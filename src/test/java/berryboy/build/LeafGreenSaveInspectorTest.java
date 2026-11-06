package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic Flash images only; no game data or player saves are required. */
class LeafGreenSaveInspectorTest {
    private static final int[] LENGTHS = {0xF24, 0xF80, 0xF80, 0xF80, 0xEE8,
            0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0x7D0};
    @TempDir Path directory;

    @Test
    void inspectionPreservesTheExistingSummaryInterface() throws Exception {
        var result = run(fixture(0x11223344), null);
        assertThat(result.exit).isZero();
        assertThat(result.output).contains("\"valid_sectors\": 14", "\"latest_generation\": 1",
                "\"x\": 7", "\"y\": 6", "\"money\": 3000", "\"frames\": 51");
    }

    @Test
    void identicalFilesPassTheStrictComparison() throws Exception {
        var bytes = fixture(0x11223344);
        var result = run(bytes, bytes);
        assertThat(result.exit).isZero();
        assertThat(result.output).contains("\"raw_equal\": true", "\"raw_different_bytes\": 0",
                "\"decoded_payload_differences\": []");
    }

    @Test
    void rekeyingExplainsCiphertextWithoutPassingStrictEquality() throws Exception {
        var result = run(fixture(0x11223344), fixture(0xEEDDCCBB));
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"raw_equal\": false", "\"rekeyed_encrypted_fields\": 654",
                "\"encryption_key\": 4", "\"unexplained\": 0", "\"different_bytes\": 4");
    }

    @Test
    void thePreviousGenerationIsComparedToo() throws Exception {
        var result = run(fixture(0x11223344, 0x01020304), fixture(0xEEDDCCBB, 0x01020304));
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"valid_sectors\": 28", "\"latest_generation\": 2",
                "\"rekeyed_encrypted_fields\": 654", "\"unexplained\": 0", "\"generation\": 1");
    }

    @Test
    void playTimeRemainsAnExplicitDecodedDifference() throws Exception {
        var left = fixture(0x11223344); var right = left.clone(); right[0x12] = 52; checksum(right, 0, 0);
        var result = run(left, right);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"play_time\": 1", "\"different_bytes\": 1",
                "\"frames\": 51", "\"frames\": 52");
    }

    @Test
    void realEncryptedChangesAreNotClassifiedAsRekeying() throws Exception {
        var left = fixture(0x11223344); var right = fixture(0xEEDDCCBB);
        buffer(right).putInt(0x1000 + 0x290, 4000 ^ 0xEEDDCCBB); checksum(right, 1, 1);
        var result = run(left, right);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"rekeyed_encrypted_fields\": 650", "\"money\": 4000")
                .doesNotContain("\"unexplained\": 0");
    }

    @Test
    void unknownPayloadChangesStayUnexplained() throws Exception {
        var left = fixture(0x11223344); var right = left.clone(); right[0x5045] = 17; checksum(right, 5, 5);
        var result = run(left, right);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"unexplained\": 1", "\"section\": 5", "\"different_bytes\": 1");
    }

    @Test
    void unusedPaddingIsStillPartOfTheStrictBinaryResult() throws Exception {
        var left = fixture(0x11223344); var right = left.clone(); right[0xF30] = 17;
        var result = run(left, right);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"raw_different_bytes\": 1", "\"unexplained\": 1",
                "\"decoded_payload_differences\": []");
    }

    @Test
    void checksumCorruptionFailsBeforeClassification() throws Exception {
        var left = fixture(0x11223344); var right = left.clone(); right[0] ^= 1;
        var result = run(left, right);
        assertThat(result.exit).isEqualTo(2);
        assertThat(result.output).contains("Checksum mismatch").doesNotContain("raw_equal");
    }

    private Result run(byte[] left, byte[] right) throws Exception {
        var first = directory.resolve("left.sav"); Files.write(first, left);
        var script = Path.of(System.getProperty("berryboy.projectRoot", "."), "tools", "inspect_leafgreen_save.py");
        var command = new java.util.ArrayList<>(java.util.List.of("python3", script.toString(), first.toString()));
        var second = directory.resolve("right.sav");
        if (right != null) { Files.write(second, right); command.add("--compare"); command.add(second.toString()); }
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exit = process.waitFor();
        assertThat(Files.readAllBytes(first)).isEqualTo(left);
        if (right != null) assertThat(Files.readAllBytes(second)).isEqualTo(right);
        return new Result(exit, output);
    }

    private static byte[] fixture(int... keys) {
        var bytes = new byte[0x20000]; Arrays.fill(bytes, (byte) 0xff); var out = buffer(bytes);
        for (var generation = 0; generation < keys.length; generation++) {
            var base = generation * 14 * 0x1000;
            for (var section = 0; section < 14; section++) {
                var start = base + section * 0x1000;
                Arrays.fill(bytes, start, start + 0x1000, (byte) 0);
                out.putShort(start + 0xFF4, (short) section); out.putInt(start + 0xFF8, 0x08012025);
                out.putInt(start + 0xFFC, generation + 1);
            }
            bytes[base + 0x11] = 8; bytes[base + 0x12] = 51;
            out.putInt(base + 0xF20, keys[generation]); out.putInt(base + 0xAF8, keys[generation]);
            out.putShort(base + 0x1000, (short) 7); out.putShort(base + 0x1002, (short) 6);
            bytes[base + 0x1004] = 4; bytes[base + 0x1005] = 1;
            putWorldField(out, base, 0x290, 3000 ^ keys[generation], 4);
            putWorldField(out, base, 0x294, keys[generation], 2);
            var pockets = new int[][]{{0x310, 42}, {0x3B8, 30}, {0x430, 13}, {0x464, 58}, {0x54C, 43}};
            for (var pocket : pockets) for (var item = 0; item < pocket[1]; item++)
                putWorldField(out, base, pocket[0] + item * 4 + 2, keys[generation], 2);
            for (var stat = 0; stat < 64; stat++) putWorldField(out, base, 0x1200 + stat * 4, keys[generation], 4);
            for (var tower = 0; tower < 4; tower++) putWorldField(out, base, 0x3D3C + tower * 12, keys[generation], 4);
            for (var section = 0; section < 14; section++) checksum(bytes, generation * 14 + section, section);
        }
        return bytes;
    }

    private static void putWorldField(ByteBuffer out, int base, int offset, int value, int size) {
        var physical = base + (1 + offset / 0xF80) * 0x1000 + offset % 0xF80;
        if (size == 4) out.putInt(physical, value); else out.putShort(physical, (short) value);
    }

    private static void checksum(byte[] bytes, int sector, int section) {
        var out = buffer(bytes); var total = 0; var base = sector * 0x1000;
        for (var offset = 0; offset < LENGTHS[section]; offset += 4) total += out.getInt(base + offset);
        out.putShort(base + 0xFF6, (short) ((total >>> 16) + (total & 0xffff)));
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private record Result(int exit, String output) { }
}
