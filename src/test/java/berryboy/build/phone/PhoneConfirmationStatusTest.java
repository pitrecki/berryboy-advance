package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.build.PhoneHarness;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fast worker completion must remain visible after the confirmation callback returns. */
@Tag("build")
class PhoneConfirmationStatusTest {
    @TempDir Path temporary;
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void completionCannotBeOverwrittenByWorkingStatus(boolean overwrite) throws Exception {
        var root=Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        assertThat(PhoneHarness.compileUi(root,temporary,root.resolve("src/test/fixtures/phone-confirm/berryboy/phone/PhoneSession.java"))).isZero();
        try(var loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},getClass().getClassLoader())) {
            var type=loader.loadClass("berryboy.phone.UiHarness");
            try(var ui=(AutoCloseable)type.getConstructor().newInstance()) {
                var select=type.getMethod("select",String.class);var command=type.getMethod("command",String.class);
                select.invoke(ui,"Open game");command.invoke(ui,"Start game");command.invoke(ui,"Pause / Menu");
                if(overwrite){select.invoke(ui,"Save state");select.invoke(ui,"Slot 1");}
                else select.invoke(ui,"Restart game");
                command.invoke(ui,"Confirm");
                var expected=overwrite?"Saved slot 1":"Game restarted";
                var screen=type.getMethod("screen");String actual="";long end=System.nanoTime()+2_000_000_000L;
                while(System.nanoTime()<end){actual=((berryboy.mobile.ui.PhoneScreen)screen.invoke(ui)).message();if(actual.equals(expected))break;Thread.sleep(1);}
                assertThat(actual).as("completed command retains its final visible status").isEqualTo(expected);
            }
        }
    }
}
