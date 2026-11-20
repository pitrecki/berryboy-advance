package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.build.PhoneHarness;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("build")
class PhoneReliabilityToolTest {
    @TempDir Path temporary;

    @Test
    void compilesAndCompletesTheFiniteUiExperimentWithIndependentSyntheticCheckpoints() throws Exception {
        var root=Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes=temporary.resolve("classes");assertThat(PhoneHarness.compileUi(root,classes)).isZero();
        var main=root.resolve("target/classes");var compile=new ArrayList<String>();
        compile.addAll(java.util.List.of("--release","21","-cp",classes+java.io.File.pathSeparator+main,"-d",classes.toString()));
        try(var sources=Files.list(root.resolve("tools/phone-reliability"))){sources.filter(p->p.toString().endsWith(".java")).forEach(p->compile.add(p.toString()));}
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))).isZero();
        var rom=DemoRom.create();var bios=new byte[16384];word(bios,0,0xe59ff018);word(bios,0x20,0x08000000);
        var m=new Machine(rom,bios);m.memory.clockedAudio().setCapture(true);m.runCycles(LcdTiming.FRAME_CYCLES);
        var pcm=new short[2048];m.memory.clockedAudio().drain(pcm,m.memory.clockedAudio().availableFrames());
        var p=new Properties();asset(p,"rom",rom);asset(p,"bios",bios);asset(p,"state",MachineState.save(m));asset(p,"frame_input","# neutral fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        p.setProperty("frames","120");p.setProperty("checkpoint_interval","6");p.setProperty("hardware","Synthetic host UI fixture");p.setProperty("oracle",temporary.resolve("oracle").toString());
        var config=temporary.resolve("scene.properties");try(var writer=Files.newBufferedWriter(config)){p.store(writer,"Synthetic tool integration");}
        var manifest=temporary.resolve("manifest.json");Files.writeString(manifest,"{\"scope\":\"test helper compilation\"}\n");
        var cp=classes+java.io.File.pathSeparator+main;
        run(root,cp,manifest,"PrepareReliabilityOracle",config.toString(),temporary.resolve("oracle").toString());
        run(root,cp,manifest,"RunPhoneReliability",config.toString(),temporary.resolve("ui").toString(),"synthetic-test","true");
        var report=Files.readString(temporary.resolve("ui/report.json"));
        assertThat(report).contains("\"frames\":120", "\"writes\":21", "\"checkpoint_loads\":10", "\"all_slots_exercised\":true", "\"controller_recreations\":1", "\"all_rgb_pcm_and_full_state_exact\":true");
        assertThat(Files.readAllBytes(temporary.resolve("ui/states/final.bbs")))
                .isEqualTo(Files.readAllBytes(temporary.resolve("oracle/frame-120.bbs")));
    }
    private void asset(Properties p,String name,byte[] data) throws Exception {
        var file=temporary.resolve(name+".bin");Files.write(file,data);p.setProperty(name,file.toString());
        p.setProperty(name+"_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    }
    private void run(Path root,String cp,Path manifest,String tool,String... args) throws Exception {
        var command=new ArrayList<String>(java.util.List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xms256m","-Xmx512m","-XX:+UseG1GC","-Djava.awt.headless=true","-Dberryboy.reliabilityManifest="+manifest,"-cp",cp,"berryboy.desktop.reliability."+tool));
        command.addAll(java.util.List.of(args));var log=temporary.resolve(tool+".log");var builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var cache=Files.createDirectories(temporary.resolve("font-cache"));builder.environment().put("XDG_CACHE_HOME",cache.toString());
        var process=builder.start();boolean completed=process.waitFor(45,TimeUnit.SECONDS);if(!completed)process.destroyForcibly();
        assertThat(completed).as("%s completed: %s",tool,Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as("%s: %s",tool,Files.readString(log)).isZero();
    }
    private static void word(byte[] b,int at,int value){for(int i=0;i<4;i++)b[at+i]=(byte)(value>>>(i*8));}
}
