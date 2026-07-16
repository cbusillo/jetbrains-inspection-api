# Testing

This project has three test surfaces:

- **Core (Kotlin/JVM)**: shared filtering and JSON helpers (no IDE dependencies).
- **Plugin (Kotlin/Gradle)**: HTTP handler and inspection extraction/trigger logic.
- **MCP server (JVM)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 (required for Gradle builds)

If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
JDK 21 path before running the scripts.

In the Every Code sandbox, Gradle may need escalated permissions. If you see
"Operation not permitted" from NativeServices, re-run the command with
escalation.

## Local commands

```bash
# Plugin tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Core tests
./gradlew :inspection-core:test

# MCP server tests
./gradlew :mcp-server-jvm:test

# Focused MCP auto-routing coverage lives in McpServerTest and covers
# inspection_list_projects, project_path routing, and duplicate-name ambiguity.

# MCP server jar
./gradlew :mcp-server-jvm:mcpServerJar

# Everything (plugin tests + MCP tests + plugin build)
./scripts/test-all.sh
```

## Automated IDE smoke test

`./scripts/test-automated.sh` can install the plugin into a local IDE,
start it with a test project, and hit a few API endpoints.

- Configure your machine in `AGENTS.local.md` (copy from `AGENTS.local.template.md`).

## Agent inspection helper

The external `jetbrains-inspection` skill uses `scripts/jb-inspect.py` as the
primary agent-facing wrapper around this plugin's HTTP API. Run it from the
installed or checked-out skill when validating behavior the agents rely on:

```bash
HELPER="${CODE_HOME:-${CODEX_HOME:-$HOME/.code}}/skills/jetbrains-inspection/scripts/jb-inspect.py"
uv run "$HELPER" inspect \
  --repo "$PWD" \
  --scope changed_files
```

For agent readiness, use `inspect-closeout` so cleanup status is explicit:

```bash
uv run "$HELPER" inspect-closeout \
  --repo "$PWD" \
  --scope changed_files
```

`inspect-closeout` serializes helper-owned IDE opens, requires an exact
current-worktree route, runs inspection, and calls the plugin lifecycle close
endpoint only for projects opened by the helper. Projects that were open before
the helper started are left open. On macOS, lifecycle opens use `open -g` by
default so the IDE should not take focus while readiness inspection is preparing
a worktree. Auto-open requires a global trusted-root policy in
`${CODE_HOME:-${CODEX_HOME:-$HOME/.code}}/jetbrains-inspection.json`; test
worktrees should be created under those roots, not random temp directories.

Before it starts lifecycle auto-open, the helper adds the matching trusted root
to the selected JetBrains product's Trusted Locations and sets project opening
to the separate-window mode. If auto-open stalls, treat it as a blocker: check
for an unsupported IDE config layout, settings sync overwriting the config, or a
missing inspection plugin. The
plugin-side lifecycle open endpoint schedules project opening asynchronously and
uses the worktree directory name as the frame project name so cloned worktrees
with identical checked-in `.idea` metadata can coexist in IntelliJ IDEA,
PyCharm, and WebStorm. Current builds advertise the `lease_bound_v1` ownership
protocol: the helper persists a lease before open, passes that `lease_id`, and
accepts cleanup authority only from a later claim with
`ownership_proven=true`. Scheduling, path/session matching, or a close-token
field without that proof is not sufficient to close a project. Regression tests
must cover a user project appearing between open acceptance and IDE-thread
execution, and must prove that such a project receives no close token.

The helper treats `capture_incomplete`, stale results, timeouts, indexing,
session drift, route ambiguity, wrong-worktree routes, and cleanup failures as
non-clean outcomes. Cached stale findings are returned only when the helper is
run with `--include-stale` for explicit diagnostics. `capture_incomplete`
responses expose `capture_incomplete_reason` plus `capture_diagnostic`; use the
reason bucket for triage and keep the diagnostic payload for helper debugging.
Agent-facing reports from plugin endpoint responses should use
`inspection_verdict`, `inspection_verdict_reason`, `inspection_verdict_message`,
and `inspection_verdict_next_action`. `GREEN` means the inspection worked and
found no actionable findings for the selected scope/filter, `RED` means the
inspection worked and returned actionable findings, and `UNKNOWN` means the
tooling did not prove either state. The external `jb-inspect.py` helper may wrap
these fields in its own compact `agent_result` envelope with retry policy for
agent workflows.
Use `uv run "$HELPER" summarize-outcomes` to view a helper-side rollup of
`outcomes.jsonl` by verdict, bucket, retry, IDE channel, and cleanup status
without running another inspection.
When this repo changes
inspection status semantics, route metadata, clean/capture classification,
lifecycle cleanup contracts, or MCP tool response contracts, update the skill
docs/tests/scripts in the `jetbrains-inspection` skill as part of the same
workstream.

