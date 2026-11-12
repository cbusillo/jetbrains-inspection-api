# AGENTS.md

This repo contains the Inspection API plugin and its bundled MCP server.

Follow the shared [executing loop](https://github.com/cbusillo/codex-skills/blob/main/skills/references/executing-loop.md).
This repository uses a normal merge commit after green CI and any required
other-model review; weigh findings under [reviews by another model](https://github.com/cbusillo/codex-skills/blob/main/skills/references/model-review.md).

User-facing setup, API usage, and release details live in [README.md](README.md).
Testing details live in [TESTING.md](TESTING.md) and manual IDE smoke recipes
live in [TESTING_INSTRUCTIONS.md](TESTING_INSTRUCTIONS.md).
Local plugin rollout and diagnosis guidance lives in [MAINTAINING.md](MAINTAINING.md).

## What Inspection API does

Inspection API lets coding tools ask a JetBrains IDE to inspect code and read its findings through HTTP and MCP.
It must report UNKNOWN when it cannot prove the requested inspection completed; Stable and full builds have different API constraints.
Evidence: [DIRECTION.md](DIRECTION.md) and [README.md](README.md).

## Tooling

- Plugin: Kotlin/Gradle, requires Java 21. Supported IDE builds are configured
  in `build.gradle.kts`.
- MCP server: Kotlin/JVM (bundled in plugin, built via `mcp-server-jvm`).
- Release smoke: `scripts/release-smoke.py` installs a built archive into an
  isolated IDE and compares the plugin's own verdicts with fixed expectations;
  it judges nothing itself. Agents no longer inspect through the plugin.

## Always-on rules

- If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
  JDK 21 path. Repository shell scripts resolve this override; direct
  `./gradlew` calls require `JAVA_HOME="$JAVA_HOME_21"`. An IDE's bundled
  runtime works only if it is Java 21.
- Gradle may need additional sandbox permissions; if you see "Operation not
  permitted" from NativeServices, re-run with the required permissions.
- Prefer descriptive names to comments/docstrings; keep commentary minimal.
- Avoid stale docs: keep this file evergreen and link to README for specifics.

## Test rules

Follow the shared [Tests rules](https://github.com/cbusillo/codex-skills/blob/main/instructions/global.md#tests).

- For `qualityGate.test.local` in `.github/github.json`, substitute a unit-test
  task and class/method filter; use the separate focused
  platform-test command documented in `TESTING.md` for platform fixtures. Run
  maintenance-Python or shell fixture modules directly when those helpers change.
  See `TESTING.md` for test surfaces.
- Safety-module selection includes the changed area and its callers. Trace
  inspection trust/cancellation to plugin/core/MCP tests,
  the release smoke to its driver test, and release/publication to
  release-contract and agents-release fixtures. Also run `cancellationCatchLint`,
  `inspectionBoundaryLint` and `internalApiManifest` for their respective
  changed boundaries, plus `stableBuild` for Stable internal-API/verdict changes,
  using the commands in `.github/github.json`. Preserve required platform
  evidence as missing when it cannot run; green CI does not waive a required local module.
- Before adding or keeping a doubtful test,
  plant the fault it claims to guard against and confirm it fails.
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
