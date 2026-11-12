# Testing

This project has three test surfaces:

- **Core (Kotlin/JVM)**: shared filtering and JSON helpers (no IDE dependencies).
- **Plugin (Kotlin/Gradle)**: HTTP handler and inspection extraction/trigger logic.
- **MCP server (JVM)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 for Gradle builds and artifact verification.
- `uv` for the commit-gate linters and their behavioral fixture tests. CI and
  the release test job install it before running the gate.

If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
JDK 21 path before running the scripts.

Gradle may need additional sandbox permissions. If you see "Operation not
permitted" from NativeServices, re-run the command with the required
permissions.

## Local commands

The local/CI commands are declared in `.github/github.json`. Replace the
local placeholders with the affected module task and test class or method,
for example `./gradlew :mcp-server-jvm:test --tests '*McpServerTest' -x :platformTest`.
Plain plugin unit tests use `:test` with that exclusion; `:test` otherwise
depends on the entire platform suite. For platform fixtures, use
`./gradlew :platformTest --tests '*SelectedPlatformTest'` instead. These run
in a separate JVM from plugin unit tests to isolate the real test application.
Maintenance helpers use their focused Python or shell fixture module directly.
Run workflow and shell lint alongside the selected tests. Safety changes also
run the complete affected modules described in `AGENTS.md`.

Do not run the full commit gate locally.
CI runs that complete gate on every PR head, main push and train branch.
The full gate remains the entrypoint for CI and release automation. Installed IDE smoke and licensed
SDK qualification below remain separate evidence, not substitutes for CI.

## Isolated Python/uv SDK controls

Run the real Python SDK and uv configurator controls against a local PyCharm
installation in Gradle's separate test JVM and worktree-local sandbox:

```bash
JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew \
  -PpythonSdkTestIdePath=/path/to/PyCharm.app pythonUvSdkTest
```

These macOS/Linux controls use `uv` to create disposable environments. They verify exact
helper/uv object reuse, project/module assignment, interrupted setup and
cancellation. They do not change the installed IDE's SDK table or establish
installed-runtime acceptance. The normal development runtime has no Python
plugin, so this separate task requires the explicit PyCharm path.

## Release smoke

`scripts/release-smoke.py` installs one plugin archive into an isolated copy of
a real IDE and asks the plugin for three verdicts on
`test-fixtures/release-smoke`. The driver decides nothing itself; it compares
the plugin's `inspection_verdict` with a fixed expectation:

| Case | Scope | Expected |
| --- | --- | --- |
| `clean` | `files` `src/clean.json` | GREEN, no findings |
| `finding` | `files` `src/finding.json` | RED, duplicate-key findings |
| `unproven` | `directory` `src` | UNKNOWN / `execution_not_proven` |

Stable cannot prove a broad scope, and the full build withholds proof because
ShellCheck is enabled for `src/check.sh` (`native_batch_annotator_unproven`).
The full build keeps the directory's findings with that UNKNOWN.

```bash
uv run scripts/release-smoke.py --archive build/distributions/jetbrains-inspection-api-X.Y.Z.zip \
  --ide-app /path/to/PyCharm.app --work-dir /new/evidence/directory
```

The work directory must be new. It holds the IDE's private config, system,
plugins, logs, `user.home`, the fixture copy, and every plugin response in
`responses.jsonl`. The IDE never reads or writes the normal profile; it starts
with a new config, so it runs in the IDE's free tier. The driver opens the fixture
through the plugin's lifecycle endpoints, closes it, and stops the IDE process
group. Each case runs once: the first check on the freshly opened project must
already be decisive, so a `stale_results` answer fails the smoke. It prints a
JSON report and exits non-zero unless every case matches.

`./scripts/release.sh tag` runs this smoke for the Stable and full archives
built from the exact tagged commit and refuses to tag when either fails.
`scripts/test-release-smoke.py` drives the driver against a fake plugin server.

### Inspection execution proof (issues #239, #259, #284, #296, and #301)

