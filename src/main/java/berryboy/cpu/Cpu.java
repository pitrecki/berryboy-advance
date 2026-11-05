package berryboy.cpu;

import berryboy.EmulationException;
import berryboy.bus.Memory;

/**
 * ARM7TDMI execution loop. One step services a DMA unit, interrupt, HALT event
 * or instruction phase and advances guest cycles. A decoded instruction can
 * wait for DMA before data/refill accesses or during internal cycles. Prepared data
 * operations run once, and retirement waits until the instruction completes.
 * The interpreters and register banks are created once and reused for every step.
 */
public final class Cpu {
    public static final int STACK_POINTER = 13;
    public static final int USER = 0x10;
    public static final int FIQ = 0x11;
    public static final int IRQ = 0x12;
    public static final int SUPERVISOR = 0x13;
    public static final int ABORT = 0x17;
    public static final int UNDEFINED = 0x1b;
    public static final int SYSTEM = 0x1f;

    public static final int N = 0x80000000;
    public static final int Z = 0x40000000;
    public static final int C = 0x20000000;
    public static final int V = 0x10000000;
    public static final int T = 0x20;

    private final Memory memory;
    private final CpuState state;
    private final ArmInterpreter arm;
    private final ThumbInterpreter thumb;
    private final CpuDataTransfers transfers;
    private long instructionCount;
    private boolean instructionInFlight;
    private int remainingInternalCycles;
    private boolean dataPhase;
    private boolean busGranted;
    private int pendingAddress;
    private int pendingOpcode;
    private boolean fetchPending;
    private boolean pendingThumb;
    private boolean pendingIrq;

    public Cpu(Memory memory) {
        this.memory = memory;
        this.state = new CpuState();
        this.transfers = new CpuDataTransfers(state, memory);
        this.arm = new ArmInterpreter(state, memory, transfers);
        this.thumb = new ThumbInterpreter(state, memory, transfers);
    }

    public void step() {
        if (instructionInFlight) {
            try {
                if (memory.busTiming().refilling()) resumeRefill();
                else resumeInstructionTiming();
            } catch (EmulationException error) {
                throw instructionFailure(error, pendingAddress, pendingOpcode);
            } finally {
                memory.finishInstruction();
            }
            return;
        }
        if (memory.busTiming().refilling()) {
            resumeRefill(); // IRQ entry has no instruction to retire.
            return;
        }
        if (!fetchPending) {
            if (memory.halted()) {
                if (serviceDma(false)) return;
                if (!memory.interrupts().wakePending()) {
                    memory.clock().advanceTo(memory.clock().nextDeadline());
                    return;
                }
                memory.wake();
            }
            // The bus operation has started even if DMA must finish first.
            // IRQ acceptance belongs here, not after every yielded DMA unit.
            pendingAddress = state.programCounter();
            pendingThumb = state.isThumb();
            pendingIrq = memory.interrupts().irqPending() && (state.status() & 0x80) == 0;
            pendingOpcode = 0;
            busGranted = false;
            fetchPending = true;
        }
        var address = pendingAddress;
        var thumbMode = pendingThumb;
        int opcode = 0;
        try {
            if (waitForBus()) return;
            fetchPending = false;
            if (pendingIrq) {
                if (!memory.hasBios()) throw new EmulationException("IRQ needs a supplied BIOS");
                // Preserve the existing discarded-fetch cost. Its wider bus/
                // pipeline timing is qualified separately from acceptance.
                memory.clock().advance(1);
                state.enterIrq();
                beginRefill();
                resumeRefill();
                return;
            }
            opcode = memory.fetch(address, thumbMode);
            pendingOpcode = opcode;
            transfers.reset();
            busGranted = false;
            state.beginInstruction(thumbMode ? 2 : 4);
            var execute = thumbMode || state.conditionPassed(opcode >>> 28);
            var internal = execute ? InstructionTiming.internal(opcode, thumbMode, state) : 0;
            if (thumbMode) {
                thumb.execute(opcode);
            } else if (execute) {
                arm.execute(opcode);
            }
            remainingInternalCycles = internal;
            dataPhase = true;
            instructionInFlight = true;
            // DMA accesses must not inherit the CPU's timed-data context.
            memory.finishInstruction();
            resumeInstructionTiming();
        } catch (EmulationException error) {
            throw instructionFailure(error, address, opcode);
        } finally {
            memory.finishInstruction();
        }
    }

