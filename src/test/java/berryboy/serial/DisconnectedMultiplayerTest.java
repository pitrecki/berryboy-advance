package berryboy.serial;

import berryboy.Machine;
import berryboy.MachineState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import static org.assertj.core.api.Assertions.assertThat;

/** No cable: observe pulled-up lines and waiting transfers, never fabricate a peer. */
@Tag("core")
class DisconnectedMultiplayerTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/serial/disconnected-multi.csv", numLinesToSkip = 1)
    void matchesDisconnectedReferenceWithoutShiftingOrRaisingIrq(int control, long elapsed, int readback,
            int receive0, int receive1, int receive2, int receive3, int send, int serialIrq) {
        var m = new Machine(new byte[512]);
        m.memory.write16(0x04000134, 0); m.memory.write16(0x0400012a, 0x55aa);
        m.memory.write16(0x04000128, control); m.clock.advance(elapsed);
        assertThat(m.memory.read16(0x04000128)).isEqualTo(readback);
        var received = new int[]{receive0, receive1, receive2, receive3};
        for (var i=0;i<4;i++) assertThat(m.memory.read16(0x04000120+i*2)).isEqualTo(received[i]);
        assertThat(m.memory.read16(0x0400012a)).isEqualTo(send);
        assertThat((m.memory.read16(0x04000202) >>> 7) & 1).isEqualTo(serialIrq);
    }
    @Test
    void modeChangeCancelsTheNormalShiftAndCanStartANewNormalTransfer() {
        var m = new Machine(new byte[512]);
        m.memory.write16(0x04000134, 0); m.memory.write16(0x04000128, 0x5083);
        m.clock.advance(16); m.memory.write8(0x04000129, 0x60); // Normal 32 -> multiplayer.
        m.clock.advance(1000);
        assertThat(m.memory.read16(0x04000128)).isEqualTo(0x608f);
        assertThat(m.memory.read32(0x04000120)).isZero();
        assertThat(m.memory.read16(0x04000202) & 0x80).isZero();
        m.memory.write16(0x04000128, 0x6003); m.memory.write16(0x04000128, 0x5083);
        m.clock.advance(255); assertThat(m.memory.read16(0x04000128) & 0x80).isEqualTo(0x80);
        m.clock.advance(1); assertThat(m.memory.read32(0x04000120)).isEqualTo(-1);
        assertThat(m.memory.read16(0x04000202) & 0x80).isEqualTo(0x80);
    }
    @Test
    void gpioSelectionDoesNotStartAStoredSerialMode() {
        var m = new Machine(new byte[512]); m.memory.write16(0x04000134, 0x8000);
        m.memory.write16(0x04000128, 0x7083); m.clock.advance(10000);
        assertThat(m.memory.read16(0x04000202) & 0x80).isZero();
        assertThat(m.memory.read16(0x04000128) & 0x80).isEqualTo(0x80);
    }
    @Test
    void waitingTransferSurvivesACompleteStateRoundTripWithoutCreatingADeadline() {
        var rom = new byte[512]; var original = new Machine(rom);
        original.memory.write16(0x04000134, 0); original.memory.write16(0x04000128, 0x6083);
        original.clock.advance(20);
        var restored = MachineState.load(rom, null, MachineState.save(original));
        original.clock.advance(10000); restored.clock.advance(10000);
        assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(original));
        assertThat(restored.memory.read16(0x04000128)).isEqualTo(0x608f);
        assertThat(restored.memory.read16(0x04000202) & 0x80).isZero();
    }
}