For `current_file`, `files`, and `changed_files` scopes, GREEN requires complete execution of every exact-file applicable batch-runnable obligation within the 25-file/60-second bounds. Replay is parallelized by exact file; local obligations use `InspectionEngine.inspectEx` and global-simple obligations use the public single-file route described below, with an isolated copied wrapper whose cleanup completes before proof returns, and every worker shares the same deadline and cancellation state. The API returns a sparse map containing only tools with descriptors, so the plugin keeps separate submitted, applicable, runnable, executed, and failed obligation evidence; an empty descriptor map is never sufficient by itself. Globally enabled tools that the selected profile disables for the file or whose declared language is positively non-applicable are counted but excluded. After `findTool2RunInBatch` returns `null`, the platform adapter classifies only an unpaired unfair source wrapper (`isUnfair == true` and source tool not `PairedUnfairLocalInspectionTool`) as intentionally non-batch-capable. That obligation is excluded from the batch denominator with bounded diagnostic evidence, but it is never counted as executed or clean. Paired unfair tools with an unavailable counterpart, fair missing wrappers, and unavailable metadata remain fail-closed. GREEN requires at least one real batch execution globally and on every requested file with an applicable obligation, so a file containing only excluded applicable tools remains `UNKNOWN`/`capture_incomplete` with `capture_incomplete_reason: "execution_not_proven"`; files with no applicable obligations are not falsely blocked. The platform test exercises these semantics on the 2025.1.1 development runtime, while `verifyPlugin` checks the compiled API use across every recommended IDE in the range configured in `build.gradle.kts`.

Injected-language discovery uses the same deadline-aware, write-priority read as replay. The injected-PSI platform tests hold an injector active while an IDE write or the proof deadline interrupts it; a write permits discovery to restart, while an expired deadline keeps proof incomplete.

The #296 evidence suite drives the real bounded adapter with a synthetic non-default `InspectionProfileImpl`. It proves profile-disabled obligations are excluded without hiding enabled findings, a zero deadline remains fail-closed, and `AnalysisScope` traversal agrees between native expected-file accounting and supported submission for exact files, directories, and the whole test project. `inspectEx` descriptors still carry `ProblemHighlightType` rather than the selected profile's display level, so the mapper resolves the tool's `HighlightDisplayKey` against the selected profile and may raise the descriptor-derived severity. It never lowers descriptor severity, and missing keys, PSI elements, or profile lookup failures retain the descriptor-derived value.

Broad native execution no longer initializes or polls `InspectionResultsView`. Direct presentation descriptors remain the primary broad-scope model. Exact bounded execution proof can independently confirm an empty result after every applicable tool/file obligation completes, including in a fresh IDE session where no Inspection Results or Problems surface has been created; an unreadable model or a model with unmapped descriptors still remains `UNKNOWN`, and native broad-scope proof still requires a readable empty model. Stable successful tool-window extraction remains an alternate empty-result observation when available. Missing proof or incomplete semantic scope coverage remains `UNKNOWN`. The generic Problems/inspection tool-window fallback stays available, but the capture path no longer depends on `GlobalInspectionContextImpl.getView()` or `initializeViewIfNeeded()`.

`NativeInspectionExecutionProofTest` checks that missing and unexpected files,
missing completion events, and inspection failures prevent clean proof even
when the remaining evidence is complete. Validate these controls with planted
faults in event collection and the file-set guards. Native file events remain
necessary: public path profiling skips out-of-scope files before recording a
path and cannot preserve the unexpected-file check. Activity and per-kind
completion counters are diagnostics only and are no longer collected.

