---
name: jetbrains-inspection-workflow
description: >-
  Use for JetBrains Inspection API plugin, MCP server, HTTP API, test, smoke,
  release, or docs work.
metadata:
  short-description: Develop and validate inspection API work
policy:
  allow_implicit_invocation: true
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
5. Check `.github/github.json` when validation gates, workflow names, cleanup
   policy, docs routing, or GitHub signal assumptions matter.

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

1. For narrow docs-only changes where no runtime behavior changed, skip
   inspection and record a one-line not-run reason. Inspect the rendered
   Markdown or affected examples only when examples describe behavior that is
   itself under test; do not run the full suite unless behavior changed.
2. For plugin or inspection-core changes, start with the closest Gradle test
   target, then run broader plugin tests if extraction, filtering, scope, or
   stale-location behavior changed.
3. For MCP server or HTTP API changes, run the matching module tests and check
   that README examples still match request/response behavior.
4. For behavior that depends on a live JetBrains IDE, use the IDE smoke path
   below and open `TESTING_INSTRUCTIONS.md` for the exact manual sequence.
5. For lifecycle, capture, routing, or dogfood-exit readiness, run the dogfood
   smoke matrix (`./scripts/dogfood-smoke-matrix.sh`) when local IDEs are
   available; it exercises preexisting and helper-opened readiness inspection
   paths and records cleanup evidence.
6. For verdict or extraction changes that must prove the red lane, run
   `./scripts/dogfood-red-lane-smoke.sh --product intellij|pycharm|webstorm`
   with the matching local IDE and the current plugin installed; it copies the
   maintained known-bad fixture and requires structured JSON with `RED`,
   `total_problems > 0`, and cleanup `closed`.
   For 2026.2 EAP release gates, pass `--ide-channel eap --ide-version 2026.2`
   and use `--timeout-ms 300000 --prepare-timeout-ms 300000`; this matches the
   plugin wait cap and avoids treating a slow EAP inspection as release evidence.
7. For release readiness, run `./scripts/test-all.sh` or the commit gate before
   build/release commands, and confirm CI status when GitHub state matters.

## When Changing Behavior

1. For HTTP API behavior, read the relevant endpoint docs in `README.md`
   before changing request/response semantics.
2. For inspection result filtering, pagination, severity, file pattern, or
   stale-location behavior, check both plugin extraction logic and MCP server
   parameter handling.
3. For MCP tool behavior, validate the JVM MCP server tests and ensure direct
   HTTP examples still describe the same behavior.
4. For project selection behavior, preserve the MCP auto-routing order:
   `project_key`, `project_path`, MCP process working directory, then unique
   project name. Blank or omitted selectors fall back to the focused/active
   open project only when unambiguous.
5. For route/session behavior, preserve `session_id` drift handling: stale
   sessions must return a fresh-trigger requirement instead of silently using
   old cached state.
6. For clean/capture classification, preserve non-clean outcomes for
   `capture_incomplete`, stale results, timeouts, indexing, session drift,
   route ambiguity, wrong-worktree routes, and cleanup failures.
7. For red-lane smoke tests, prefer `./scripts/dogfood-red-lane-smoke.sh` with
   the relevant `--product` and require current actionable findings in the helper
   response, such as `total_problems > 0`; a paginated current page may have an
   empty `problems` list even when matching findings exist.
   `capture_incomplete`, `non_empty_unmapped_tree`, or a non-clean zero-problem
   response proves only that clean was not confirmed.
8. Agent-facing inspection reports should use the helper verdict: `GREEN` means
   inspection worked and found no actionable findings for the selected
   scope/filter, `RED` means inspection worked and found actionable current
   problems, and `UNKNOWN` means the IDE/plugin/helper did not prove either
   state and the next action must be reported. Prefer the helper's compact
   `agent_result` envelope: verdict, bucket, scope, one-line finding summary
   with file/line when available, retry policy, and next action. Raw diagnostic
   fields such as `capture_diagnostic` belong in helper debugging only. On
   `UNKNOWN`, retry at most once and only when `retry_policy.retry=true`.

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
6. For stateless HTTP clients, resolve `/api/inspection/route` first and pass
   the returned `project_key` and `session_id` through trigger, wait, status,
   and problems calls.
7. When validating agent readiness inspection behavior across IDEs or repos, run
   `./scripts/dogfood-smoke-matrix.sh` and inspect its JSON artifact for
   cleanup `closed`/`not_needed`, IDE identity, plugin version, and failure
   buckets.
8. Product-level IDE selectors such as `WebStorm`, `PyCharm`, and
   `IntelliJ IDEA` should resolve to the latest installed stable/non-EAP app and
   matching config dir. Use exact selectors such as `--ide-channel eap`,
   `--ide-version 2026.2`, or `--ide-app` only when the test intentionally
   targets an EAP or exact IDE version. Never infer EAP from a discovered EAP
   install; EAP must be explicitly requested through repo metadata, CLI flags,
   exact app/version selection, or task text.
9. If lifecycle auto-open stalls with `ide_selection_required`,
   `ide_config_ambiguous`, or `ide_config_missing`, treat that as repo metadata
   work: add preferred IDE metadata to `.github/github.json` or explicitly pass
   the exact IDE for a one-off smoke run.
10. If lifecycle auto-open stalls, inspect trusted-root setup, project-opening
   mode, IDE config layout, settings sync, and plugin installation before
   changing product code.

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
