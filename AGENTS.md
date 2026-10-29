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
- Desktop client: `scripts/desktop-client/jb-inspect.py` drives the plugin's
  HTTP API for this repository's smokes; agents no longer inspect through the
  plugin. Keep its status/clean/capture contracts in mind when changing HTTP
  inspection behavior, and update it and its tests together.

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
  MCP tool contracts change, update the desktop client and its tests to match.

## Test rules

Follow the shared [Tests rules](https://github.com/cbusillo/codex-skills/blob/main/instructions/global.md#tests).

- Use `qualityGate.test.local` in `.github/github.json` for focused tests of
  changed behavior, plus required workflow/shell lint and IDE inspection.
  Substitute a unit-test task and class/method filter; use the separate focused
  platform-test command documented in `TESTING.md` for platform fixtures. Run
  maintenance-Python or shell fixture modules directly when those helpers change. The complete
  `qualityGate.test.ci` gate runs only in CI and must pass for the current PR
  head before `ready` or merge. See `TESTING.md` for test surfaces.
- For safety, authorization, approval rules, destructive helpers and shared
  infrastructure,
  also run the complete local test modules exercising the changed area and its
  callers. Trace inspection trust/cancellation to plugin/core/MCP tests,
  installation/recovery to smoke-lifecycle and red-lane fixtures, retention to
  IDE-retention fixtures, and release/publication to release-contract and
  agents-release fixtures. Also run `cancellationCatchLint`,
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

## Local-only overrides

If you need machine-specific paths/ports/log locations, copy
`AGENTS.local.template.md` to `AGENTS.local.md`.
`AGENTS.local.md` is ignored in git.