    private void resumeInstructionTiming() {
        if (dataPhase) {
            while (transfers.hasNext()) {
                if (!transfers.busLocked() && waitForBus()) return;
                memory.resumeInstructionData();
                try {
                    transfers.transferNext();
                } finally {
                    memory.finishInstruction();
                }
            }
            transfers.finish();
            dataPhase = false;
        }
        // Preserve dispatch of zero-delay device writes even for instructions
        // with no internal cycles. Readiness at the end of the final cycle
        // belongs to the next bus operation, not to an extra retirement stall.
        memory.busTiming().internal(0, state.branched());
        while (remainingInternalCycles > 0 || memory.dma().ownsBus()) {
            if (serviceDma(true)) return; // At most one DMA unit per public step.
            memory.busTiming().internal(1, state.branched());
            remainingInternalCycles--;
        }
        if (state.branched()) {
            beginRefill();
            resumeRefill();
        } else retireInstruction();
    }

    private void beginRefill() {
        busGranted = false;
        memory.busTiming().beginRefill(state.programCounter(), state.isThumb() ? 2 : 4);
    }

    private void resumeRefill() {
        while (memory.busTiming().refilling()) {
            if (waitForBus()) return;
            memory.busTiming().refillNext();
        }
        if (instructionInFlight) retireInstruction();
    }

    private boolean waitForBus() {
        if (busGranted) {
            busGranted = false;
            return false;
        }
        return serviceDma(false);
    }

    private boolean serviceDma(boolean overlapInternal) {
        var before = memory.clock().now();
        if (!memory.dma().runPending()) return false;
        if (instructionInFlight && overlapInternal) {
            // Internal CPU work overlaps this DMA interval. Keep retirement
            // blocked until ownership ends, even when its cycle budget is used.
            var elapsed = memory.clock().now() - before;
            remainingInternalCycles = elapsed >= remainingInternalCycles ? 0
                    : remainingInternalCycles - (int) elapsed;
            if (!memory.dma().ownsBus() && remainingInternalCycles == 0) {
                // Start the refill but yield after this DMA unit. Resuming it
                // here could service a second interval within one public step.
                if (state.branched()) beginRefill();
                else retireInstruction();
            }
        } else if ((fetchPending || (instructionInFlight && dataPhase) || memory.busTiming().refilling())
                && !memory.dma().ownsBus()) {
            // NBA bus arbitration grants the waiting access after the interval
            // completes, even if a new request arrives during its release.
            busGranted = true;
        }
        return true;
    }

    private EmulationException instructionFailure(EmulationException error, int address, int opcode) {
        // Completed accesses remain visible; never replay them after a fault.
        state.writeRegister(CpuState.PROGRAM_COUNTER, address);
        memory.busTiming().cancelRefill();
        instructionInFlight = false;
        fetchPending = false;
        pendingIrq = false;
        remainingInternalCycles = 0;
        dataPhase = false;
        busGranted = false;
        transfers.reset();
        return new EmulationException(error.getMessage() + " [opcode=0x" + Integer.toHexString(opcode)
                + ", pc=0x" + Integer.toHexString(address) + "]");
    }

    private void retireInstruction() {
        instructionInFlight = false;
        instructionCount++;
    }

    /** Registers/PC can reflect executed work before the instruction retires. */
    public boolean instructionInFlight() { return instructionInFlight; }

    /** Waiting for an opcode/discarded IRQ fetch; no decoded instruction yet. */
    public boolean fetchInFlight() { return fetchPending; }

    public int register(int index) {
        return state.register(index);
    }

    public void setRegister(int index, int value) {
        state.writeRegister(index, value);
    }

    public int pc() {
        return state.programCounter();
    }

    public void setPc(int address) {
        memory.busTiming().invalidateFetch();
        state.writeRegister(CpuState.PROGRAM_COUNTER, address);
    }

    public int cpsr() {
        return state.status();
    }

    public void setCpsr(int value) {
        state.writeStatus(value);
    }

    public long instructions() {
        return instructionCount;
    }

    public boolean isThumb() {
        return state.isThumb();
    }

    public boolean negative() {
        return state.negative();
    }

    public boolean zero() {
        return state.zero();
    }

    public boolean carry() {
        return state.carry();
    }

    public boolean overflow() {
        return state.overflow();
    }
}
