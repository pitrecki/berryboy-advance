package berryboy;

import static berryboy.EmulatorFixtures.STACK_TOP;
import static berryboy.EmulatorFixtures.arm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
@DisplayName("ARM memory transfers")
class ArmTransferTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "LDR rotates an unaligned word, 0xe5901001, 0xff123480",
            "LDRH rotates an odd halfword, 0xe1d010b1, 0xff000080",
            "Odd LDRSH reads a signed byte, 0xe1d010f1, -128",
            "LDRSB sign-extends a byte, 0xe1d010d1, -128"
    })
    void appliesArm7UnalignedLoadRules(String name, long opcode, long expected) {
        var machine = arm((int) opcode);
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.memory.write32(Memory.EWRAM_BASE, 0x123480ff);

        machine.cpu.step();

        assertEquals((int) expected, machine.cpu.register(1));
    }

    @Test
    void updatesBaseForPostIndexedStoreAndPreIndexedLoad() {
        var machine = arm(
                0xe4801004, // STR r1,[r0],#4
                0xe5302004  // LDR r2,[r0,#-4]!
        );
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.cpu.setRegister(1, 0x12345678);

        machine.runInstructions(2);

        assertAll(
                () -> assertEquals(Memory.EWRAM_BASE, machine.cpu.register(0)),
                () -> assertEquals(0x12345678, machine.cpu.register(2)));
    }

    @Test
    void usesShiftedRegisterOffset() {
        var machine = arm(0xe7801102, 0xe7903102); // STR/LDR with r2 LSL #2
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.cpu.setRegister(1, 123);
        machine.cpu.setRegister(2, 3);

        machine.runInstructions(2);

        assertAll(
                () -> assertEquals(123, machine.cpu.register(3)),
                () -> assertEquals(123, machine.memory.read32(Memory.EWRAM_BASE + 12)));
    }

    @Test
    void restoresRegistersAndStackPointerWithBlockTransfers() {
        var machine = arm(
                0xe92d4003, // STMFD sp!,{r0,r1,lr}
                0xe8bd400c  // LDMFD sp!,{r2,r3,lr}
        );
        machine.cpu.setRegister(0, 11);
        machine.cpu.setRegister(1, 22);
        machine.cpu.setRegister(14, 33);

        machine.runInstructions(2);

        assertAll(
                () -> assertEquals(11, machine.cpu.register(2)),
                () -> assertEquals(22, machine.cpu.register(3)),
                () -> assertEquals(33, machine.cpu.register(14)),
                () -> assertEquals(STACK_TOP, machine.cpu.register(13)));
    }

    @Test
    void swapsUsingRotatedLoadAndAlignedStore() {
        var machine = arm(0xe1002091); // SWP r2,r1,[r0]
        machine.cpu.setRegister(0, Memory.EWRAM_BASE + 1);
        machine.cpu.setRegister(1, 0xaabbccdd);
        machine.memory.write32(Memory.EWRAM_BASE, 0x11223344);

        machine.cpu.step();

        assertAll(
                () -> assertEquals(0x44112233, machine.cpu.register(2)),
                () -> assertEquals(0xaabbccdd, machine.memory.read32(Memory.EWRAM_BASE)));
    }

    @Test
    void storesOriginalBaseWhenItIsFirstInRegisterList() {
        var machine = arm(0xe8a00003); // STMIA r0!,{r0,r1}
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.cpu.setRegister(1, 22);

        machine.cpu.step();

        assertEquals(Memory.EWRAM_BASE, machine.memory.read32(Memory.EWRAM_BASE));
    }

    @Test
    void storesUpdatedBaseWhenItFollowsAnotherRegister() {
        var machine = arm(0xe8a10003); // STMIA r1!,{r0,r1}
        machine.cpu.setRegister(1, Memory.EWRAM_BASE);

        machine.cpu.step();

        assertEquals(Memory.EWRAM_BASE + 8, machine.memory.read32(Memory.EWRAM_BASE + 4));
    }

    @Test
    void preservesLoadedBaseInsteadOfApplyingWriteback() {
        var machine = arm(0xe8b00003); // LDMIA r0!,{r0,r1}
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.memory.write32(Memory.EWRAM_BASE, 17);

        machine.cpu.step();

        assertEquals(17, machine.cpu.register(0));
    }
}
