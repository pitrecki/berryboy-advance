package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("build")
class BlackBerryReleaseTest {
    @TempDir Path temporary;
    private Path root, output, sdk, script;
    private static final String INVOKE = "import importlib.util,sys; from pathlib import Path; "
            + "s=importlib.util.spec_from_file_location('release_tool',sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
            + "root=Path(sys.argv[2]); out=Path(sys.argv[3]); sdk=Path(sys.argv[4]); "
            + "m.record_build(root,out,sdk,'ecj',sdk/'ecj.jar') if sys.argv[5]=='record' else print(m.package(root,out,root/'target'/sys.argv[5],'0.8.1',sys.argv[6]=='clean'))";

    @BeforeEach
    void fixture() throws Exception {
        root=temporary.resolve("repo"); output=root.resolve("target/blackberry/output"); sdk=temporary.resolve("sdk");
        script=Path.of(System.getProperty("berryboy.projectRoot", ".")).resolve("tools/blackberry_release.py").toAbsolutePath();
        write(root.resolve("pom.xml"),"<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><version>0.8.1</version></project>");
        for(var name:List.of("tools/blackberry.sh","tools/blackberry_release.py","tools/blackberry-install.txt","src/main/java/berryboy/Demo.java","platform/midp/entry.txt"))write(root.resolve(name),"fixture");
        write(root.resolve(".gitignore"),"/target/\n");
        run("git","init","-q",root.toString()); run("git","-C",root.toString(),"add",".");
        run("git","-C",root.toString(),"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-qm","fixture");
        for(var name:List.of("bin/rapc.jar","lib/net_rim_api.jar","ecj.jar"))write(sdk.resolve(name),"tool");
        Files.createDirectories(output); nativeJar(45);
        cods(List.of("BerryBoyAdvance.cod","BerryBoyAdvance-1.cod"));
        assertThat(invoke("record",false).code()).isZero();
    }

