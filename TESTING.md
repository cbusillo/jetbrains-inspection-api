# Testing

This project has three test surfaces:

- **Core (Kotlin/JVM)**: shared filtering and JSON helpers (no IDE dependencies).
- **Plugin (Kotlin/Gradle)**: HTTP handler and inspection extraction/trigger logic.
- **MCP server (JVM)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 for Stable Gradle builds and trusted canary artifact verification.
- Java 25 for the 262-only canary source build.

If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
JDK 21 path before running the scripts. Set `JAVA_HOME_25` when Java 25 is not
discoverable for a canary source build.

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
Lifecycle readiness is structural and must not synthesize a whole-project
language scope. The inspection preflight evaluates the resolved requested scope:
non-Python `files` and `changed_files` scopes report `python_not_in_scope`, while
selected Python requires language support and a resolved Python SDK with no
scheduled SDK refresh or unsettled analysis. Missing support or SDK assignment
reports `language_sdk_missing`; SDK refresh, analysis, unreadable update state,
or unavailable exact-scope resolution reports `project_analysis_not_ready`.
Neither state may start inspection or publish decisive GREEN or RED findings.
When the selected Python scope has an executable project-root `.venv`, that
interpreter must be the effective SDK for every selected Python file; unrelated
or mixed Python SDK assignments remain unready. The normalized worktree-local
interpreter path is authoritative: the global target of a `.venv/bin/python`
symlink is not accepted as the local SDK. Each observation captures scope
resolution, global SDK registration, per-file module/project assignment, and
update state in one IDE read action. Inspection preflight uses an initial
ten-second observation window before taking the stable input fingerprint.
Forward progress can extend the observation-start deadline to at most thirty
seconds from settle start. No new observation starts at or after that deadline;
an IDE read that began earlier may finish afterward, but its late result is
discarded. Once settling is needed, two consecutive ready observations are
required. Missing or invalid interpreter candidates skip the wait; a candidate
that never registers remains terminal `language_sdk_missing`, while
registered-but-unassigned, mismatched, mixed, or updating SDKs remain
`project_analysis_not_ready`. Timeout diagnostics expose separate registration,
distinct-SDK, per-file assignment, mismatch, and bounded `python_sdk_settle_*`
evidence without modifying JetBrains' global SDK table.
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

This is a live IDE smoke, not a normal CI unit test. The red-lane smoke-script contract remains local-only
under `./scripts/test-all.sh` because the contract
invokes the developer-facing helper through `uv run`, which is not part of the
required CI toolchain. `./scripts/test-red-lane-smoke-script.sh` stubs the helper
and checks the IntelliJ, PyCharm, and WebStorm fixture contracts without
requiring a GUI IDE. Run it whenever the red-lane fixtures or dogfood CLI change.

### Inspection execution proof (issues #239, #259, #284, #296, and #301)

For `current_file`, `files`, and `changed_files` scopes, GREEN requires complete execution of every exact-file applicable batch-runnable obligation within the 25-file/60-second bounds. Replay is parallelized by exact file; each obligation executes through the supported `InspectionEngine.inspectEx` API with an isolated copied wrapper whose cleanup completes before proof returns, and every worker shares the same deadline and cancellation state. The API returns a sparse map containing only tools with descriptors, so the plugin keeps separate submitted, applicable, runnable, executed, and failed obligation evidence; an empty descriptor map is never sufficient by itself. Globally enabled tools that the selected profile disables for the file or whose declared language is positively non-applicable are counted but excluded. After `findTool2RunInBatch` returns `null`, the platform adapter classifies only an unpaired unfair source wrapper (`isUnfair == true` and source tool not `PairedUnfairLocalInspectionTool`) as intentionally non-batch-capable. That obligation is excluded from the batch denominator with bounded diagnostic evidence, but it is never counted as executed or clean. Paired unfair tools with an unavailable counterpart, fair missing wrappers, and unavailable metadata remain fail-closed. GREEN requires at least one real batch execution globally and on every requested file with an applicable obligation, so a file containing only excluded applicable tools remains `UNKNOWN`/`capture_incomplete` with `capture_incomplete_reason: "execution_not_proven"`; files with no applicable obligations are not falsely blocked. Current mapped findings remain decisive RED even when clean proof is incomplete. The platform test exercises these semantics on the 2025.1.1 development runtime, while `verifyPlugin` checks the compiled API use across every configured 251, 252, 253, 261, and 262 IDE line.

