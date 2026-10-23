# Autonomous roadmap loop

`tools/roadmap_loop.py` runs successive Codex CLI invocations against the current
roadmap. Each invocation completes one small step, verifies it, records evidence,
commits and pushes. The runner checks the Git delivery before starting another
invocation. No repeated chat message is needed while this process is running.

## Start on a persistent Linux host

Requirements: Python 3, Git, an authenticated Codex CLI, the project's JDK/Maven
toolchain, and Git push access. The CLI protocol was checked against Codex CLI
0.163.0. The working tree must be clean, HEAD must already be published, and the
current branch must have a remote upstream. Install required local test assets
separately; ROMs, BIOS and toolchains are not supplied by this runner.

Keep the existing user identity:

```bash
git config user.name "Piotr Nowak"
git config user.email "piotr_nowak@outlook.com"
python3 tools/roadmap_loop.py --max-steps 20
```

Run this from a terminal that will remain alive, for example inside an existing
tmux session on your development host. Source any required toolchain activation
before starting; child processes inherit the environment. The default limit is
20 invocations with one hour per invocation. Both values must be positive:

```bash
python3 tools/roadmap_loop.py --max-steps 5 --timeout 1800
```

Codex uses `workspace-write` sandboxing and `--ask-for-approval never`. Denied
operations fail instead of requesting interactive approval. The host's Codex
configuration must already permit the build and Git operations required by the
project; this tool does not grant permissions, disable sandboxing or change
network policy. Authentication and API usage follow the user's CLI setup and
may incur charges. The runner does not select a model.

## Checkpoints and stops

Each run writes an ignored `target/roadmap-loop/run-*/` directory containing:

- `step-NNN.log`: CLI event output and errors for each invocation.
- `step-NNN.json`: the agent's structured summary, validation and next action.
- `state.json`: the last verified HEAD, invocation count and current stop reason.

The runner independently checks that the tree is clean, the branch/upstream and
configured identity are unchanged, history descends from the prior checkpoint,
every new commit has the configured author and committer, and the remote branch
at the configured push URL equals HEAD. A `progress` result requires a new
commit. A repository-wide lock prevents overlapping runner instances, including
instances in linked worktrees.

The runner stops on `done`, `blocked`, an invocation limit, timeout, Ctrl-C,
invalid structured output, a CLI error or a failed Git check. Timeout and Ctrl-C
terminate the invocation's process group. There are no automatic retries,
resets, force-pushes or repairs. Exit codes are 0 for done/limit, 2 for blocked,
1 for errors and 130 for Ctrl-C. The reported validation and roadmap completion
remain agent claims; the Git checks do not independently prove test correctness.

After a normal limit, run the same command to continue. Each new invocation
reads the committed roadmap rather than relying on chat memory. After an error,
inspect the logs and working tree, preserve any unfinished work, and resolve the
failure before restarting. The checkpoint's HEAD is the last verified delivery;
a failed invocation may have left a later local or remote commit.

## Verification and scope

`RoadmapLoopTest` uses temporary repositories, local bare remotes and a fake CLI
executable. It covers successive deliveries, stopping/resuming at the budget,
done/blocked results, no progress, malformed output, wrong authors, unpublished
commits, branch changes, rewritten history, overlapping instances, dirty trees,
CLI failures and timeouts. These 16 test cases do
not invoke a model or use the project's actual remote.

The runner is delivered as an explicit command. No background service, chat
resumer, scheduled GitHub workflow or paid model session has been started by
adding it. The current cloud chat has no available scheduling mechanism. A
persistent host with a working, authenticated CLI is needed for execution after
the chat ends; stopping the host stops the loop.