### Red lane dogfood

Use `./scripts/dogfood-red-lane-smoke.sh` when changing verdict semantics,
capture behavior, extraction, helper readiness inspection, or release readiness.
It copies a maintained known-bad project fixture to a disposable local project,
runs the external `jb-inspect.py inspect-closeout`, and requires `VERDICT=RED`,
`total_problems > 0`, and cleanup `closed`. The helper may exit non-zero because
`RED` is not readiness-clean; the smoke trusts the structured JSON verdict and
still fails on invalid JSON or missing cleanup.

The report prints and stores `open_attempt_count`, `open_methods`,
verdict/error reason, and plugin identity so first-attempt reliability is
visible. A warm IDE red-lane run should normally be
`attempts=1 methods=running_ide`; cold IDE runs may include bootstrap attempts,
but must still end in a trustworthy RED with cleanup `closed`.

```bash
./scripts/dogfood-red-lane-smoke.sh \
  --product intellij \
  --ide "IntelliJ IDEA" \
  --ide-app "IntelliJ IDEA" \
  --json-out tmp/dogfood-red-lane.json

./scripts/dogfood-red-lane-smoke.sh \
  --product pycharm \
  --ide "PyCharm" \
  --ide-app "PyCharm" \
  --json-out tmp/dogfood-red-lane-pycharm.json

./scripts/dogfood-red-lane-smoke.sh \
  --product webstorm \
  --ide "WebStorm" \
  --ide-app "WebStorm 2026.2 EAP" \
  --json-out tmp/dogfood-red-lane-webstorm.json
```

Use `--ide` for the inspection identity selector and `--ide-app` for the exact
macOS app bundle to launch. This matters for EAP installs such as
`WebStorm 2026.2 EAP`, where the app bundle name is more specific than the
product selector.

This is a live IDE smoke, not a normal CI unit test. `./scripts/test-all.sh`
runs `./scripts/test-red-lane-smoke-script.sh`, which stubs the helper and
checks the IntelliJ, PyCharm, and WebStorm fixture contracts without requiring a
GUI IDE.

Before shipping changes to clean/capture classification, run the focused
`InspectionSnapshotStateTest` coverage, then build the plugin with
`./gradlew buildPlugin`. Smoke the installed plugin from the agent helper in the
JetBrains IDEs that matter for the change, such as PyCharm, WebStorm, and
IntelliJ IDEA. If plugin installation prompts about replacing an existing jar,
handle that explicitly and rerun the smoke; the prompt alone is not install
validation.

## Dogfood smoke matrix

Use `./scripts/dogfood-smoke-matrix.sh` before release, after lifecycle or
capture behavior changes, and when closing a dogfood session that should prove
agent readiness inspection behavior. The matrix wraps
`jb-inspect.py inspect-closeout`, includes this repo by default, includes
`~/Developer/mediaforce` when present, and runs both preexisting-project and
helper-opened worktree cases.

```bash
./scripts/dogfood-smoke-matrix.sh \
  --json-out tmp/dogfood-smoke-matrix.json
```

The preexisting case uses `--no-open` and expects cleanup `not_needed`; if the
project is not already open it is reported as a skipped preexisting row. The
helper-opened case creates a disposable linked worktree under
`~/.code/working/jetbrains-inspection-api/dogfood-smoke`, expects
`opened_by_helper=true`, and expects cleanup `closed`. Each row records the IDE
identity, plugin version, cleanup status, result bucket, helper `agent_result`
bucket/retry decision, and the issue bucket to check for failures such as
`capture_incomplete`, opaque helper errors, or lifecycle cleanup regressions.

For a smaller targeted pass, restrict the matrix explicitly:

