# Testing

This project has three test surfaces:

- **Core (Kotlin/JVM)**: shared filtering and JSON helpers (no IDE dependencies).
- **Plugin (Kotlin/Gradle)**: HTTP handler and inspection extraction/trigger logic.
- **MCP server (JVM)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 (required for Stable Gradle builds)
- Java 25 for the 262-only private-API canary, because the selected platform
  classes use Java 25 bytecode; the bundled runtime in a 2026.2 IDE is valid

If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
JDK 21 path before running the scripts.
For the private-API canary, set `JAVA_HOME_25` when Java 25 is not discoverable;
the 2026.2 IDE bundled runtime is a valid value.

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
# inspection_list_projects, project_path routing, duplicate-name ambiguity,
# accepted-run pinning, run replacement, and triggered-scope carry-forward.

# MCP server jar
./gradlew :mcp-server-jvm:mcpServerJar

# Everything (plugin tests + MCP tests + plugin build)
./scripts/test-all.sh
```

## Automated IDE smoke test

`./scripts/test-automated.sh` can install the plugin into a local IDE,
start it with a test project, and hit a few API endpoints.

- Configure your machine in `AGENTS.local.md` (copy from `AGENTS.local.template.md`).
- The smoke verifies that the live IDE reports the version from
  `gradle.properties`, selects one tracked source file, and runs `scope=files`
  so a zero-finding result requires bounded execution proof. It does not use an
  empty `whole_project` result as clean evidence.

### Installed-plugin smoke expectation

When verifying an installed plugin in a live IDE:
- **MCP Setup Discovery**: Confirm `Tools` → `Copy MCP Setup` locates `jetbrains-inspection-mcp.jar` via layered discovery (`CODE_SOURCE` → `CLASS_RESOURCE` → `PATH_MANAGER_CLASS` → `PLUGIN_ROOT_SCAN`).
- **Redacted Failure Reporting**: On resolution failure, verify that dialogs and IDE logs report redacted strategy attempt outcomes with user home directory paths replaced by `~`.
- **Workflow Support**: Confirm agent workflows operate via the preferred `codex-skills` `jetbrains-inspection` helper while MCP server tools remain fully supported.

## Agent inspection helper

The external `jetbrains-inspection` skill uses `scripts/jb-inspect.py` as the
primary agent-facing wrapper around this plugin's HTTP API (preferred for agent
workflows while MCP remains supported). Run it from the
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
prepares a directory-based project store before using the noninteractive public
project-open path for directory inputs. Explicit `.ipr` inputs outside `.idea`
preserve the requested file-based project path and use its containing directory
for trust, routing, readiness, and lifecycle ownership. Files under `.idea`
always open the containing project root regardless of filename extension. This
avoids the IDE's Open/Attach/New Window prompt while
using the worktree directory name as the frame project name, running project
configurators, and refreshing the VFS so cloned worktrees
with identical checked-in `.idea` metadata can coexist in IntelliJ IDEA,
PyCharm, and WebStorm. Current builds advertise the `lease_bound_v1` ownership
protocol: the helper persists a lease before open, passes that `lease_id`, and
accepts cleanup authority only from a later claim with
`ownership_proven=true`. Scheduling, path/session matching, or a close-token
field without that proof is not sufficient to close a project. Regression tests
must cover a user project appearing between open acceptance and IDE-thread
execution, and must prove that such a project receives no close token.
Helper-owned routes expose `lifecycle_readiness`; readiness requires a content
root covering the requested worktree after project configuration stabilizes.
For project scopes containing Python files, readiness also requires a resolved
Python SDK with no scheduled SDK refresh and no active daemon analysis. Missing
SDKs report `language_sdk_missing`; SDK refresh, analysis, or unreadable update
state reports `project_analysis_not_ready`. Neither state may publish decisive
GREEN or RED findings.
If configurators remove the initial raw-directory module, the plugin repairs
the exact lease-bound helper-owned project with a non-persistent fallback module
and reports `fallback_module_count`; the fallback must not create tracked
project files or alter a preexisting, coalesced, or outside-root project.
If neither the project model nor that bounded repair establishes coverage,
preparation must fail with
`project_content_roots_missing` and close the helper-owned project before any
inspection trigger. A project model that becomes ready but misses the required
stabilization window must remain unresolved as `project_configuration_unstable`
rather than becoming ready on the next lifecycle poll.
Imported multi-module projects may retain unrelated sibling content roots when
a normal non-fallback module covers the requested worktree. Regression coverage
must also prove that fallback-only target coverage plus an unrelated root stays
blocked as `content_roots_outside_target`.

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
Snapshot-less `/problems` calls return `no_results`/`UNKNOWN` rather than using a
live tool-window scrape as current evidence. Targeted clean captures may bound
detail rows at 25 files, but they must evaluate every resolved file and expose
the complete aggregate `scope_file_semantic_coverage` proof. Only missing
aggregate proof returns `capture_incomplete`/`scope_not_covered`; semantic gaps
beyond the detail limit remain visible through bounded examples and counts and
return `UNKNOWN`/`scope_semantic_coverage_missing` by default. HTTP and MCP stay
fail-closed; only the external helper's explicit `--allow-text-only-coverage`
option can accept generic text coverage. Recognized dependency lockfiles count
as metadata only when the plugin reports both `is_excluded=true` and the
`excluded_dependency_lockfile` role; basename-only or wildcard lockfile
exemptions are not allowed.
Once a settled clean snapshot has only a semantic-coverage gap, `/wait` must
complete with that semantic reason instead of consuming the full timeout. This
keeps default behavior fail-closed while allowing the helper's explicit
text-only override to make a decisive result.
Input changes during final publication return retryable
`inspection_inputs_changed` instead of unattributed `unknown`. MCP trigger flows
pin the accepted inspection run through wait/problems and reuse the trigger's
scope-defining parameters unless the caller explicitly supplies a new scope.
Foreign 409 runs and terminal responses without run identity remain `UNKNOWN`.
Finding-bearing snapshots preserve detail bounds and aggregate proof, so
filtering their findings to zero remains `UNKNOWN` only when complete semantic
coverage was not proven.
Use `uv run "$HELPER" summarize-outcomes` to view a helper-side rollup of
`outcomes.jsonl` by verdict, bucket, retry, IDE channel, and cleanup status
without running another inspection.
For the #209 operational denominator, point `JB_INSPECT_DEPLOYMENT_MANIFEST` at
the immutable manifest for the installed plugin/helper pair and run
`uv run "$HELPER" summarize-outcomes --qualification-file <qualification.json>
--sample-size 50`. Qualification schema v1 records an ISO-8601 boundary,
optional boundary event ID, exact helper revision, exact plugin fingerprint,
and deployment-manifest SHA-256. The strict rollup counts only `inspect` and
`inspect-closeout` assessments, groups retries by client run ID, reports every
post-boundary exclusion, and fails closed on incomplete provenance,
unattributed UNKNOWNs, artifact mismatch, or non-clean cleanup. A
configuration-blocked event is excluded only when inspection never started and
the exact missing IDE selector/configuration code is recorded.
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
  --ide-app "WebStorm" \
  --json-out tmp/dogfood-red-lane-webstorm.json
```

