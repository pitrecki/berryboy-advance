package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Production JSR-75/RMS storage, with failures at real filesystem-double write boundaries. */
@Tag("build")
class PhoneStorageInterruptionTest {
    @TempDir static Path classes;
    @TempDir Path temporary;

    @BeforeAll
    static void compileProductionStorage() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        assertThat(PhoneHarness.compile(root, classes, false)).isZero();
    }

    static Stream<Arguments> failures() {
        return Stream.of("truncate", "partial", "flush", "output_close", "file_close", "pointer")
                .flatMap(phase -> Stream.of("st0", "sav").map(kind -> Arguments.of(phase, kind)));
    }

    static Stream<Arguments> firstWriteFailures() {
        return Stream.of("partial", "pointer")
                .flatMap(phase -> Stream.of("st0", "sav").map(kind -> Arguments.of(phase, kind)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void failedReplacementKeepsTheCommittedGenerationAndCanBeRetried(String phase, String kind) throws Exception {
        try (var storage = new StorageCase(temporary)) {
            var older = payload(kind, 1); var active = payload(kind, 2); var candidate = payload(kind, 3);
            storage.write(kind, older); storage.write(kind, active);
            var committed = storage.metadata(kind);
            assertThat(committed[0]).isEqualTo((byte) 1);
            storage.fail(phase);
            assertThatThrownBy(() -> storage.write(kind, candidate))
                    .isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(storage.failureType(phase));
            assertThat(storage.metadata(kind)).as("Pointer must not publish a failed generation").isEqualTo(committed);
            assertThat(Files.readAllBytes(storage.file(kind, 1))).isEqualTo(active);
            var staged = Files.readAllBytes(storage.file(kind, 0));
            if (phase.equals("partial")) assertThat(staged).isEqualTo(Arrays.copyOf(candidate, 13));
            else if (phase.equals("truncate")) assertThat(staged).isEmpty();
            else assertThat(staged).isEqualTo(candidate);
            storage.reopen();
            assertThat(storage.read(kind)).as("A new storage instance must read the previous generation").isEqualTo(active);
            storage.write(kind, candidate); storage.reopen();
            assertThat(storage.read(kind)).isEqualTo(candidate);
            assertThat(storage.metadata(kind)[0]).isZero();
            assertThat(Files.readAllBytes(storage.file(kind, 1))).isEqualTo(active);
            assertThat(Files.readAllBytes(storage.original)).isEqualTo(storage.seed);
        }
    }

    @ParameterizedTest
    @MethodSource("firstWriteFailures")
    void anInterruptedFirstWriteIsNotPublishedAndDoesNotHideTheOriginalSave(String phase, String kind) throws Exception {
        try (var storage = new StorageCase(temporary)) {
            var candidate = payload(kind, 4); storage.fail(phase);
            assertThatThrownBy(() -> storage.write(kind, candidate)).hasCauseInstanceOf(storage.failureType(phase));
            storage.reopen();
            assertThat(storage.metadata(kind)).isNull();
            assertThat(storage.read(kind)).isNull();
            assertThat(storage.initialBackup()).isEqualTo(storage.seed);
            assertThat(storage.slots()[0]).isEqualTo("Empty");
            if (phase.equals("partial")) assertThat(Files.readAllBytes(storage.file(kind, 0))).hasSize(13);
            else assertThat(Files.readAllBytes(storage.file(kind, 0))).isEqualTo(candidate);
            storage.write(kind, candidate); storage.reopen();
            assertThat(storage.read(kind)).isEqualTo(candidate);
            assertThat(Files.readAllBytes(storage.original)).isEqualTo(storage.seed);
        }
    }

    @Test
    void batteryAndStateSlotFailuresKeepTheirSeparateCommitPointers() throws Exception {
        try (var storage = new StorageCase(temporary)) {
            var battery = payload("sav", 5); var state = payload("st0", 6);
            storage.write("sav", battery); storage.write("st0", state);
            storage.fail("partial");
            assertThatThrownBy(() -> storage.write("sav", payload("sav", 7))).hasCauseInstanceOf(IOException.class);
            storage.reopen();
            assertThat(storage.read("sav")).isEqualTo(battery);
            assertThat(storage.read("st0")).isEqualTo(state);
            storage.fail("pointer");
            assertThatThrownBy(() -> storage.write("st0", payload("st0", 8)))
                    .hasCauseInstanceOf(storage.failureType("pointer"));
            storage.reopen();
            assertThat(storage.read("sav")).isEqualTo(battery);
            assertThat(storage.read("st0")).isEqualTo(state);
            assertThat(Files.readAllBytes(storage.original)).isEqualTo(storage.seed);
        }
    }

    private static byte[] payload(String kind, int value) {
        var result = new byte[kind.equals("sav") ? 131072 : 8192]; Arrays.fill(result, (byte) value); return result;
    }

    private static final class StorageCase implements AutoCloseable {
        private final URLClassLoader loader;
        private final Constructor<?> constructor;
        private final Method read, write, readMetadata, initialBackup, slots;
        private final String url, prefix, key;
        private final Path original;
        private final byte[] seed = payload("sav", 17);
        private Object instance;

        StorageCase(Path directory) throws Exception {
            original = directory.resolve("game.sav"); Files.write(original, seed);
            url = directory.resolve("game.gba").toUri().toString();
            loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
            var type = loader.loadClass("berryboy.phone.PhoneStorage");
            constructor = type.getDeclaredConstructor(String.class, int.class); constructor.setAccessible(true); reopen();
            read = method(type, "read", String.class); write = method(type, "write", String.class, byte[].class);
            initialBackup = method(type, "initialBackup", int.class); slots = method(type, "slots");
            readMetadata = method(loader.loadClass("berryboy.phone.PhoneRecords"), "read", String.class);
            var filePrefix = type.getDeclaredField("filePrefix"); filePrefix.setAccessible(true); prefix = (String) filePrefix.get(instance);
            var recordKey = type.getDeclaredField("key"); recordKey.setAccessible(true); key = (String) recordKey.get(instance);
        }

        void reopen() throws Exception { instance = constructor.newInstance(url, 0x12345678); }
        void write(String kind, byte[] bytes) throws Exception { write.invoke(instance, kind, bytes); }
        byte[] read(String kind) throws Exception { return (byte[]) read.invoke(instance, kind); }
        byte[] metadata(String kind) throws Exception { return (byte[]) readMetadata.invoke(null, key + kind); }
        byte[] initialBackup() throws Exception { return (byte[]) initialBackup.invoke(instance, seed.length); }
        String[] slots() throws Exception { return (String[]) slots.invoke(instance); }
        Path file(String kind, int generation) { return Path.of(URI.create(prefix + kind + "-" + generation)); }

        void fail(String phase) throws Exception {
            var connector = loader.loadClass("javax.microedition.io.Connector");
            if (phase.equals("partial")) connector.getField("failAfterBytes").setInt(null, 13);
            else if (phase.equals("pointer")) loader.loadClass("javax.microedition.rms.RecordStore").getField("failCommit").setBoolean(null, true);
            else connector.getField(switch (phase) {
                case "truncate" -> "failTruncate";
                case "flush" -> "failFlush";
                case "output_close" -> "failOutputClose";
                case "file_close" -> "failConnectionClose";
                default -> throw new IllegalArgumentException(phase);
            }).setBoolean(null, true);
        }

        Class<? extends Throwable> failureType(String phase) throws Exception {
            return phase.equals("pointer") ? loader.loadClass("javax.microedition.rms.RecordStoreException").asSubclass(Throwable.class) : IOException.class;
        }
        public void close() throws IOException { loader.close(); }
        private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
            var result = type.getDeclaredMethod(name, arguments); result.setAccessible(true); return result;
        }
    }
}
