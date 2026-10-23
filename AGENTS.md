# Project conventions

- Develop on Linux with JDK 21 and Maven 3.9.x, without Java preview features.
- Use JUnit 5 for test discovery, lifecycle and parameterization, and AssertJ for
  every host test assertion. Group related register and flag checks with
  `assertSoftly` when all failures should be reported together.
- Keep all Markdown documentation and commit messages in English.
- Preserve the split between the portable emulator core and the modern desktop,
  build and test packages. Do not introduce JUnit, AssertJ or unsupported JDK APIs
  into the exported core or the MIDP adapter.
- Validate code changes with `mvn verify`. When assertions, test dependencies or
  export behavior change, also run `tools/test-with-console.sh` to check the
  exported core. Record actual results and distinguish host validation from
  unverified BlackBerry runtime behavior.
- Use Git history as the primary source review and delivery channel. Do not
  generate HTML source summaries or ZIP archives unless explicitly requested.
- After each completed and verified project update, commit the relevant changes
  and push them to the configured repository. Preserve unrelated work and do not
  rewrite published history. If the remote or write access is missing, finish the
  local work and report the missing destination or access; never claim a push
  succeeded without verifying it.
- Keep generated build output, downloaded dependencies, SDK installations and
  credentials out of Git.
- Prefer small classes with one responsibility and named hardware constants.
  Explain register behavior and timing assumptions beside the relevant code.
- Group core code and tests by feature (`cpu`, `bus`, `timing`, `video`, `audio`,
  `cartridge`, `input`, `serial`). Keep the root for composition and diagnostics. Preserve
  device ports/listeners rather than introducing dependencies on decoder internals.
- Keep guest state on its deterministic emulation owner. Parallel host work must
  receive owned snapshots through bounded queues, propagate failures and apply
  backpressure; do not share live memory/frame/audio buffers across threads.
- Keep the full-graphics/performance roadmap in `docs/BENCHMARKS.md`. Every future
  performance result must identify the ROM hash, scenario, target hardware and
  actual video/audio timing. Never report instruction throughput as game FPS.
- The performance goal is native GBA refresh with no emulator frame skips or
  audio underruns. Validate on the Bold 9900 separately from the Linux host.
- Documentation captures must come from the application. Label headless component
  captures and keep capture tooling reproducible.

## Design principles

- Apply SOLID and GRASP through focused responsibilities, encapsulated invariants
  and narrow device ports. Prefer composition over inheritance. Introduce an
  interface only for a real boundary or variation, not for every class.
- Follow KISS, YAGNI and PoLA: use the simplest clear design for current needs,
  predictable names and explicit ownership. Avoid speculative frameworks,
  configuration and extension points.
- Balance DRY with WET (Write Everything Twice): tolerate a small second example
  while responsibilities are becoming clear; consolidate stable domain knowledge
  once the shared responsibility is demonstrated. Do not merge unrelated code
  merely because it looks similar.
- Preserve SoC, high cohesion and low coupling: group by feature and reason to
  change, separate emulation from presentation/build/reference tooling, and
  isolate third-party internals in adapters.
- Follow CQS: inspection queries do not execute work or silently mutate state;
  name commands as operations even when they report progress. Document any
  hardware-defined side effects of reads explicitly.
- Apply LoD and Tell, Don't Ask: delegate behavior to the state owner through
  its public port instead of reaching through collaborators and rebuilding its
  rules elsewhere. Composition roots and diagnostic adapters may wire devices.
- Fail fast on invalid inputs, unsupported modes and inconsistent state. Keep
  failure messages actionable; do not hide failures behind defaults or retries.
- Use the Boy Scout Rule for small improvements in touched code, with relevant
  verification; avoid unrelated large rewrites while fixing a local defect.
- Apply ADP/SDP/SAP to package dependencies: keep them acyclic, point toward
  stable contracts and make stable boundaries appropriately abstract without
  speculative layers. Apply REP/CCP/CRP by grouping code that is released,
  changes and is consumed together; preserve the core/host/export split.

See `docs/ARCHITECTURE.md` for the project-specific interpretation of these
principles. Treat them as design constraints, not a quota of patterns to add.

## Autonomous roadmap work

- The user authorizes successive roadmap steps, verification, commits and pushes
  without requesting a new "follow-up" after each delivery. Within an active
  session, a completed commit is a checkpoint, not a default stopping condition.
- Read the current evidence and next actions in `docs/BENCHMARKS.md`; implement
  the next eligible small step, run the appropriate checks, update its evidence,
  commit and push, then continue with the next eligible step.
- Report progress in commentary. If a gate needs unavailable hardware or an
  independent expected result, record the specific dependency and continue other
  authorized work that does not depend on it. Do not bypass correctness gates.
- Persist enough state in the roadmap and linked records to resume from the
  actual checkpoint without repeating completed work or asking for known inputs.
- Distinguish this in-session workflow from an external scheduler. Do not claim
  background execution or automatic session restart unless a working mechanism
  has actually been configured and verified.