Use `--ide` for the inspection identity selector and `--ide-app` for the exact
macOS app bundle to launch. Keep the bundle selector aligned with the installed
application name even when channel and version selectors identify an EAP line.

This is a live IDE smoke, not a normal CI unit test. `./scripts/test-all.sh`
runs `./scripts/test-red-lane-smoke-script.sh`, which stubs the helper and
checks the IntelliJ, PyCharm, and WebStorm fixture contracts without requiring a
GUI IDE.

### Inspection execution proof (issues #239 and #259)

For `current_file`, `files`, and `changed_files` scopes, GREEN requires that at least one local inspection tool executes successfully against the resolved files. If no tool runs, any tool errors out, or the 25-file/20-second bounds are exceeded, the verdict is `UNKNOWN`/`capture_incomplete` with `capture_incomplete_reason: "execution_not_proven"`.

For `whole_project` and `directory`, the plugin uses the JetBrains native inspection run instead of repeating every local tool against every file. The plugin opens the platform's synchronous file-traversal gate and installs a run-bounded `InspectListener` subscription on the same inspection event topic used by local and global tools. GREEN requires a normal native return, at least one traversed physical file, at least one completed local or global-simple file inspection, zero inspection failures, zero unmapped native problem counts, and unchanged run/session/scope/profile/input evidence. Global-only completion or lifecycle activity without file traversal remains `UNKNOWN`/`execution_not_proven`.

