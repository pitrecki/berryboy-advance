package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("desktop")
@DisplayName("Desktop command line")
class LaunchCommandTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("validCommands")
    void parsesCommandWithoutRunningTheEmulator(String name, String[] arguments, LaunchCommand expected) {
        assertThat(LaunchCommand.parse(arguments)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCommands")
    void rejectsInvalidArgumentsBeforeOpeningAFile(String name, String[] arguments) {
        assertThatThrownBy(() -> LaunchCommand.parse(arguments))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> validCommands() {
        return Stream.of(
                Arguments.of("default demo", new String[]{}, new LaunchCommand.Demo(false)),
                Arguments.of("demo window", new String[]{"demo"}, new LaunchCommand.Demo(false)),
                Arguments.of("headless demo", new String[]{"demo", "--headless"}, new LaunchCommand.Demo(true)),
                Arguments.of("audio playback", new String[]{"audio-demo"}, new LaunchCommand.AudioDemo(false)),
                Arguments.of("audio WAV only", new String[]{"audio-demo", "--headless"}, new LaunchCommand.AudioDemo(true)),
                Arguments.of("header with spaces in path", new String[]{"header", "my game.gba"},
                        new LaunchCommand.Header(Path.of("my game.gba"))),
                Arguments.of("run with window", new String[]{"run", "game.gba", "200000"},
                        new LaunchCommand.Run(Path.of("game.gba"), 200000, false)),
                Arguments.of("headless run", new String[]{"run", "game.gba", "200000", "--headless"},
                        new LaunchCommand.Run(Path.of("game.gba"), 200000, true)),
                Arguments.of("zero instruction snapshot", new String[]{"run", "game.gba", "0"},
                        new LaunchCommand.Run(Path.of("game.gba"), 0, false)));
    }

    private static Stream<Arguments> invalidCommands() {
        return Stream.of(
                Arguments.of("unknown command", new String[]{"unknown"}),
                Arguments.of("unknown demo option", new String[]{"demo", "--quiet"}),
                Arguments.of("unknown audio option", new String[]{"audio-demo", "--quiet"}),
                Arguments.of("extra audio argument", new String[]{"audio-demo", "--headless", "extra"}),
                Arguments.of("extra demo argument", new String[]{"demo", "--headless", "extra"}),
                Arguments.of("header without a path", new String[]{"header"}),
                Arguments.of("header with extra argument", new String[]{"header", "game.gba", "extra"}),
                Arguments.of("run without arguments", new String[]{"run"}),
                Arguments.of("run without budget", new String[]{"run", "game.gba"}),
                Arguments.of("negative budget", new String[]{"run", "game.gba", "-1"}),
                Arguments.of("non-numeric budget", new String[]{"run", "game.gba", "many"}),
                Arguments.of("budget overflow", new String[]{"run", "game.gba", "2147483648"}),
                Arguments.of("unknown run option", new String[]{"run", "game.gba", "1", "--quiet"}),
                Arguments.of("extra run argument", new String[]{"run", "game.gba", "1", "--headless", "extra"}));
    }
}