For `whole_project` and `directory`, the plugin uses the JetBrains native inspection run instead of repeating every local tool against every file. `InspectionEngine.inspectEx` accepts local inspection wrappers only, so it does not replace the remaining global and global-simple execution evidence. The plugin opens the platform's synchronous file-traversal gate and installs a run-bounded `InspectListener` subscription on the same inspection event topic used by local and global tools. GREEN requires a normal native return, at least one traversed physical file, at least one completed local or global-simple file inspection, zero inspection failures, zero unmapped native problem counts, and unchanged run/session/scope/profile/input evidence. Global-only completion or lifecycle activity without file traversal remains `UNKNOWN`/`execution_not_proven`.

The #296 evidence suite drives the real bounded adapter with a synthetic non-default `InspectionProfileImpl`. It proves profile-disabled obligations are excluded without hiding enabled findings, a zero deadline remains fail-closed, and `AnalysisScope` traversal agrees between native expected-file accounting and supported submission for exact files, directories, and the whole test project. `inspectEx` descriptors still carry `ProblemHighlightType` rather than the selected profile's display level, so the mapper resolves the tool's `HighlightDisplayKey` against the selected profile and may raise the descriptor-derived severity. It never lowers descriptor severity, and missing keys, PSI elements, or profile lookup failures retain the descriptor-derived value.

Broad native execution no longer initializes or polls `InspectionResultsView`. Direct presentation descriptors remain the primary broad-scope model. Exact bounded execution proof can independently confirm an empty result after every applicable tool/file obligation completes, including in a fresh IDE session where no Inspection Results or Problems surface has been created; an unreadable model or a model with unmapped descriptors still remains `UNKNOWN`, and native broad-scope proof still requires a readable empty model. Stable successful tool-window extraction remains an alternate empty-result observation when available. Missing proof or incomplete semantic scope coverage remains `UNKNOWN`. The generic Problems/inspection tool-window fallback stays available, but the capture path no longer depends on `GlobalInspectionContextImpl.getView()` or `initializeViewIfNeeded()`.

Automated wrapper tests prove only API reachability: local wrappers can use `inspectEx`, while global-simple and true-global wrappers cannot. Native execution of global kinds and live RED/UNKNOWN preservation require disposable IDE smoke projects. Use `dogfood-red-lane-smoke.sh`, which copies maintained fixtures outside the plugin checkout; do not use a helper-owned open of this repository as #296 evidence because IDE project-model writes can invalidate the worktree snapshot.

Run the focused regression suite:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :test \
  --tests "*.SupportedInspectionExecutorPlatformTest" \
  --tests "*.ExactFileExecutionProofTest" \
  --tests "*.InspectionSnapshotStateTest.*Proof*" \
  --tests "*.InspectionSnapshotStateTest.*execution*" \
  --tests "*.InspectionHandlerTest" \
  --tests "*.NativeInspectionExecutionProofTest"