```bash
./scripts/dogfood-smoke-matrix.sh \
  --ide "IntelliJ IDEA" \
  --case helper-opened \
  --repo plugin="$PWD"
```

## Local cleanup

`./scripts/clean-local.sh` removes disposable local files such as `.DS_Store`
files and old `tmp/*.log` test logs without deleting local configuration, agent
state, Gradle caches, IDE sandboxes, or build output.

## CI

GitHub Actions runs workflow lint, shell lint, and the commit gate on pull
requests and pushes to `main` via `.github/workflows/ci.yml`:

- `reviewdog/action-actionlint` on workflow files, using PR annotations for pull
  requests and a GitHub check reporter for pushes
- `shellcheck --severity=warning scripts/*.sh`
- `./scripts/commit-gate.sh --ci`

The commit gate sync-checks `plugin.xml` against `gradle.properties`, runs the
fast release/workflow contract tests, requires Java 21, then runs plugin tests,
core tests, MCP server tests, and `buildPlugin`.
Code scanning is tracked through the required `Analyze (actions)` and
`Analyze (python)` checks alongside `commit-gate`. `Analyze (java-kotlin)` also
runs as a non-required signal so Kotlin and plugin upgrades can be validated
before that check is considered stable enough to require.

Version tags (`v*`) run `.github/workflows/release.yml`, which rejects tag,
`pluginVersion`, or `plugin.xml` mismatches before publication, repeats the
commit gate, runs `buildPlugin`, `verifyPluginStructure`, and `verifyPlugin`,
creates the GitHub Release, then publishes to JetBrains Marketplace. The
workflow also rejects tags that do not point at the current default-branch
commit.

`./scripts/test-all.sh` reports the configured JaCoCo contracts accurately:
plugin coverage is a 0% minimum report-only signal because IntelliJ classloader
isolation prevents reliable plugin instrumentation, while `inspection-core`
and `mcp-server-jvm` each enforce 85% minimum coverage.

Release preparation is a two-phase protected-branch flow:

1. `./scripts/release.sh --patch|--minor|--major` creates and validates a
   `release/vX.Y.Z` branch, pushes it, and opens a PR into the default branch.
2. After that PR merges, update local `main` and run
   `./scripts/release.sh tag vX.Y.Z` to validate and push the release tag.

## 2026.2 compatibility release gates

Before publishing a compatibility-range change for JetBrains 2026.2, capture
evidence for:

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyPluginStructure`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyPlugin`
- IntelliJ IDEA, PyCharm, and WebStorm red-lane dogfood smokes with exact
  2026.2 EAP selectors and `--timeout-ms 300000 --prepare-timeout-ms 300000`.
- The exec-harness worktree scenario in
  `test-fixtures/exec-harness/jetbrains-inspection-262-worktree-live.json`, with
  `JETBRAINS_INSPECTION_API_REPO` pointing at this checkout,
  `CODE_EXEC_HARNESS_ROOT` pointing at the checkout that contains
  `tools/code-exec-harness`, and `JETBRAINS_INSPECTION_IDE_CONFIG_DIR` pointing
  at the installed IntelliJ IDEA 2026.2 config directory.

The `/api/inspection/wait` endpoint caps a single wait request at 300 seconds;
large-project release smokes should prefer helper closeout JSON and rerun with a
fresh route rather than treating one long wait timeout as a clean result. When
the timed-out response still reports `inspection_in_progress`, exercise
`/api/inspection/cancel` with that response's `inspection_run_id`, verify
`/status` reaches an idle state, and confirm a concurrent `/identity` request
remains responsive while lifecycle close runs. A `run_changed` response must
leave the newer run untouched and defer helper-owned project cleanup.

For normal dogfood against the latest installed stable IDE, use
`qualityGate.manualSmoke.execHarnessInstalledWorktree` from `.github/github.json`
or run `test-fixtures/exec-harness/jetbrains-inspection-installed-worktree-live.json`
with the harness output rooted in this checkout and
`JETBRAINS_INSPECTION_IDE_CONFIG_DIR` pointing at the installed stable IntelliJ
IDEA config directory. The 2026.2 fixture is an exact EAP compatibility gate; do
not use it for ordinary local agent-readiness checks unless the matching 2026.2
EAP app and config directory are installed.
