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
- Refresh `BerryBoy-Source.html` with `python3 tools/build_source_view.py` when
  delivering source changes, so the phone-friendly viewer matches the code.
- After each completed and verified project update, commit the relevant changes
  and push them to the configured repository. Preserve unrelated work and do not
  rewrite published history. If the remote or write access is missing, finish the
  local work and report the missing destination or access; never claim a push
  succeeded without verifying it.
- Keep generated build output, downloaded dependencies, SDK installations and
  credentials out of Git.