Automated wrapper tests show that only local wrappers can use `inspectEx`. The Stable file-proof controls separately verify global-simple execution through the public single-file route. Native execution of global kinds and live RED/UNKNOWN preservation require a disposable IDE project: use the [release smoke](#release-smoke), which copies its fixture into a new work directory. Do not open this repository itself as #296 evidence, because IDE project-model writes can invalidate the snapshot.

Run the focused regression suite:

```bash
JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew :platformTest \
  --tests "*.SupportedInspectionExecutorPlatformTest" \
  --tests "*.NativeCompletionProofVerdictPlatformTest"
JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew :test \
  --tests "*.ExactFileExecutionProofTest" \
  --tests "*.InspectionSnapshotStateTest.*Proof*" \
  --tests "*.InspectionSnapshotStateTest.*execution*" \
  --tests "*.InspectionHandlerRunTest" \
  --tests "*.InspectionHandlerResultsTest" \
  --tests "*.InspectionHandlerLifecycleTest" \
  --tests "*.NativeInspectionExecutionProofTest" \
  --tests "*.NativeInspectionCompletionObservationTest" -x :platformTest
```

Key expectations:
- supported `inspectEx` execution returns direct descriptors for findings, omits clean/inapplicable/suppressed tools from its sparse result map, and propagates failures and cancellation; the deterministic `buildVisitor` construction-failure regression confirms the tested platform propagates that failure instead of returning an empty clean result
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
- `whole_project`/`directory` meeting the [broad-scope completion requirements](README.md#result-schema) → GREEN
- `whole_project`/`directory` aborted, failed, zero-file, zero-file-scoped-tool, stale, or mismatched native execution → `execution_not_proven`, not GREEN
- native problem counts without mapped findings → `execution_not_proven`, not GREEN
- filtering an unproven finding snapshot to zero matching findings → `execution_not_proven`, not `no_matching_findings`
- an exactly empty `changed_files` scope remains a vacuously clean result without executing tools
- `capture_diagnostic` contains `execution_proof_mode`, `execution_proof_established`, `execution_proof_clean`, `execution_proof_error_count`, `execution_proof_skipped`, `execution_proof_skipped_reason`, and `execution_proof_block_reason`; exact bounded scopes also separate globally enabled tools from candidate, applicable, non-applicable, runnable, executed, failed, unclassified, and unvisited obligations and emit bounded examples; native broad scopes include expected, analyzed, missing, unexpected, and tool-event counts under `execution_proof_native_*`

Before shipping changes to clean/capture classification, run the focused
`InspectionSnapshotStateTest` coverage and the [release smoke](#release-smoke)
for both builds.

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

The commit gate runs the
action-pin, inspection-boundary and cancellation linters, the exact internal-API manifest
validator, the release smoke driver test, and release contract tests. It requires Java 21, then
runs plugin tests,
the core and MCP 85% JaCoCo verification tasks, and `buildPlugin`. Each coverage
verification task depends on its module's tests and report, so tests are not run
twice. The plugin module has a JaCoCo report but no coverage threshold, because
IntelliJ classloader isolation prevents reliable plugin instrumentation.

The commit gate is CI's complete suite and refuses to run without `--ci`. Do
not install it as a Git hook; run the focused local checks instead.
The CI job reports its duration against a 300-second budget in the job summary
and raises a warning annotation when it is exceeded.

The required status check is `commit-gate`. CodeQL is enforced separately by
the code-scanning ruleset and thresholds recorded in `.github/github.json`;
individual `Analyze (...)` jobs are not additional required status checks.

Version tags (`v*`) run `.github/workflows/release.yml`; see
[Marketplace Stable releases](README.md#marketplace-stable-releases) for the
packaging, verification, publication and rerun flow.

### Stable and full builds

One source tree builds two archives. `-PinspectionBuild=full` (the default)
adds `src/full/kotlin`, which carries native attestation through internal
IntelliJ APIs; GitHub releases and the Marketplace `agents` channel publish it.
`-PinspectionBuild=stable` adds `src/stable/kotlin` instead, which uses public
APIs only, and is the archive the Stable release workflow builds. Each build
records its edition as `plugin.build.edition` in `inspection-build.properties`
and as `plugin_build_edition` in the identity metadata. The Stable release
validator refuses an archive whose edition is not `stable`, and
`publishPlugin` refuses to send another edition to Stable.

In a normal IDE the public native run skips its per-file pass, so the Stable
build cannot prove a `whole_project`, `directory` or `all` run clean: it returns
`UNKNOWN` with `execution_proof_block_reason:
"stable_build_cannot_prove_broad_scope"`. For `current_file`, `files` and
`changed_files` both builds prove local and global-simple inspections per file.
Global-simple tools use the public `InspectionEngine.runInspectionOnFile` route
with a copied wrapper and private context. The adapter observes `checkFile`
completion, runs the tool's start/finish callbacks, and retains both holder and
processor findings. The platform Annotator runs inside a daemon progress context
with the selected profile bound to the file; caller cancellation and the replay
deadline cancel that context. The Stable controls exercise the real Annotator
on clean Java and a Java syntax error. An unmapped processor reference leaves
proof incomplete while retaining valid holder findings from the same file.
A skipped callback, exception, missing descriptor mapping,
deadline or incomplete file obligation remains UNKNOWN. Diagnostics retain the
local-tool count and add `execution_proof_enabled_global_simple_tool_count`;
the obligation and execution totals include both kinds. See the
[result contract](README.md#result-schema) for missing-proof verdicts.
`StableBuildVerdictPlatformTest` in
`src/testStable/kotlin` checks these verdicts and compiles only in the Stable
build. It covers a clean file run, a planted single-file engine skip, both
finding channels and lifecycle callbacks, and a failure on one of two files.
Processor findings honor the tool's suppression predicate, matching the native
processor. Problem groups route through the native run's initialized local
reporting tools, retaining the selected profile's inspection name and severity;
disabled or unknown targets are dropped. An unavailable reporting map cannot
prove a grouped result clean. Holder and start findings retain their originating
tool's unfiltered presentation behavior. The grouped finding controls exercise
the exact-file proof route through the API.
Cross-file findings from either channel count as mapped, but affect the verdict
only when their file belongs to the requested scope. A missing mapping still
blocks clean proof. These checks share the same replay budget with local tools,
including in the full build after its native pass. Unreadable global-tool
metadata leaves enumeration incomplete and the verdict UNKNOWN.

Run the Stable controls with:

```bash
JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew :platformTest -PinspectionBuild=stable --tests '*.StableBuildVerdictPlatformTest'
```

`verifyPlugin` compares each configured IDE report with the exact, sorted
canonical findings in `config/plugin-verifier/<edition>-internal-api-allowlist.txt`
for the selected edition. The Stable list may contain only uses JetBrains has
exempted for this plugin; it is empty today, so any internal API use in the
Stable archive fails. Additional findings, missing expected findings, malformed
report lines, or no plugin report at all fail the build; a plugin report
without an internal API file counts as no findings. The commit gate runs the
Stable check against the compile IDE only (`-PpluginVerifierIdes=target`, about
15 seconds); the release workflows and `release-compatibility-gate.sh` check
every recommended IDE.

Verifier downloads and extracted plugins stay in each worktree's
`build/pluginVerifier`; reports stay in `build/reports/pluginVerifier`.
IDE distributions remain in Gradle's shared dependency/transform cache.
Do not share a build directory between worktrees or clean another session's
verifier home.

The commit gate also runs a real verifier with a disposable fallback user home
and fails if the verifier writes there. To exercise this regression directly:

```bash
JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew verifyPlugin \
  -PpluginVerifierIdes=target -I scripts/test-plugin-verifier-isolation.init.gradle
```

All production `GlobalInspectionContextImpl` source references must stay in
`src/full/kotlin/com/shiny/inspectionmcp/InspectionBuildEdition.kt` (including
its private attested subclass), and `scripts/lint-inspection-boundary.py`
rejects source or allowlist references that escape that named boundary on the
commit gate execution path. The boundary exists only for synchronous native
execution and native attestation that the supported bounded `inspectEx` path
cannot provide.

### Linters and release contract tests

Plugin code must not turn a cancelled read action or progress into a fallback
value. Use `runCatchingCancellable` for result fallbacks; the lint rejects
unguarded `runCatching`, `recoverCatching` and `mapCatching` calls unless a
`// Cancellation:` note explains deferred handling. The lint accepts a catch of
`Exception`, `RuntimeException`, `IllegalStateException`, or `Throwable` only when it passes the exception to
`rethrowIfCanceled` (or another named cancellation handler), ends by throwing
it, keeps it for a later throw, follows a `CancellationException` catch, or
explains in a `// Cancellation:` comment why it is safe, such as a failure
carried to another thread that rethrows it.

When an intentional implementation change alters verifier findings, review the
complete `missing`/`unexpected` diff from `verifyPlugin`, update that edition's
manifest with the sorted canonical first sentence of each approved finding, and
rerun `./gradlew verifyPlugin -PinspectionBuild=<edition>` across every
configured IDE. A Stable entry needs JetBrains' exemption for that exact use.
Never add a broader class-name pattern to make a report pass.

The release contract tests run the Stable release scripts against fakes and
cover version and channel rejection, artifact identity, source provenance,
verified digests, unexpected internal APIs, and the release PR/tag flow. Invalid
artifact fixtures supply their actual digest and require the intended validator
refusal before fake upload. Action-pin, boundary and cancellation linter tests use disposable
workflow, source and manifest fixtures; the linters enforce the rules on actual
repository inputs in the commit gate.

The required commit gate owns the 85% coverage thresholds for `inspection-core`
and `mcp-server-jvm`.

## Compatibility release gates

Before publishing a compatibility-range change, select the newly supported IDE
version explicitly and capture evidence for:

- `JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew buildPlugin`
- `JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew verifyPluginStructure`
- `JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew verifyPlugin -PinspectionBuild=stable`
- `JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}" ./gradlew verifyPlugin -PinspectionBuild=full`
- The [release smoke](#release-smoke) for both builds, run on the newly
  supported IDE version.

### Normal-IDE traversal lifecycle

Normal-IDE file traversal must be tested separately from headless execution.
The platform's closed-view gate cancels native traversal in a normal IDE, but
headless tests bypass that gate. Regression controls must exercise the real
platform gate in normal mode and compare a factory-created context with the
plugin's synchronous context. Closing a context and caller cancellation must
still stop work. Live acceptance uses the same clean files-scope fixture that
previously cancelled in `native_execute`, plus a deliberate finding control;
an UNKNOWN result is not a clean pass.
Test-only platform references and reflection seed the normal-mode gate, parent
indicator, and retained tool state that headless fixtures otherwise omit; the
release-contract boundary check applies to production sources.

The `RedLane` targeted fallback must isolate copied wrappers from the native run.
Local wrappers use `inspectEx` with the current caller indicator: the platform's
`runInspectionOnFile` substitutes an `EmptyProgressIndicator` for local tools,
which prevents mid-file caller cancellation. Non-local wrappers retain a private
context per file because `runInspectionOnFile` cleans the supplied context and
its progress indicator. Tests keep the parent progress and tool state intact,
verify cleanup on failure, and cancel a running local tool through the actual
caller indicator. These tests do not establish mid-file cancellation for global
tools or broad operational reliability.
An empty `RedLane` run deliberately remains `UNKNOWN/inspection_trigger_empty_model`;
its successful execution is not a GREEN control. Use a separately named fixture
profile with the same inspection settings for clean-result acceptance.

### Exact-proof write contention

`WritePriorityInspectionReadPlatformTest` queues an IDE write while a cooperative
inspection read is blocked. The write must complete within the test's bounded
wait, the interrupted read must report preemption, and the callback must observe
the worker before it unwinds. Separate cases preserve caller cancellation and
ensure a diagnostic callback failure cannot prevent cancellation.
`SupportedInspectionExecutorPlatformTest` also proves that bounded exact-file
proof resumes local and global-simple tools after the write and establishes
proof within the original deadline, records the transient preemption, and
avoids a terminal preemption. Engine retries receive a fresh file context after
the platform cleans the interrupted one. A deadline during a cooperative local
or global-simple run leaves proof UNKNOWN; stopping a non-cooperative tool is
not proven by these controls.
`ExactFileExecutionProofTest` checks retained findings, incomplete obligations,
worker shutdown, and wrapper cleanup. `InspectionCaptureTimingTest` verifies that
proof time cannot substitute for the clean-result observation window or extend
the total capture budget. Run these with `InspectionHandlerRunTest` for terminal
outcome and frozen diagnostic coverage.

`CaptureLoopExtractionPlatformTest` also covers an extraction that crosses the
capture deadline using the handler's clock. Stable-empty records the in-loop
observation and stays false in this control. The preserved final classification
uses completed native proof plus a clean context model to confirm GREEN after
the deadline. The run retains its deadline exit and timed-out terminal outcome.

These controls prove cooperative cancellation, not a forced stop of an arbitrary
third-party inspection. For a real recurrence, preserve the exact scope/revision,
`inspection_run_id`, `inspection_failure_diagnostic`, stage history, and owned
cleanup. Transient write preemptions resume inside the same proof budget; the
proof diagnostic records their count and first tool and file. If writes exhaust
the budget, `exact_proof_deadline` remains the terminal failure. Neither this
deadline nor an escaped `exact_proof_write_preempted` authorizes an outer
assessment retry.

### Files-scope snapshot freshness

`InspectionHandlerResultsTest` runs publication controls for whole-project and
explicit-file scopes. Both must reconcile PSI-only churn with unchanged tracked
inputs and matching findings, reject changed content, profiles, unavailable
validation and publication races, and reject edits after publication. Complete exact-file execution proof must preserve GREEN and actionable RED
without an Inspection Results window. Missing, incomplete, or non-exact proof
still requires authoritative live extraction. A real dumb-mode fixture and the
publication controls also reject proof-backed reconciliation when indexing
overlapped native analysis or exact proof; indexing after completed smart-mode proof can be
reconciled after input validation. Explicit files outside tracked roots,
under excluded roots, or in ignored workspace metadata cannot reconcile a PSI
change into fresh results. `ProjectStateCapturePlatformTest`
uses a real indexing transition to prove that the PSI counter can advance while
file bytes and saved-document state stay unchanged.
