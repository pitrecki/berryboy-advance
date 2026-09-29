#!/usr/bin/env bash
# Optional JUnit Platform Console route, including the same tests on exported core classes.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

: "${JUNIT_CONSOLE_JAR:?Set JUNIT_CONSOLE_JAR to junit-platform-console-standalone-1.13.4.jar}"
source_release=21
build_dir=target/junit-console
main_classes="$build_dir/main"
test_classes="$build_dir/test"
legacy_classes="$build_dir/legacy"
mkdir -p "$main_classes" "$test_classes" "$legacy_classes"
# Remove stale bytecode from earlier test names while keeping source files untouched.
find "$main_classes" "$test_classes" "$legacy_classes" -name '*.class' -delete

find src/main/java -name '*.java' -type f | sort > "$build_dir/main-sources.txt"
find src/test/java -name '*.java' -type f | sort > "$build_dir/test-sources.txt"
java com.sun.tools.javac.Main --release "$source_release" -encoding UTF-8 \
  -d "$main_classes" @"$build_dir/main-sources.txt"
java com.sun.tools.javac.Main --release "$source_release" -encoding UTF-8 \
  -cp "$main_classes:$JUNIT_CONSOLE_JAR" -d "$test_classes" @"$build_dir/test-sources.txt"

java -Dberryboy.sourceRelease="$source_release" -jar "$JUNIT_CONSOLE_JAR" execute \
  --class-path "$main_classes:$test_classes" --scan-class-path="$test_classes" \
  --include-engine junit-jupiter --fail-if-no-tests --disable-banner --details=summary \
  --reports-dir "$build_dir/reports/host"

java -Dberryboy.sourceRelease="$source_release" -cp "$main_classes" berryboy.build.LegacyExport
find target/blackberry/generated -name '*.java' -type f | sort > "$build_dir/legacy-sources.txt"
java com.sun.tools.javac.Main --release 8 -encoding UTF-8 \
  -d "$legacy_classes" @"$build_dir/legacy-sources.txt"

# Generated classes precede host classes; Jupiter runs all tests tagged core against them.
java -jar "$JUNIT_CONSOLE_JAR" execute \
  --class-path "$legacy_classes:$main_classes:$test_classes" --scan-class-path="$test_classes" \
  --include-engine junit-jupiter --include-tag core --fail-if-no-tests \
  --disable-banner --details=summary --reports-dir "$build_dir/reports/legacy"
