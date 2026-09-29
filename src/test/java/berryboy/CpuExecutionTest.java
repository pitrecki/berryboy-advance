package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.blankRom;
import static berryboy.EmulatorFixtures.writeHalfword;
import static berryboy.EmulatorFixtures.writeWord;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("CPU program execution")
class CpuExecutionTest {
    @Test
    void executesMixedArmAndThumbProgramAndReturnsToArm() {
        var machine = new Machine(mixedInstructionProgram());
        var cpu = machine.cpu;
        cpu.setRegister(3, Memory.EWRAM_BASE);
        cpu.setRegister(5, Memory.ROM_BASE + 0x41);
        cpu.setRegister(14, Memory.ROM_BASE + 0x80);

        machine.runInstructions(8);
        assertAll(
                () -> assertEquals(12, cpu.register(1), "ARM addition"),
                () -> assertEquals(1, cpu.register(2), "conditional MOV"),
                () -> assertEquals(12, cpu.register(4), "store and load"),
                () -> assertTrue(cpu.isThumb(), "BX enters Thumb"));

        machine.runInstructions(6);
        assertAll(
                () -> assertEquals(12, cpu.register(6), "Thumb addition"),
                () -> assertEquals(1, cpu.register(7), "conditional branch"),
                () -> assertFalse(cpu.isThumb(), "BX returns to ARM"),
                () -> assertEquals(Memory.ROM_BASE + 0x80, cpu.pc()));
    }

    @Test
    void branchesPastAnInstructionAfterSubtractionUnderflow() {
        var machine = arm(
                0xe3a00000, // MOV r0,#0
                0xe2500001, // SUBS r0,r0,#1
                0xea000000, // B skips next instruction
                0xe3a01063, // MOV r1,#99
                0xe3a01004  // MOV r1,#4
        );

        machine.runInstructions(4);

        assertAll(
                () -> assertEquals(-1, machine.cpu.register(0)),
                () -> assertTrue(machine.cpu.negative()),
                () -> assertFalse(machine.cpu.carry(), "borrow clears carry"),
                () -> assertEquals(4, machine.cpu.register(1)));
    }

    private static byte[] mixedInstructionProgram() {
        var image = blankRom();
        writeWord(image, 0, 0xe3a00005);    // MOV r0,#5
        writeWord(image, 4, 0xe2801007);    // ADD r1,r0,#7
        writeWord(image, 8, 0xe351000c);    // CMP r1,#12
        writeWord(image, 12, 0x03a02001);   // MOVEQ r2,#1
        writeWord(image, 16, 0x13a02002);   // MOVNE r2,#2 (skipped)
        writeWord(image, 20, 0xe5831000);   // STR r1,[r3]
        writeWord(image, 24, 0xe5934000);   // LDR r4,[r3]
        writeWord(image, 28, 0xe12fff15);   // BX r5
        writeHalfword(image, 0x40, 0x2609); // MOV r6,#9
        writeHalfword(image, 0x42, 0x3603); // ADD r6,#3
        writeHalfword(image, 0x44, 0x2e0c); // CMP r6,#12
        writeHalfword(image, 0x46, 0xd000); // BEQ skips MOV #0
        writeHalfword(image, 0x48, 0x2700); // MOV r7,#0
        writeHalfword(image, 0x4a, 0x2701); // MOV r7,#1
        writeHalfword(image, 0x4c, 0x4770); // BX lr
        return image;
    }
}
