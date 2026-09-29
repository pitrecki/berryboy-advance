package berryboy.desktop;

import java.nio.file.Path;

/** Validated desktop commands; parsing has no file or emulator side effects. */
sealed interface LaunchCommand {
    String DEMO_USAGE = "demo [--headless]";
    String HEADER_USAGE = "header <file.gba>";
    String RUN_USAGE = "run <file.gba> <instructions> [--headless]";

    record Demo(boolean headless) implements LaunchCommand {
    }

    record Header(Path rom) implements LaunchCommand {
    }

    record Run(Path rom, int instructions, boolean headless) implements LaunchCommand {
        public Run {
            if (instructions < 0) {
                throw new IllegalArgumentException("Instruction budget must be non-negative. Usage: " + RUN_USAGE);
            }
        }
    }

    static LaunchCommand parse(String... arguments) {
        if (arguments.length == 0) {
            return new Demo(false);
        }
        return switch (arguments[0]) {
            case "demo" -> new Demo(headlessOption(arguments, 1, DEMO_USAGE));
            case "header" -> parseHeader(arguments);
            case "run" -> parseRun(arguments);
            default -> throw new IllegalArgumentException("Usage: " + DEMO_USAGE + " | " + HEADER_USAGE + " | " + RUN_USAGE);
        };
    }

    private static Header parseHeader(String[] arguments) {
        require(arguments.length == 2, HEADER_USAGE);
        return new Header(Path.of(arguments[1]));
    }

    private static Run parseRun(String[] arguments) {
        require(arguments.length >= 3, RUN_USAGE);
        var headless = headlessOption(arguments, 3, RUN_USAGE);
        try {
            return new Run(Path.of(arguments[1]), Integer.parseInt(arguments[2]), headless);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Instruction budget must be a 32-bit integer. Usage: " + RUN_USAGE, error);
        }
    }

    private static boolean headlessOption(String[] arguments, int requiredArguments, String usage) {
        if (arguments.length == requiredArguments) {
            return false;
        }
        require(arguments.length == requiredArguments + 1 && arguments[requiredArguments].equals("--headless"), usage);
        return true;
    }

    private static void require(boolean condition, String usage) {
        if (!condition) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }
}
