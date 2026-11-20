package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.build.PhoneHarness;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The host platform must not make a closed phone application look like a production memory leak. */
@Tag("build")
class PhoneHostLifecycleTest {
    @TempDir Path temporary;
    @Test
    void terminalNotificationRemovesTheDestroyedApplicationsDisplayRoots() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        assertThat(PhoneHarness.compileUi(root,temporary)).isZero();
        try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},getClass().getClassLoader())) {
            var type = loader.loadClass("berryboy.phone.UiHarness");
            var ui = type.getConstructor().newInstance();
            var app = type.getField("application").get(ui);
            var display = type.getField("display").get(ui);
            var platform = display.getClass();
            assertThat(platform.getMethod("liveDisplays").invoke(null)).isEqualTo(1);
            type.getMethod("close").invoke(ui);
            var destroyed = app.getClass().getField("destroyed");
            long end = System.nanoTime()+2_000_000_000L;
            while (!destroyed.getBoolean(app) && System.nanoTime()<end) Thread.sleep(1);
            assertThat(destroyed.getBoolean(app)).isTrue();
            assertThat(platform.getMethod("liveDisplays").invoke(null)).isEqualTo(0);
            assertThat(platform.getMethod("getCurrent").invoke(display)).isNull();
        }
    }
}
