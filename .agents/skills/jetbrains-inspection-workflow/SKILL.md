---
name: jetbrains-inspection-workflow
description: >-
  Use for JetBrains Inspection API plugin, MCP server, HTTP API, test, smoke,
  release, or docs work.
---

# JetBrains Inspection Workflow

Use this skill for development and validation work in the JetBrains Inspection
API repo. Keep factual API/setup details in `README.md`, testing details in
`TESTING.md` and `TESTING_INSTRUCTIONS.md`, and use this skill as the task
workflow router.

## First Checks

1. Run `git status --short` before edits and preserve unrelated changes.
2. Check whether the task touches plugin code, `inspection-core`,
   `mcp-server-jvm`, scripts, docs, or release metadata.
3. Use Java 21 for Gradle commands. On macOS prefer
   `JAVA_HOME=$(/usr/libexec/java_home -v 21)`; if that fails, check
   `JAVA_HOME_21`.
4. If Gradle reports NativeServices or sandbox permission errors in Every Code,
   retry the same command with the required permission path rather than changing
   project files.

## Common Commands

- Build plugin zip: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
- Full validation: `./scripts/test-all.sh`
- Plugin tests: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
- Core tests: `./gradlew :inspection-core:test`
- MCP server tests: `./gradlew :mcp-server-jvm:test`
- MCP server jar: `./gradlew :mcp-server-jvm:mcpServerJar`
- Commit gate: `./scripts/commit-gate.sh`

## Testing Workflow

Use focused validation first, then broaden based on risk and user-facing impact.
Keep exact command matrices, environment setup, and troubleshooting in
`TESTING.md`; keep manual IDE endpoint recipes in `TESTING_INSTRUCTIONS.md`.

1. For narrow docs-only changes, inspect the rendered Markdown or affected
   examples when useful; do not run the full suite unless behavior changed.
2. For plugin or inspection-core changes, start with the closest Gradle test
   target, then run broader plugin tests if extraction, filtering, scope, or
   stale-location behavior changed.
3. For MCP server or HTTP API changes, run the matching module tests and check
   that README examples still match request/response behavior.
4. For behavior that depends on a live JetBrains IDE, use the IDE smoke path
   below and open `TESTING_INSTRUCTIONS.md` for the exact manual sequence.
5. For release readiness, run `./scripts/test-all.sh` or the commit gate before
   build/release commands, and confirm CI status when GitHub state matters.

## When Changing Behavior

1. For HTTP API behavior, read the relevant endpoint docs in `README.md`
   before changing request/response semantics.
2. For inspection result filtering, pagination, severity, file pattern, or
   stale-location behavior, check both plugin extraction logic and MCP server
   parameter handling.
3. For MCP tool behavior, validate the JVM MCP server tests and ensure direct
   HTTP examples still describe the same behavior.
4. For project selection behavior, preserve the rule that blank or omitted
   `project` uses the focused/active project and nonblank values must match an
   open project.

## IDE Smoke Testing

Use smoke tests when behavior depends on a live JetBrains IDE rather than only
unit tests.

1. Read `AGENTS.local.template.md` if local IDE paths, ports, or test project
   settings are needed.
2. Read `TESTING_INSTRUCTIONS.md` for manual endpoint smoke tests.
3. Confirm the IDE built-in server allows external connections and unsigned
   requests.
4. Exercise status, trigger or wait, then problems retrieval for the relevant
   scope.
5. Prefer targeted scopes such as `current_file`, `files`, `directory`, or
   `changed_files` when full-project inspection is unnecessary.

## Release Work

For release preparation, read the `README.md` Releases section for publishing
details and use this checklist:

1. Confirm the working tree is clean before release scripts, because release
   automation expects that.
2. Bump `gradle.properties` (`pluginVersion`) if the release script is not doing
   it for the task.
3. Run `./scripts/test-all.sh` or `./scripts/commit-gate.sh`.
4. Build the plugin zip with
   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`.
5. Prefer `./scripts/release.sh --patch|--minor|--major` when it fits the task.

Tag pushes run `.github/workflows/release.yml`, which runs the CI commit gate,
builds the plugin zip, publishes to JetBrains Marketplace, and uploads the zip
from `build/distributions/` to the GitHub Release.

## Documentation Split

- Keep `AGENTS.md` as short always-on guidance.
- Keep user-facing setup, API details, and examples in `README.md`.
- Keep test surface and command details in `TESTING.md`.
- Keep manual smoke-test recipes in `TESTING_INSTRUCTIONS.md`.
- Put only conditional agent workflow guidance in this skill.
