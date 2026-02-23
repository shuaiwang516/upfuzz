# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/org/zlab/upfuzz/` contains core logic. Key areas are `fuzzingengine/` (orchestration), plus system-specific modules: `cassandra/`, `hdfs/`, `hbase/`, and `ozone/`.
- `src/main/resources/` stores runtime resources used by clusters and command daemons.
- `src/main/c/` contains NYX-related native code (`custom_agent`, JNI helpers).
- `src/test/java/` mirrors production packages with JUnit tests.
- Operational and data directories: `bin/` (run/utility scripts), root `*_config.json` files (test configs), `configInfo/` and `configtests/` (config datasets), `prebuild/` (downloaded system binaries), and `docs/` (usage/design notes).

## Build, Test, and Development Commands
```bash
./gradlew clean build
./gradlew test
./gradlew spotlessCheck
./gradlew spotlessApply
./gradlew copyDependencies
./gradlew nyxBuild
bin/start_server.sh config.json
bin/start_clients.sh 1 config.json
```
- `build`: compiles and runs tests.
- `spotlessCheck`/`spotlessApply`: enforce formatting (Java + misc files).
- `copyDependencies`: populates `dependencies/` for runtime.
- `nyxBuild`: builds NYX C artifacts.
- `start_server.sh` and `start_clients.sh`: launch fuzzing runs.

## Coding Style & Naming Conventions
- Java source/target is 11; Gradle currently forks JVM tools from `/usr/lib/jvm/java-17-openjdk-amd64`.
- Use Spotless before committing; Java formatting comes from Eclipse settings in `.settings/org.eclipse.jdt.core.prefs`.
- Prefer 4-space indentation and conventional Java naming: `PascalCase` classes, `camelCase` members, `UPPER_SNAKE_CASE` constants.
- Command classes may intentionally use all-caps names (for example, `ALTER_TABLE_ADD`, `SNAPSHOT`) to reflect shell/CQL operations; keep naming consistent with the surrounding package.

## Testing Guidelines
- Testing uses JUnit 5 (`useJUnitPlatform()`), with some legacy `TestCase` usage.
- Test files should end with `Test.java` and live under matching package paths in `src/test/java/`.
- Run focused suites while iterating, for example: `./gradlew test --tests 'org.zlab.upfuzz.cassandra.*'`.
- No coverage gate is enforced; add regression tests for bug fixes and behavior changes.

## Commit & Pull Request Guidelines
- Recent commits favor short, imperative subjects such as `update config`, `fix assertion`, or `add script`.
- Prefer `<verb> <scope>` messages; use a colon for detail when helpful (example: `update readme: add tmux instruction`).
- PRs should include: purpose, key files changed, validation commands used, relevant config file(s), and linked issue/bug IDs when applicable.
