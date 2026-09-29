#!/usr/bin/env bash
# Run Jupiter on both the Java 21 host classes and the exported core classes.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

source_release=21
build_dir=target/junit-console
main_classes=target/classes
test_classes=target/test-classes
legacy_classes="$build_dir/legacy"
dependencies_file="$PWD/$build_dir/dependencies.txt"
java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$legacy_classes"
find "$legacy_classes" -name '*.class' -delete

# Maven supplies Jupiter, AssertJ and their transitive dependencies, including Byte Buddy.
mvn -B -ntp "$@" test-compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=test -Dmdep.outputFile="$dependencies_file"
dependencies=$(< "$dependencies_file")

"$java_command" -Dberryboy.sourceRelease="$source_release" \
  -cp "$main_classes:$test_classes:$dependencies" org.junit.platform.console.ConsoleLauncher execute \
  --scan-class-path="$test_classes" --include-engine junit-jupiter \
  --fail-if-no-tests --disable-banner --details=summary \
  --reports-dir "$build_dir/reports/host"

"$java_command" -Dberryboy.sourceRelease="$source_release" -cp "$main_classes" berryboy.build.LegacyExport
find target/blackberry/generated -name '*.java' -type f | sort > "$build_dir/legacy-sources.txt"
"$java_command" com.sun.tools.javac.Main --release 8 -encoding UTF-8 \
  -d "$legacy_classes" @"$build_dir/legacy-sources.txt"

# Generated classes precede host classes; Jupiter runs all tests tagged core against them.
"$java_command" -cp "$legacy_classes:$main_classes:$test_classes:$dependencies" \
  org.junit.platform.console.ConsoleLauncher execute \
  --scan-class-path="$test_classes" --include-engine junit-jupiter --include-tag core \
  --fail-if-no-tests --disable-banner --details=summary \
  --reports-dir "$build_dir/reports/legacy"