Run the focused regression suite:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :test \
  --tests "*.InspectionSnapshotStateTest.*Proof*" \
  --tests "*.InspectionSnapshotStateTest.*execution*" \
  --tests "*.NativeInspectionExecutionProofTest"
```

Key expectations:
- `current_file`/`files`/`changed_files` inspection with zero executed tools → `execution_not_proven`, not GREEN
- `current_file`/`files`/`changed_files` inspection with tool errors → `execution_not_proven`, not GREEN
- bounded inspection with successful execution, no findings → GREEN (`proofEstablished=true`, `executedToolCount > 0`)
- `whole_project`/`directory` normal native completion with file traversal, file-scoped tool completions, no failures, and zero findings → GREEN
- `whole_project`/`directory` aborted, failed, zero-file, zero-file-scoped-tool, stale, or mismatched native execution → `execution_not_proven`, not GREEN
- native problem counts without mapped findings → `execution_not_proven`, not GREEN; mapped current findings remain RED
- filtering an unproven finding snapshot to zero matching findings → `execution_not_proven`, not `no_matching_findings`
- an exactly empty `changed_files` scope remains a vacuously clean result without executing tools
- `capture_diagnostic` contains `execution_proof_mode`, `execution_proof_established`, `execution_proof_clean`, `execution_proof_error_count`, `execution_proof_skipped`, `execution_proof_skipped_reason`, and `execution_proof_block_reason`; native broad scopes also include expected, analyzed, missing, unexpected, and tool-event counts under `execution_proof_native_*`

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
commit. `verifyPlugin` treats internal API usage as a release failure except
for the existing inspection-engine Marketplace exemption. Every configured IDE
report must match the exact, sorted canonical findings in
`config/plugin-verifier/stable-internal-api-allowlist.txt`; additional findings,
missing expected findings, malformed report lines, or absent reports fail the
build. This replaces broad class-name substring acceptance.

When an intentional Stable implementation change alters verifier findings,
review the complete `missing`/`unexpected` diff from `verifyPlugin`, update the
manifest with the sorted canonical first sentence of each approved finding, and
rerun `./gradlew verifyPlugin` across every configured IDE. Never add a broader
class-name pattern to make a report pass.

Canary tags use `canary/vX.Y.Z-canary.N` but do not trigger publication.
`.github/workflows/canary-release.yml` must be dispatched explicitly from the
default branch with an existing isolated tag. Its build job treats the source,
Gradle logic, verifier reports, and workspace as untrusted, persists no checkout
credentials, uses no Gradle cache, and runs without Marketplace secrets. A fresh
verification job checks out only trusted controls, downloads the built zip,
independently runs Plugin Verifier against that artifact, and requires every
artifact-derived report to match the trusted default-branch
`config/plugin-verifier/canary-internal-api-allowlist.txt`. The verification job
records the artifact digest and uploads its reports. Only then may the fresh
publish job enter the default-branch-only, reviewer-approved
`canary-marketplace` environment. It revalidates the tag SHA, archive identity,
and verified digest, then uploads through the
trusted `scripts/publish-canary-artifact.sh` with the environment-only
`CANARY_PUBLISH_TOKEN`, explicit `canary` channel, and required verified SHA-256.
That environment job has read-only repository permission. A separate write-only
GitHub release job creates the prerelease only after Marketplace upload succeeds
and rechecks the verified digest without receiving the Marketplace token. The release contract tests
cover malformed versions, Stable/canary workflow separation, branch isolation,
artifact identity, absent or wrong channels, unexpected internal APIs, and an
adversarial attempt to replace trusted verifier controls and reports.
The canary manifest is trusted, reviewable evidence. An intended canary finding
must first update that default-branch manifest through review; the experimental
branch must not modify the Stable manifest or replace exact matching with a
broader rule. Manual verification and recovery publication must run from a
separate, clean checkout pinned to the reviewed default-branch dispatch commit,
never from the canary source worktree.

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
  stable 2026.2 selectors and `--timeout-ms 300000 --prepare-timeout-ms 300000`.
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
