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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({
        "4294967295,0,0,22", "0,4294967295,0,11",
        "4294967294,4294967295,4294967295,22", "4294967295,4294967294,4294967295,11",
        "0,1,1,22", "1,0,1,11",
        "4294967294,0,4294967294,11", "0,4294967294,4294967294,22"
    })
    void selectsTheGameCounterIncludingItsSpecificWrapPair(long first, long second, long latest, int x) throws Exception {
        var bytes = fixture(0x11223344, 0x55667788);
        counter(bytes, 0, first); counter(bytes, 1, second);
        buffer(bytes).putShort(0x1000, (short) 11); checksum(bytes, 1, 1);
        buffer(bytes).putShort(15 * 0x1000, (short) 22); checksum(bytes, 15, 1);
        var result = run(bytes, null);
        assertThat(result.exit).isZero();
        assertThat(result.output).contains("\"latest_generation\": " + latest + ",", "\"x\": " + x + ",");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 4294967295L})
    void aSingleCompleteBoundaryCounterIsNotMistakenForEmptyFlash(long generation) throws Exception {
        var bytes = fixture(0x11223344); counter(bytes, 0, generation);
        var result = run(bytes, null);
        assertThat(result.exit).isZero();
        assertThat(result.output).contains("\"valid_sectors\": 14", "\"latest_generation\": " + generation + ",");
    }

    @Test
    void wrappingStillComparesTheOlderGenerationAndRejectsCorruptPayloads() throws Exception {
        var left = fixture(0x11223344, 0x55667788); counter(left, 0, 0xFFFFFFFFL); counter(left, 1, 0);
        assertThat(run(left, left).exit).isZero();
        var right = left.clone(); right[0x5045] = 17; checksum(right, 5, 5);
        var comparison = run(left, right);
        assertThat(comparison.exit).isEqualTo(1);
        assertThat(comparison.output).contains("\"latest_generation\": 0", "\"generation\": 4294967295",
                "\"raw_equal\": false", "\"unexplained\": 1");
        right[0x5045] ^= 1;
        var invalid = run(left, right);
        assertThat(invalid.exit).isEqualTo(2);
        assertThat(invalid.output).contains("Checksum mismatch").doesNotContain("raw_equal");
    }

    @Test
    void wrappingDoesNotAcceptAnIncompleteGeneration() throws Exception {
        var bytes = fixture(0x11223344, 0x55667788); counter(bytes, 0, 0xFFFFFFFFL); counter(bytes, 1, 0);
        Arrays.fill(bytes, 27 * 0x1000, 28 * 0x1000, (byte) 0xFF);
        var result = run(bytes, null);
        assertThat(result.exit).isEqualTo(2);
        assertThat(result.output).contains("Incomplete saved generation").doesNotContain("latest_generation");
    }

    private static void counter(byte[] bytes, int slot, long value) {
        for (var section = 0; section < 14; section++) buffer(bytes).putInt((slot * 14 + section) * 0x1000 + 0xFFC, (int) value);
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

    @Test
    void activityLeavesInactiveObjectsAndScenesEmpty() throws Exception {
        var result = run(fixture(0x11223344), null, true);
        assertThat(result.exit).isZero();
        assertThat(result.output).contains("\"objects\": []", "\"quest_scenes\": []");
    }

    @Test
    void activityReadsSignedObjectCoordinatesAndPackedDirections() throws Exception {
        var bytes = fixture(0x11223344); var object = new byte[0x24]; object[0] = 1;
        object[8] = 2; object[10] = 3; object[0x18] = 0x43; object[0x20] = 1;
        buffer(object).putShort(0xC, (short) 20).putShort(0xE, (short) 24)
                .putShort(0x10, (short) -2).putShort(0x12, (short) 25);
        writeWorld(bytes, 0x6A0 + 2 * 0x24, object);
        var result = run(bytes, null, true);
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("\"slot\": 2", "\"local_id\": 2", "\"x\": 20", "\"y\": 24", "\"x\": -2", "\"x\": -9",
                "\"y\": 18", "\"facing_direction\": 3", "\"movement_direction\": 4", "\"previous_movement_direction\": 1");
    }

    @Test
    void activityDecodesMixedCommandsAcrossALogicalSectionBoundary() throws Exception {
        var bytes = fixture(0x11223344); var scene = 0x1300 + 0x668;
        writeWorld(bytes, scene, new byte[]{2, 3, 0});
        var script = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < 6; i++) script.putShort((short) 2).putShort((short) (i + 10)).put(new byte[]{2, 0, 3, 0x12});
        script.putShort((short) 35).putShort((short) 6).put(new byte[]{1, 2});
        script.putShort((short) 41).putShort((short) 7).putShort((short) 39);
        writeWorld(bytes, scene + 0x568, script.array());
        var result = run(bytes, null, true);
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("\"scene_index\": 1", "\"command_count\": 9", "\"duration\": 15",
                "\"kind\": \"departed\"", "\"kind\": \"wait\"", "\"kind\": \"scene_end\"", "\"world_offset\": 7946");
    }

    @Test
    void objectActivityDoesNotRelaxStrictPayloadComparison() throws Exception {
        var left = fixture(0x11223344); var object = new byte[0x24]; object[0] = 1; object[0x10] = 17;
        writeWorld(left, 0x6A0, object); var right = left.clone(); object[0x10] = 18;
        writeWorld(right, 0x6A0, object);
        var result = run(left, right, true);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"raw_equal\": false", "\"unexplained\": 1", "\"x\": 17", "\"x\": 18");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 0x1002, 0x0FFF})
    void unsupportedOrRepeatedCommandsFailOnlyTheOptionalActivityView(int command) throws Exception {
        var bytes = fixture(0x11223344); writeWorld(bytes, 0x1300, new byte[]{1});
        writeWorld(bytes, 0x1868, new byte[]{(byte) command, (byte) (command >>> 8)});
        var plain = run(bytes, null); var activity = run(bytes, null, true);
        assertThat(plain.exit).isZero(); assertThat(plain.output).doesNotContain("\"activity\"");
        assertThat(activity.exit).isEqualTo(2); assertThat(activity.output).contains("Unsupported Quest Log command");
    }

    @Test
    void activityRequiresASceneEndWithinItsFixedScriptBuffer() throws Exception {
        var bytes = fixture(0x11223344); writeWorld(bytes, 0x1300, new byte[]{1});
        var result = run(bytes, null, true);
        assertThat(result.exit).isEqualTo(2); assertThat(result.output).contains("Missing Quest Log scene end");
    }

    @Test
    void activityCannotReadAPartialCommandFromTheNextScene() throws Exception {
        var bytes = fixture(0x11223344); writeWorld(bytes, 0x1300, new byte[]{1});
        writeWorld(bytes, 0x1868 + 248, new byte[]{41, 0, 0, 0, 2, 0});
        var result = run(bytes, null, true);
        assertThat(result.exit).isEqualTo(2); assertThat(result.output).contains("Truncated Quest Log command");
    }

    @Test
    void activityUsesTheCompactSceneObjectLayoutForInitialCoordinates() throws Exception {
        var bytes = fixture(0x11223344); writeWorld(bytes, 0x1300, new byte[]{2});
        var object = new byte[0x14]; object[0] = 1; object[7] = 2; object[9] = 3;
        buffer(object).putShort(0xA, (short) 20).putShort(0xC, (short) 24);
        writeWorld(bytes, 0x1300 + 8 + 2 * 0x14, object); writeWorld(bytes, 0x1868, new byte[]{39, 0});
        var result = run(bytes, null, true);
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("\"initial_objects\": [", "\"slot\": 2", "\"local_id\": 2",
                "\"x\": 20", "\"y\": 24", "\"command_count\": 1");
    }

    @Test
    void activityRejectsAnUnknownSceneTypeWithoutChangingPlainInspection() throws Exception {
        var bytes = fixture(0x11223344); writeWorld(bytes, 0x1300, new byte[]{3});
        assertThat(run(bytes, null).exit).isZero();
        var result = run(bytes, null, true);
        assertThat(result.exit).isEqualTo(2); assertThat(result.output).contains("Unsupported Quest Log scene type");
    }

    private Result run(byte[] left, byte[] right) throws Exception {
        return run(left, right, false);
    }

    private Result run(byte[] left, byte[] right, boolean activity) throws Exception {
        var first = directory.resolve("left.sav"); Files.write(first, left);
        var script = Path.of(System.getProperty("berryboy.projectRoot", "."), "tools", "inspect_leafgreen_save.py");
        var command = new java.util.ArrayList<>(java.util.List.of("python3", script.toString(), first.toString()));
        var second = directory.resolve("right.sav");
        if (right != null) { Files.write(second, right); command.add("--compare"); command.add(second.toString()); }
        if (activity) command.add("--activity");
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

    private static void writeWorld(byte[] bytes, int offset, byte[] data) {
        for (var i = 0; i < data.length; i++) {
            var logical = offset + i;
            bytes[(1 + logical / 0xF80) * 0x1000 + logical % 0xF80] = data[i];
        }
        for (var section = 1; section <= 4; section++) checksum(bytes, section, section);
    }

    private static void checksum(byte[] bytes, int sector, int section) {
        var out = buffer(bytes); var total = 0; var base = sector * 0x1000;
        for (var offset = 0; offset < LENGTHS[section]; offset += 4) total += out.getInt(base + offset);
        out.putShort(base + 0xFF6, (short) ((total >>> 16) + (total & 0xffff)));
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private record Result(int exit, String output) { }
}
