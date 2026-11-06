package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import berryboy.cheat.CheatCode;
import berryboy.cheat.CheatList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("build")
class PhoneStorageTest {
    @TempDir Path temporary;

    @Test
    void failedFileOrPointerWritesRetainThePreviousSlotAndOriginalSave() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var output = temporary.resolve("classes");
        assertThat(PhoneHarness.compile(root, output, false)).isZero();
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var child = loader.loadClass("berryboy.phone.PhoneFiles").getDeclaredMethod("childUrl", String.class, String.class);
            child.setAccessible(true);
            assertThat(child.invoke(null, "file:///SDCard/", "Pokémon #1.gba")).isEqualTo("file:///SDCard/Pok%C3%A9mon%20%231.gba");
            var type = loader.loadClass("berryboy.phone.PhoneStorage");
            var constructor = type.getDeclaredConstructor(String.class, int.class); constructor.setAccessible(true);
            var original = temporary.resolve("game.sav"); Files.write(original, new byte[]{7, 8});
            var storage = constructor.newInstance(temporary.resolve("game.gba").toUri().toString(), 0x12345678);
            var read = type.getDeclaredMethod("read", String.class); read.setAccessible(true);
            var write = type.getDeclaredMethod("write", String.class, byte[].class); write.setAccessible(true);
            var initial = type.getDeclaredMethod("initialBackup", int.class); initial.setAccessible(true);
            assertThat((byte[]) initial.invoke(storage, 10)).containsExactly((byte) 7, (byte) 8);
            assertThat(read.invoke(storage, "st0")).isNull();
            write.invoke(storage, "st0", new byte[]{1, 2, 3});
            loader.loadClass("javax.microedition.io.Connector").getField("failWrite").setBoolean(null, true);
            assertThatThrownBy(() -> write.invoke(storage, "st0", new byte[]{4, 5})).isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(java.io.IOException.class);
            assertThat((byte[]) read.invoke(storage, "st0")).containsExactly((byte) 1, (byte) 2, (byte) 3);
            loader.loadClass("javax.microedition.rms.RecordStore").getField("failCommit").setBoolean(null, true);
            assertThatThrownBy(() -> write.invoke(storage, "st0", new byte[]{6})).isInstanceOf(InvocationTargetException.class);
            assertThat((byte[]) read.invoke(storage, "st0")).containsExactly((byte) 1, (byte) 2, (byte) 3);
            write.invoke(storage, "st0", new byte[]{9});
            assertThat((byte[]) read.invoke(storage, "st0")).containsExactly((byte) 9);
            write.invoke(storage, "sav", new byte[]{10, 11});
            assertThat((byte[]) initial.invoke(storage, 10)).containsExactly((byte) 10, (byte) 11);
            assertThat(Files.readAllBytes(original)).containsExactly((byte) 7, (byte) 8);
            var reopened = constructor.newInstance(temporary.resolve("game.gba").toUri().toString(), 0x12345678);
            assertThat((byte[]) read.invoke(reopened, "st0")).containsExactly((byte) 9);
            var profile = new CheatList(); profile.add("Example", new CheatCode("02000100:16:0042", CheatCode.RAW)); profile.toggle(0);
            var saveCheats = type.getDeclaredMethod("cheats", CheatList.class); saveCheats.setAccessible(true);
            var loadCheats = type.getDeclaredMethod("cheats"); loadCheats.setAccessible(true);
            saveCheats.invoke(storage, profile);
            var recovered = (CheatList) loadCheats.invoke(reopened);
            assertThat(recovered.name(0)).isEqualTo("Example");
            assertThat(recovered.code(0).source()).isEqualTo("02000100:16:0042");
            assertThat(recovered.enabled(0)).isFalse();
        }
    }
}
