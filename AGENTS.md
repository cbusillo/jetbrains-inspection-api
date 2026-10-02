# AGENTS.md

This repo contains the Inspection API plugin and its bundled MCP server.

User-facing setup, API usage, and release details live in [README.md](README.md).
Testing details live in [TESTING.md](TESTING.md) and manual IDE smoke recipes
live in [TESTING_INSTRUCTIONS.md](TESTING_INSTRUCTIONS.md).
Use `.github/github.json` for non-secret repo workflow facts,
validation commands, GitHub signal availability, docs routing, important
workflows, and cleanup policy.

## Tooling

- Plugin: Kotlin/Gradle, requires Java 21. Supported IDE builds are configured
  in `build.gradle.kts` (currently 251–262.*).
- MCP server: Kotlin/JVM (bundled in plugin, built via `mcp-server-jvm`).
- Agent inspection helper: the external `jetbrains-inspection` skill's
  `scripts/jb-inspect.py` is the primary LLM-facing path for this plugin; keep
  its status/clean/capture contracts in mind when changing HTTP inspection
  behavior.

## Always-on rules

- If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
  JDK 21 path. Repository shell scripts resolve this override; direct
  `./gradlew` calls require `JAVA_HOME="$JAVA_HOME_21"`. An IDE's bundled
  runtime works only if it is Java 21.
- Gradle may need additional sandbox permissions; if you see "Operation not
  permitted" from NativeServices, re-run with the required permissions.
- Prefer descriptive names to comments/docstrings; keep commentary minimal.
- Avoid stale docs: keep this file evergreen and link to README for specifics.
- If API status semantics, route metadata, clean/capture classification, or
  MCP tool contracts change, check whether the installed or checked-out
  `jetbrains-inspection` skill docs/tests/scripts need a matching update.

## Test rules

- A test stays only if it fails when the product is broken and passes when
  someone makes an intended change. Before adding or keeping a doubtful test,
  plant the fault it claims to guard against and confirm it fails.
- No test may assert a literal defined elsewhere (versions, build numbers,
  toolchain, counts, hashes); assert agreement with the single source of truth
  or nothing.
- No test may assert workflow, config, script, or documentation text. Enforce
  the rule where it executes: the workflow itself, a helper script with its own
  behavioural test, or `actionlint`.
- Verification and loading code must not depend on working-tree or machine
  state (git config, environment variables, absolute paths, installed tools);
  check live state only on the path that acts on it.
- A test must reach the behaviour it names and assert the response. "Something
  was written" and `verify(exactly = 0)` on a method production never calls
  are not assertions.
- Do not add production code, overloads, or wait-skipping seams that exist only
  for tests. Injecting a time source is fine when the waiting logic still
  runs against it; setting a wait to zero is not.

## Local-only overrides

If you need machine-specific paths/ports/log locations, copy
`AGENTS.local.template.md` to `AGENTS.local.md`.
`AGENTS.local.md` is ignored in git.