    @Test
    void includesEveryCodAndBuildsConsistentDescriptorsChecksumsAndRepeatableZip() throws Exception {
        assertThat(invoke("one",true).code()).isZero(); assertThat(invoke("two",true).code()).isZero();
        var file=root.resolve("target/one/BerryBoy-Advance-0.8.1-BBOS7.1.zip");
        assertThat(Files.readAllBytes(file)).isEqualTo(Files.readAllBytes(root.resolve("target/two").resolve(file.getFileName())));
        try(var zip=new ZipFile(file.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName).toList()).containsExactlyInAnyOrder("BerryBoyAdvance.cod","BerryBoyAdvance-1.cod","BerryBoyAdvance.jad","BerryBoyAdvance.jar","BerryBoyAdvance.alx","INSTALL.txt","manifest.json","SHA256SUMS");
            var jad=text(zip,"BerryBoyAdvance.jad");
            for(var suffix:List.of("","-1")) {
                var name="BerryBoyAdvance"+suffix+".cod";var bytes=zip.getInputStream(zip.getEntry(name)).readAllBytes();
                assertThat(bytes).startsWith((byte)0xde,(byte)0xc0,(byte)0xff,(byte)0xff);
                assertThat(jad).contains("RIM-COD-URL"+suffix+": "+name,"RIM-COD-Size"+suffix+": "+bytes.length,
                        "RIM-COD-SHA1"+suffix+": "+HexFormat.ofDelimiter(" ").formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)));
                assertThat(text(zip,"BerryBoyAdvance.alx")).contains(name);
            }
            for(var line:text(zip,"SHA256SUMS").lines().toList()) {
                var parts=line.split("  ",2);var bytes=zip.getInputStream(zip.getEntry(parts[1])).readAllBytes();
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(parts[0]);
            }
            assertThat(text(zip,"manifest.json")).contains("\"source_dirty\": false","\"version\": \"0.8.1\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"../escape.cod","BerryBoyAdvance-2.cod","unexpected.txt"})
    void rejectsUnexpectedAndMissingSiblingModules(String member) throws Exception {
        cods(List.of("BerryBoyAdvance.cod",member));assertThat(invoke("record",false).code()).isZero();
        assertThat(invoke("bad",false).code()).isNotZero();assertThat(Files.exists(root.resolve("target/bad/BerryBoy-Advance-0.8.1-BBOS7.1.zip"))).isFalse();
    }

    @Test
    void rejectsArtifactsModifiedAfterSdkBuild() throws Exception {
        Files.write(output.resolve("BerryBoyAdvance.cod"),new byte[]{1,2,3});
        var result=invoke("bad",false);assertThat(result.code()).isNotZero();assertThat(result.text()).contains("artifact changed");
    }
    @Test
    void rejectsChangedInputsAndDirtyPublication() throws Exception {
        write(root.resolve("src/main/java/berryboy/Demo.java"),"changed");
        var result=invoke("bad",false);assertThat(result.code()).isNotZero();assertThat(result.text()).contains("inputs changed");
        assertThat(invoke("record",false).code()).isZero();assertThat(invoke("preview",false).code()).isZero();
        assertThat(invoke("publish",true).text()).contains("clean source build");
    }
    @Test
    void rejectsModernDesktopJarEvenWithUpdatedProvenance() throws Exception {
        nativeJar(65);assertThat(invoke("record",false).code()).isZero();
        var result=invoke("bad",false);assertThat(result.code()).isNotZero();assertThat(result.text()).contains("Non-CLDC class");
    }
    @Test
    void rejectsWrongJadVersionAndExistingReleaseArchive() throws Exception {
        assertThat(invoke("one",true).code()).isZero();assertThat(invoke("one",true).text()).contains("already exists");
        var jad=output.resolve("BerryBoyAdvance.jad");write(jad,Files.readString(jad).replace("0.8.1","0.8.0"));
        assertThat(invoke("record",false).code()).isZero();assertThat(invoke("bad",false).text()).contains("JAD version/JAR size mismatch");
    }
    @Test
    void publicationRejectsSnapshotVersionsEvenWhenTheSourceIsClean() throws Exception {
        var pom=root.resolve("pom.xml");write(pom,Files.readString(pom).replace("0.8.1","0.8.1-SNAPSHOT"));
        run("git","-C",root.toString(),"add","pom.xml");
        run("git","-C",root.toString(),"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-qm","snapshot");
        assertThat(invoke("record",false).code()).isZero();
        assertThat(invoke("preview",false).code()).isZero();
        assertThat(invoke("publish",true).text()).contains("frozen Maven version");
    }
    private void nativeJar(int major) throws Exception {
        try(var zip=new ZipOutputStream(Files.newOutputStream(output.resolve("BerryBoyAdvance.jar")))) {
            zip.putNextEntry(new ZipEntry("berryboy/BlackBerryMidlet.class"));
            zip.write(ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(0xcafebabe).putShort((short)0).putShort((short)major).array()); zip.closeEntry();
        }
        write(output.resolve("BerryBoyAdvance.jad"),"MIDlet-Name: BerryBoy Advance\nMIDlet-Version: 0.8.1\nMIDlet-Jar-URL: BerryBoyAdvance.jar\nMIDlet-Jar-Size: "+Files.size(output.resolve("BerryBoyAdvance.jar"))+"\n");
    }
    private void cods(List<String> names) throws Exception {
        try(var zip=new ZipOutputStream(Files.newOutputStream(output.resolve("BerryBoyAdvance.cod")))) {
            for(var name:names){zip.putNextEntry(new ZipEntry(name));zip.write(new byte[]{(byte)0xde,(byte)0xc0,(byte)0xff,(byte)0xff,1,2,3,4});zip.closeEntry();}
        }
    }
    private Result invoke(String action,boolean clean) throws Exception {return run("python3","-c",INVOKE,script.toString(),root.toString(),output.toString(),sdk.toString(),action,clean?"clean":"preview");}
    private static void write(Path path,String value)throws Exception{Files.createDirectories(path.getParent());Files.writeString(path,value);}
    private static String text(ZipFile zip,String name)throws Exception{return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(),StandardCharsets.UTF_8);}
    private static Result run(String...command)throws Exception{var process=new ProcessBuilder(command).redirectErrorStream(true).start();var output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);return new Result(process.waitFor(),output);}
    private record Result(int code,String text){}
}
