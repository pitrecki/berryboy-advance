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
