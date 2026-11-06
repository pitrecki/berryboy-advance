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

@Tag("build")
class PhoneSessionTest {
    @TempDir Path temporary;
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
                var frames=number(uiType,ui,"frames");resume.invoke(session);waitFor(() -> number(uiType,ui,"frames")>=frames+2);
                pause.invoke(session);waitRequest(request,session,4,0);waitFor(() -> text(uiType,ui,"message").startsWith("Loaded slot"));
                var machineField=type.getDeclaredField("machine");machineField.setAccessible(true);var restored=(Machine)machineField.get(session);
                assertThat(restored.clock.now()).isEqualTo(expected.clock.now());
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