```

Key expectations:
- supported `inspectEx` execution returns direct descriptors for findings, omits clean/inapplicable/suppressed tools from its sparse result map, and propagates failures and cancellation; the deterministic `buildVisitor` construction-failure regression confirms the tested platform propagates that failure and bounded proof remains non-GREEN
- the caller-selected wrapper set is recorded separately from sparse results; profile and applicability classification remain explicit caller responsibilities
- a selected non-default profile excludes disabled obligations while preserving enabled findings, and a zero proof deadline remains `execution_not_proven`
- platform metadata classifies only custom unpaired unfair local inspections as intentionally non-batch; custom paired-unfair, fair, and metadata-throwing wrappers remain fail-closed
- exact-file, directory, and whole-project `AnalysisScope` traversal matches the native expected-file set on physical test content
- selected profile severity remains external to `inspectEx` descriptors; mapped severity starts from `ProblemHighlightType`, may be raised by the selected profile, and is never lowered by it
- local/global-simple/true-global wrapper routing is automated evidence only; native global execution requires live IDE evidence
- `current_file`/`files`/`changed_files` inspection with zero executed tools → `execution_not_proven`, not GREEN
- `current_file`/`files`/`changed_files` inspection with tool errors → `execution_not_proven`, not GREEN
- native broad execution that aborts after partial completion evidence remains unproven, not GREEN
- bounded inspection with all applicable runnable obligations executed, no blocking obligations, and no findings → GREEN
- multi-file bounded replay accounts for every tool/file obligation while running exact files concurrently with copied wrapper cleanup lifecycle
- the reproduced two-file workload accounts for all 227 runnable obligations without timeout or unvisited work
- one shared deadline or cancellation stops every file worker, preserves completed/partial counts, and waits for cleanup before returning
- globally enabled but exact-file language-non-applicable tools do not block GREEN
- fair or paired-unfair missing wrappers, metadata lookup failures, unresolved obligations, failures, timeouts, or unmapped descriptors → `execution_not_proven`
- unpaired unfair wrappers with no batch wrapper → excluded from the batch denominator with explicit evidence, never counted as execution or clean evidence
- every requested file still requires at least one successfully executed batch obligation; all-excluded files remain `execution_not_proven`
- current mapped findings remain RED when clean execution proof is incomplete
- `whole_project`/`directory` normal native completion with file traversal, file-scoped tool completions, no failures, and zero findings → GREEN
- `whole_project`/`directory` aborted, failed, zero-file, zero-file-scoped-tool, stale, or mismatched native execution → `execution_not_proven`, not GREEN
- native problem counts without mapped findings → `execution_not_proven`, not GREEN; mapped current findings remain RED
- filtering an unproven finding snapshot to zero matching findings → `execution_not_proven`, not `no_matching_findings`
- an exactly empty `changed_files` scope remains a vacuously clean result without executing tools
- `capture_diagnostic` contains `execution_proof_mode`, `execution_proof_established`, `execution_proof_clean`, `execution_proof_error_count`, `execution_proof_skipped`, `execution_proof_skipped_reason`, and `execution_proof_block_reason`; exact bounded scopes also separate globally enabled tools from candidate, applicable, non-applicable, runnable, executed, failed, unclassified, and unvisited obligations and emit bounded examples; native broad scopes include expected, analyzed, missing, unexpected, and tool-event counts under `execution_proof_native_*`

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
the core and MCP 85% JaCoCo verification tasks, and `buildPlugin`. Each coverage
verification task depends on its module's tests and report, so tests are not run
twice. The plugin's own 0% JaCoCo minimum remains report-only and is not required
by CI.
Code scanning is tracked through the required `Analyze (actions)` and
`Analyze (python)` checks alongside `commit-gate`. `Analyze (java-kotlin)` also
runs as a non-required signal so Kotlin and plugin upgrades can be validated
before that check is considered stable enough to require.

Version tags (`v*`) run `.github/workflows/release.yml`, which rejects tag,
`pluginVersion`, or `plugin.xml` mismatches before publication, repeats the
commit gate, and rejects tags that do not point at the current default-branch
commit. A fresh packaging runner proves its exact checkout is clean, builds one
explicitly named archive without a Gradle build cache, runs
`verifyPluginStructure`, and uploads that zip. A separate Java 21 verification
runner downloads the archive, validates its embedded plugin ID, version,
compatibility range, source commit, and clean fingerprint, then runs
`verifyPlugin` against those exact bytes through
`-PpluginVerificationArchive` and records the plugin zip SHA-256.

The GitHub Release job and the later Marketplace job each download that same
workflow artifact, re-resolve the remote tag and default-branch commit, and
recheck the independently recorded plugin zip digest. Release notes are written
under `$RUNNER_TEMP`, and the notes and workflow summary record both the source
commit and archive SHA-256. The Marketplace job has read-only repository
permission, receives `PUBLISH_TOKEN` only for its bounded publication steps,
runs no Gradle tasks, and calls `scripts/publish-stable-artifact.sh`; it cannot
silently rebuild or substitute the artifact attached to the GitHub Release.
Use **Re-run failed jobs** for Stable recovery while the original workflow
artifact is retained. This reuses the verified zip without rewriting existing
release notes or clobbering attached assets. The workflow downloads an existing
same-name asset and requires the verified SHA-256, attaches a missing asset, and
fails closed when an existing asset has different bytes. A full workflow rerun
rebuilds timestamped plugin bytes and therefore fails against an existing asset
instead of replacing it.

`verifyPlugin` treats internal API usage as a release failure except for the
existing inspection-engine Marketplace exemption. Every configured IDE report
must match the exact, sorted canonical findings in
`config/plugin-verifier/stable-internal-api-allowlist.txt`; additional findings,
missing expected findings, malformed report lines, or absent reports fail the
build. This replaces broad class-name substring acceptance.

All remaining `GlobalInspectionContextImpl` source references must stay in
`GlobalInspectionContextBoundary.kt` (including its private attested subclass),
and the release-contract suite rejects source or Stable allowlist references
that escape that named boundary. The boundary exists only for broad native
execution, direct presentation access, lifecycle cleanup, and per-kind
attestation that the supported bounded `inspectEx` path cannot provide.

When an intentional Stable implementation change alters verifier findings,
review the complete `missing`/`unexpected` diff from `verifyPlugin`, update the
manifest with the sorted canonical first sentence of each approved finding, and
rerun `./gradlew verifyPlugin` across every configured IDE. Never add a broader
class-name pattern to make a report pass.

Canary tags use `canary/vX.Y.Z-canary.N` but do not trigger publication.
`.github/workflows/canary-release.yml` must be dispatched explicitly from the
default branch with an existing isolated tag. Its build job treats the source,
Gradle logic, verifier reports, and workspace as untrusted, persists no checkout
credentials, uses no Gradle cache, and runs without Marketplace secrets. Same-tag
dispatches are serialized. The untrusted 262-only source build uses Java 25 and
is discarded after tests. A separate fresh packaging runner checks out the
captured source SHA, re-resolves the immutable tag, proves the checkout is clean,
and rebuilds the artifact and structure report from scratch. A fresh
verification job checks out only trusted controls, downloads the built zip,
requires the embedded commit,
clean-state marker, and fingerprint to match the tagged source exactly,
independently runs Plugin Verifier against that artifact on the pinned reviewed
262 IDE build, and requires every
artifact-derived report to match the trusted default-branch
`config/plugin-verifier/canary-internal-api-allowlist.txt`. The verification job
uses Java 21, automatically selecting `JAVA_HOME_21` when invoked from a newer
Java runtime, records the artifact digest, and uploads its reports. Only then may the fresh
publish job enter the default-branch-only, reviewer-approved
`canary-marketplace` environment. It revalidates the tag SHA, archive identity,
and verified digest, then uploads through the
trusted `scripts/publish-canary-artifact.sh` with the environment-only
`CANARY_PUBLISH_TOKEN`, explicit `canary` channel, required source SHA, and
verified SHA-256. Artifact validation requires the canary's narrow
`262..262.*` compatibility range and clean tagged-source provenance.
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

The required commit gate owns the 85% coverage thresholds for `inspection-core`
and `mcp-server-jvm`. Plugin coverage remains a 0% minimum report-only signal
because IntelliJ classloader isolation prevents reliable plugin instrumentation;
`./scripts/test-all.sh` reports that signal without making it a required check.

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
