package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.cheat.CheatList;
import berryboy.cheat.CheatCode;
import berryboy.mobile.settings.PhoneOptions;
import berryboy.MachineState;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("build")
class PhoneSessionTest {
    @TempDir Path temporary;
    @Test
    void mutesEveryAccelerationAndRestoresOutputAtNormalSpeedWithoutChangingUserMute() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compile(root, classes, true)).isZero();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var uiType = loader.loadClass("berryboy.phone.PhoneCanvas"); var ui = uiType.getConstructor().newInstance();
            var type = loader.loadClass("berryboy.phone.PhoneSession"); var audioType = loader.loadClass("berryboy.phone.PhoneAudio");
            var ctor = type.getDeclaredConstructor(uiType, PhoneOptions.class); ctor.setAccessible(true);
            var options = new PhoneOptions(); options.setSpeed(2);
            var session = ctor.newInstance(ui, options);
            var request = method(type,"request",int.class,int.class,String.class,String.class);
            try {
                assertThat((boolean)request.invoke(session,1,0,null,null)).isTrue();
                waitFor(() -> number(uiType,ui,"frames") >= 2 || text(uiType,ui,"failure") != null);
                assertThat(uiType.getField("failure").get(ui)).isNull();
                pauseAndAwait(type, session, request, uiType, ui);
                assertThat(number(audioType,null,"appends")).isZero();
                var speeds = new int[]{1, 4, 1, 8, 1, 1};
                for (var i = 0; i < speeds.length; i++) {
                    var speed = speeds[i];
                    options.setSpeed(speed);
                    // The last transition keeps normal speed but tests the independent mute preference.
                    options.setMuted(i == speeds.length - 1);
                    method(type,"options",PhoneOptions.class).invoke(session, options);
                    pauseAndAwait(type, session, request, uiType, ui);
                    var before = number(audioType,null,"appends"); var frames = number(uiType,ui,"frames");
                    method(type,"resume").invoke(session);
                    waitFor(() -> number(uiType,ui,"frames") >= frames + 2 || text(uiType,ui,"failure") != null);
                    pauseAndAwait(type, session, request, uiType, ui);
                    assertThat(uiType.getField("failure").get(ui)).isNull();
                    if (speed == 1 && !options.muted()) assertThat(number(audioType,null,"appends")).isGreaterThan(before);
                    else assertThat(number(audioType,null,"appends")).isEqualTo(before);
                }
                assertThat(number(audioType,null,"resets")).isGreaterThan(0);
            } finally {
                method(type,"close").invoke(session);
                assertThat((boolean)method(type,"awaitClosed",long.class).invoke(session,5000L)).isTrue();
            }
        }
    }
    private static void pauseAndAwait(Class<?> type, Object session, Method request, Class<?> uiType, Object ui) throws Exception {
        var slots = number(uiType,ui,"slots"); waitRequest(request,session,7,0);
        waitFor(() -> number(uiType,ui,"slots") > slots);
        var busy = type.getDeclaredField("busy"); busy.setAccessible(true);
        waitFor(() -> { synchronized(session) { try { return !busy.getBoolean(session); } catch(Exception e) { throw new IllegalStateException(e); } } });
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void presentsBiosFramesOnlyWhenBootAnimationIsEnabled(boolean showBoot) throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compile(root, classes, true)).isZero();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var uiType = loader.loadClass("berryboy.phone.PhoneCanvas"); var ui = uiType.getConstructor().newInstance();
            var type = loader.loadClass("berryboy.phone.PhoneSession");
            var ctor = type.getDeclaredConstructor(uiType, PhoneOptions.class); ctor.setAccessible(true);
            var options = new PhoneOptions(); options.setMuted(true); options.setShowBoot(showBoot);
            var bios = new byte[16384]; var rom = new byte[512];
            // Red BIOS frame, a long CPU delay, then a cartridge that enables forced blank (white).
            word(bios, 0, 0xe59f0078); word(bios, 0x80, 0x04000000);
            word(bios, 4, 0xe3a01b01); word(bios, 8, 0xe2811003); word(bios, 12, 0xe1c010b0);
            word(bios, 0x10, 0xe59f006c); word(bios, 0x84, 0x06000000);
            word(bios, 0x14, 0xe3a0101f); word(bios, 0x18, 0xe1c010b0);
            word(bios, 0x1c, 0xe59f2064); word(bios, 0x88, 100000);
            word(bios, 0x20, 0xe2522001); word(bios, 0x24, 0x1afffffd);
            word(bios, 0x28, 0xe59ff05c); word(bios, 0x8c, 0x08000000);
            word(rom, 0, 0xe59f0018); word(rom, 0x20, 0x04000000);
            word(rom, 4, 0xe3a01080); word(rom, 8, 0xe1c010b0); word(rom, 12, 0xeafffffe);
            var romFile = temporary.resolve("boot.gba"); var biosFile = temporary.resolve("boot.bin");
            Files.write(romFile, rom); Files.write(biosFile, bios);
            var session = ctor.newInstance(ui, options);
            try {
                assertThat((boolean) method(type,"request",int.class,int.class,String.class,String.class)
                        .invoke(session,2,0,romFile.toUri().toString(),biosFile.toUri().toString())).isTrue();
                waitFor(() -> number(uiType,ui,"frames") > 0 || text(uiType,ui,"failure") != null);
                assertThat(uiType.getField("failure").get(ui)).isNull();
                assertThat(number(uiType,ui,"firstFrameSample")).isEqualTo(showBoot ? 0xff0000 : 0xffffff);
            } finally {
                method(type,"close").invoke(session);
                assertThat((boolean) method(type,"awaitClosed",long.class).invoke(session,5000L)).isTrue();
            }
        }
    }
    @Test
    void runsNativeSessionCommandsOnOneOwnerAndRestoresSavedProgress() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compile(root, classes, true)).isZero();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var uiType = loader.loadClass("berryboy.phone.PhoneCanvas"); var ui = uiType.getConstructor().newInstance();
            var type = loader.loadClass("berryboy.phone.PhoneSession");
            var ctor = type.getDeclaredConstructor(uiType, PhoneOptions.class); ctor.setAccessible(true);
            var options = new PhoneOptions(); options.setMuted(true);
            var session = ctor.newInstance(ui, options);
            var request = method(type,"request",int.class,int.class,String.class,String.class);
            var pause = method(type,"pause"); var resume = method(type,"resume"); var close = method(type,"close");
            var awaitClosed = method(type,"awaitClosed",long.class);
            try {
                var rom = DemoRom.create(); var bios = new byte[16384];
                var marker = "EEPROM_V".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                System.arraycopy(marker, 0, rom, 0x180, marker.length);
                word(bios,0,0xe59ff018); word(bios,0x20,0x08000000); // Real CPU reset vector jumps into the original demo.
                var romFile = temporary.resolve("demo.gba"); var biosFile = temporary.resolve("bios.bin");
                Files.write(romFile,rom); Files.write(biosFile,bios);
                var originalSave = new byte[512]; originalSave[0] = 0x42;
                Files.write(temporary.resolve("demo.sav"), originalSave);
                assertThat((boolean) request.invoke(session,2,0,romFile.toUri().toString(),biosFile.toUri().toString())).isTrue();
                waitFor(() -> number(uiType,ui,"frames")>=2);
                pause.invoke(session);
                waitRequest(request,session,7,0); waitFor(() -> number(uiType,ui,"slots")>=1);
                var oldProfile = new CheatList(); oldProfile.add("Old game", new CheatCode("02000100:16:1234", CheatCode.RAW));
                method(type,"cheats",CheatList.class,int.class).invoke(session,oldProfile,0);
                assertThat((boolean) method(type,"requestForGame",int.class,int.class,int.class).invoke(session,3,0,0)).isFalse();
                method(type,"foreground",boolean.class).invoke(session,false);
                var stoppedAt = number(uiType,ui,"frames"); resume.invoke(session);
                Thread.sleep(50);
                assertThat(number(uiType,ui,"frames")).isEqualTo(stoppedAt);
                method(type,"foreground",boolean.class).invoke(session,true);
                waitRequest(request,session,3,0); waitFor(() -> text(uiType,ui,"message").startsWith("Saved slot"));
                var storageField=type.getDeclaredField("storage");storageField.setAccessible(true);var storage=storageField.get(session);
                var read=method(storage.getClass(),"read",String.class);
                assertThat(((CheatList)method(storage.getClass(),"cheats").invoke(storage)).size()).isZero();
                var saved=(byte[])read.invoke(storage,"st0");var expected=MachineState.load(rom,bios,saved);
                options.setSpeed(4); method(type,"options",PhoneOptions.class).invoke(session,options);
                var frames=number(uiType,ui,"frames");resume.invoke(session);waitFor(() -> number(uiType,ui,"frames")>=frames+2);
                pause.invoke(session);waitRequest(request,session,4,0);waitFor(() -> text(uiType,ui,"message").startsWith("Loaded slot"));
                var machineField=type.getDeclaredField("machine");machineField.setAccessible(true);var restored=(Machine)machineField.get(session);
                assertThat(restored.clock.now()).isEqualTo(expected.clock.now());
                var settingsField=type.getDeclaredField("options");settingsField.setAccessible(true);
                assertThat(((PhoneOptions)settingsField.get(session)).speed()).isEqualTo(4);
                waitRequest(request,session,5,0);waitFor(() -> text(uiType,ui,"message").equals("Game restarted"));
                assertThat(((Machine)machineField.get(session)).clock.now()).isZero();
                assertThat(((Machine)machineField.get(session)).memory.backup().type()).isEqualTo(berryboy.cartridge.CartridgeBackup.EEPROM_512);
                assertThat(Files.readAllBytes(temporary.resolve("demo.sav"))).isEqualTo(originalSave);
                assertThat(uiType.getField("failure").get(ui)).isNull();
            } finally {
                close.invoke(session); assertThat((boolean)awaitClosed.invoke(session,5000L)).isTrue();
            }
            waitFor(() -> flag(uiType,ui,"closed"));
        }
    }
    private static Method method(Class<?> c,String name,Class<?>...args) throws Exception {
        var m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    private static int number(Class<?> c,Object o,String f){try{return c.getField(f).getInt(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private static boolean flag(Class<?> c,Object o,String f){try{return c.getField(f).getBoolean(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String text(Class<?> c,Object o,String f){try{return (String)c.getField(f).get(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void waitFor(BooleanSupplier condition) throws Exception {
        var end=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(5);
        assertThat(condition.getAsBoolean()).as("native session completed the requested operation").isTrue();
    }
    private static void waitRequest(Method request,Object session,int operation,int slot) throws Exception {
        var accepted=false;var end=System.nanoTime()+5_000_000_000L;
        while(!accepted && System.nanoTime()<end){accepted=(boolean)request.invoke(session,operation,slot,null,null);if(!accepted)Thread.sleep(5);}
        assertThat(accepted).isTrue();
    }
    private static void word(byte[] bytes,int at,int value){for(var i=0;i<4;i++)bytes[at+i]=(byte)(value>>>(i*8));}
}
