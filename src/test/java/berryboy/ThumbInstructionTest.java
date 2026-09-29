package berryboy;

import static berryboy.EmulatorFixtures.STACK_TOP;
import static berryboy.EmulatorFixtures.thumb;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("Thumb instruction families")
class ThumbInstructionTest {
    @Test
    void combinesArithmeticShiftsLogicAndMultiply() {
        var machine = thumb(
                0x2005, // MOV r0,#5
                0x2103, // MOV r1,#3
                0x1842, // ADD r2,r0,r1
                0x1e53, // SUB r3,r2,#1
                0x009b, // LSL r3,r3,#2
                0x404b, // EOR r3,r1
                0x4353  // MUL r3,r2
        );

        machine.runInstructions(7);

        assertEquals(((7 << 2) ^ 3) * 8, machine.cpu.register(3));
    }

    @Test
    void restoresStackRegistersAndKeepsThumbWhenPoppingPc() {
        var machine = thumb(0xb503, 0x2000, 0x2100, 0xbd03); // PUSH; clear r0/r1; POP
        machine.cpu.setRegister(0, 17);
        machine.cpu.setRegister(1, 19);
        machine.cpu.setRegister(14, Memory.ROM_BASE + 9);

        machine.runInstructions(4);

        assertAll(
                () -> assertEquals(17, machine.cpu.register(0)),
                () -> assertEquals(19, machine.cpu.register(1)),
                () -> assertEquals(Memory.ROM_BASE + 8, machine.cpu.pc()),
                () -> assertTrue(machine.cpu.isThumb()),
                () -> assertEquals(STACK_TOP, machine.cpu.register(13)));
    }

    @Test
    void callsAndReturnsUsingTwoPartBranchWithLink() {
        var machine = thumb(
                0xf000, 0xf802, // BL to offset 8
                0x2709, 0xe7fe, // MOV r7,#9; B to itself
                0x2607, 0x4770  // MOV r6,#7; BX lr
        );

        machine.runInstructions(5);

        assertAll(
                () -> assertEquals(7, machine.cpu.register(6), "subroutine"),
                () -> assertEquals(9, machine.cpu.register(7), "caller resumed"),
                () -> assertEquals(Memory.ROM_BASE + 5, machine.cpu.register(14), "Thumb return address"));
    }

    @Test
    void usesHighRegistersAndPcOrSpRelativeAddresses() {
        var machine = thumb(
                0x4680, // MOV r8,r0
                0x4641, // MOV r1,r8
                0xa200, // ADR r2,PC-relative address
                0xb081, // SUB sp,#4
                0xab01  // ADD r3,sp,#4
        );
        machine.cpu.setRegister(0, 123);

        machine.runInstructions(5);

        assertAll(
                () -> assertEquals(123, machine.cpu.register(8)),
                () -> assertEquals(123, machine.cpu.register(1)),
                () -> assertEquals(Memory.ROM_BASE + 8, machine.cpu.register(2)),
                () -> assertEquals(STACK_TOP, machine.cpu.register(3)));
    }

    @Test
    void transfersHalfwordsBytesAndStackRelativeWords() {
        var machine = thumb(
                0x8008, 0x880a, // STRH r0,[r1]; LDRH r2,[r1]
                0x7048, 0x784b, // STRB r0,[r1,#1]; LDRB r3,[r1,#1]
                0x9000, 0x9c00  // STR r0,[sp]; LDR r4,[sp]
        );
        machine.cpu.setRegister(0, 0x123480ff);
        machine.cpu.setRegister(1, Memory.EWRAM_BASE);

        machine.runInstructions(6);

        assertAll(
                () -> assertEquals(0x80ff, machine.cpu.register(2)),
                () -> assertEquals(0xff, machine.cpu.register(3)),
                () -> assertEquals(0x123480ff, machine.cpu.register(4)));
    }

    @Test
    void signExtendsRegisterOffsetLoadsAtOddAddresses() {
        var machine = thumb(0x5688, 0x5e8b); // LDRSB r0,[r1,r2]; LDRSH r3,[r1,r2]
        machine.cpu.setRegister(1, Memory.EWRAM_BASE);
        machine.cpu.setRegister(2, 1);
        machine.memory.write16(Memory.EWRAM_BASE, 0x80ff);

        machine.runInstructions(2);

        assertAll(
                () -> assertEquals(-128, machine.cpu.register(0), "signed byte"),
                () -> assertEquals(-128, machine.cpu.register(3), "odd signed halfword"));
    }
}
