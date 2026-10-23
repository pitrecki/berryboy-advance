package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("build")
class RoadmapLoopTest {
    private static final Path RUNNER = Path.of(System.getProperty("berryboy.projectRoot", "."))
            .resolve("tools/roadmap_loop.py");

    @TempDir
    Path temporary;
    private Path repo;
    private Path origin;
    private Path fake;
    private int commands;

    @BeforeEach
    void createIsolatedRepositoryAndFakeCodex() throws Exception {
        repo = Files.createDirectory(temporary.resolve("work"));
        origin = temporary.resolve("origin.git");
        checked("git", "init", "--bare", "--initial-branch=main", origin.toString());
        checked("git", "init", "--initial-branch=main", repo.toString());
        git("config", "user.name", "Piotr Nowak");
        git("config", "user.email", "piotr_nowak@outlook.com");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve(".gitignore"), "/target/\n");
        git("add", ".gitignore");
        git("commit", "-m", "Initial checkpoint");
        git("remote", "add", "origin", origin.toString());
        git("push", "--set-upstream", "origin", "main");
        fake = temporary.resolve("fake-codex");
        Files.writeString(fake, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys, time
                args = sys.argv[1:]
                repo = pathlib.Path(args[args.index('--cd') + 1])
                result = pathlib.Path(args[args.index('--output-last-message') + 1])
                schema = pathlib.Path(args[args.index('--output-schema') + 1])
                if (json.loads(schema.read_text())['additionalProperties'] is not False
                        or '--sandbox' not in args or 'workspace-write' not in args
                        or '--ask-for-approval' not in args or 'never' not in args
                        or 'ONE' not in sys.stdin.read()):
                    raise RuntimeError('Invalid runner protocol')
                counter = repo / 'target' / 'calls'
                counter.parent.mkdir(exist_ok=True)
                count = int(counter.read_text()) + 1 if counter.exists() else 1
                counter.write_text(str(count))
                mode = os.environ['BERRYBOY_LOOP_TEST_MODE']
                def git(*arguments):
                    subprocess.run(['git', '-C', str(repo), *arguments], check=True)
                if mode == 'exit':
                    sys.exit(7)
                if mode == 'timeout':
                    time.sleep(30)
                if mode == 'malformed':
                    result.write_text('not json')
                    sys.exit(0)
                if mode == 'schema':
                    result.write_text(json.dumps(dict(status='progress', summary=False)))
                    sys.exit(0)
                status = 'progress'
                if mode == 'rewind':
                    git('reset', '--hard', 'HEAD^')
                elif mode in ('done', 'blocked') or (mode == 'sequence' and count == 3):
                    status = 'blocked' if mode == 'blocked' else 'done'
                elif mode != 'noop':
                    if mode == 'branch':
                        git('switch', '-c', 'unexpected')
                    (repo / 'progress.txt').write_text(str(count))
                    if mode != 'dirty':
                        git('add', 'progress.txt')
                        author = ['--author=Someone Else <other@example.com>'] if mode == 'author' else []
                        git('commit', *author, '-m', 'Complete synthetic step ' + str(count))
                        if mode not in ('unpublished', 'branch'):
                            git('push')
                result.write_text(json.dumps(dict(status=status, summary='Synthetic step',
                                                  next_action='Inspect roadmap', validation='Fake fixture only')))
                """);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void advancesThroughTwoPublishedStepsThenStopsWhenDone() throws Exception {
        var result = loop("sequence", 5);

        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isZero();
            softly.assertThat(result.output()).contains("Step 3/5", "done: Synthetic step")
                    .doesNotContain("Step 4/5");
            softly.assertThat(repo.resolve("target/calls")).hasContent("3");
            softly.assertThat(repo.resolve("progress.txt")).hasContent("2");
        });
        assertThat(git("status", "--porcelain")).isEmpty();
        assertThat(git("rev-parse", "HEAD")).isEqualTo(
                checked("git", "--git-dir", origin.toString(), "rev-parse", "refs/heads/main"));
        assertThat(checkpoint()).contains("\"status\": \"done\"", "\"completed_steps\": 3");
    }

    @Test
    void stopsAtBudgetAndResumesFromTheExistingCheckpoint() throws Exception {
        var first = loop("progress", 2);
        var second = loop("progress", 1);

        assertSoftly(softly -> {
            softly.assertThat(first.exit()).isZero();
            softly.assertThat(first.output()).contains("Step limit reached");
            softly.assertThat(second.exit()).isZero();
            softly.assertThat(repo.resolve("progress.txt")).hasContent("3");
            softly.assertThat(repo.resolve("target/calls")).hasContent("3");
        });
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("4");
    }

    @ParameterizedTest
    @CsvSource({"done, 0", "blocked, 2"})
    void terminalResultsNeverStartAnotherInvocation(String mode, int exit) throws Exception {
        var result = loop(mode, 5);

        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isEqualTo(exit);
            softly.assertThat(repo.resolve("target/calls")).hasContent("1");
            softly.assertThat(result.output()).doesNotContain("Step 2/5");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "noop, without a new commit",
            "exit, Codex exited with 7",
            "malformed, Expecting value",
            "schema, invalid structured result",
            "unpublished, Remote branch does not match HEAD",
            "author, author/committer does not match",
            "dirty, Worktree must be clean",
            "branch, 'identity, branch or upstream changed'",
            "timeout, timed out"
    })
    void failuresStopAndPersistAnErrorWithoutRetrying(String mode, String message) throws Exception {
        var result = loop(mode, 5);

        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isEqualTo(1);
            softly.assertThat(result.output()).contains(message).doesNotContain("Step 2/5");
            softly.assertThat(repo.resolve("target/calls")).hasContent("1");
        });
        assertThat(checkpoint()).contains("\"status\": \"error\"");
    }

    @Test
    void rejectsExistingUncommittedWorkBeforeStartingCodex() throws Exception {
        var uncommitted = Files.writeString(repo.resolve("my-work.txt"), "Preserve me");

        var result = loop("progress", 3);

        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isEqualTo(1);
            softly.assertThat(result.output()).contains("Worktree must be clean");
            softly.assertThat(repo.resolve("target/calls")).doesNotExist();
            softly.assertThat(uncommitted).hasContent("Preserve me");
        });
    }

    @Test
    void rejectsRewrittenHistoryEvenWhenTheWorktreeIsClean() throws Exception {
        Files.writeString(repo.resolve("checkpoint.txt"), "Published second checkpoint");
        git("add", "checkpoint.txt");
        git("commit", "-m", "Second checkpoint");
        git("push");
        var published = git("rev-parse", "HEAD");

        var result = loop("rewind", 5);

        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isEqualTo(1);
            softly.assertThat(result.output()).contains("merge-base failed").doesNotContain("Step 2/5");
            softly.assertThat(repo.resolve("target/calls")).hasContent("1");
        });
        assertThat(checked("git", "--git-dir", origin.toString(), "rev-parse", "refs/heads/main"))
                .isEqualTo(published);
    }

    @Test
    void rejectsAnOverlappingLoopBeforeInvokingCodex() throws Exception {
        var lock = repo.resolve(".git/roadmap-loop.lock");
        var holder = new ProcessBuilder("python3", "-c", """
                import fcntl, pathlib, sys
                with pathlib.Path(sys.argv[1]).open('a') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    print('locked', flush=True)
                    sys.stdin.read()
                """, lock.toString()).start();
        try (var reader = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            assertThat(reader.readLine()).isEqualTo("locked");

            var result = loop("progress", 3);

            assertSoftly(softly -> {
                softly.assertThat(result.exit()).isEqualTo(1);
                softly.assertThat(result.output()).contains("already owns this repository");
                softly.assertThat(repo.resolve("target/calls")).doesNotExist();
            });
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(5, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
        }
    }

    private String checkpoint() throws IOException {
        try (var paths = Files.walk(repo.resolve("target/roadmap-loop"))) {
            var state = paths.filter(path -> path.getFileName().toString().equals("state.json"))
                    .findFirst().orElseThrow();
            return Files.readString(state);
        }
    }

    private Result loop(String mode, int steps) throws Exception {
        var builder = new ProcessBuilder("python3", RUNNER.toString(), "--repo", repo.toString(),
                "--codex", fake.toString(), "--max-steps", Integer.toString(steps),
                "--timeout", mode.equals("timeout") ? "1" : "20");
        builder.environment().put("BERRYBOY_LOOP_TEST_MODE", mode);
        return execute(builder);
    }

    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(arguments));
        return checked(command.toArray(String[]::new));
    }

    private String checked(String... command) throws Exception {
        var result = execute(new ProcessBuilder(command));
        assertThat(result.exit()).as("%s: %s", List.of(command), result.output()).isZero();
        return result.output().strip();
    }

    private Result execute(ProcessBuilder builder) throws Exception {
        var output = temporary.resolve("command-" + commands++ + ".log");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("Fixture command timed out: " + builder.command());
        }
        return new Result(process.exitValue(), Files.readString(output));
    }

    private record Result(int exit, String output) {}
}
