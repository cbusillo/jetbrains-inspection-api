# Maintainer guide

Source landing and installed-runtime acceptance are separate. Agent inspections
run against the installed plugin; their `plugin_build_fingerprint` remains on
that build until a local rollout is verified.

Use this procedure for a behavior change's authorized local rollout. Carry an
already authorized rollout through without another check-in. A brief that
excludes deployments or IDE restarts leaves runtime installation pending; record
that boundary on the PR. This guide does not authorize Marketplace publication,
production changes, or restarting another worker's active IDE session. Follow
[DIRECTION.md](DIRECTION.md) and the current task's authorization.

## Local plugin rollout

The default `scripts/test-automated.sh` inspects a local test project without
installing or restarting. Within a separately authorized maintenance window,
its explicit installer stages a verified exact-SHA archive, resolves the exact
app/config pair from the bundle's data-directory selector (including Community editions), waits for helper quiescence,
checks all visible target projects and requests bounded normal quit. A cancelled
quit or unresolved/unsaved-document modal aborts the operation; an ordinary exit
confirmation can be handled through the IDE's normal quit UI. It never force-kills
or discards documents.

```bash
./scripts/test-automated.sh --install \
  --helper "$HELPER" --repo "$EXACT_SOURCE_WORKTREE" \
  --archive "$EXACT_ARCHIVE" --source-sha "$LANDING_SHA" \
  --ide-app "$EXACT_APP_BUNDLE" --artifact-root "$EXISTING_EVIDENCE_ROOT" \
  --maintenance-window
```

The source checkout must be clean at the full SHA embedded in the archive.
On Chris-Studio, `--repo` must name a host-created `work/*` linked worktree
whose HEAD is published to origin; a primary checkout or unpublished task refuses
installation. Push the source first, or use the supported manual procedure.
`--maintenance-window` records that every helper, MCP and HTTP caller of this
IDE has been paused for the whole operation; process checks alone cannot prove
that coordination. The automated resolver supports the bundle's standard config path. For a
custom `idea.config.path`, use the manual procedure below with the verified
actual installed path. Stage builds separately using the Java 21 override described
in TESTING.md. The installer uses the maintained helper's lifecycle and outcome
routing locks, preserves the prior payload and complete byte manifests, and
swaps only this plugin directory. A replacement failure restores the entire
prior file set while stopped, including removal of candidate-only files by moving
the failed directory aside. If the IDE restarts during replacement, both payloads
remain and restoration waits for another stopped maintenance window. The receipt
names the rollback and retained staging paths and marks runtime acceptance
pending; complete the fingerprint/smoke checks below. An initially stopped IDE
stays stopped; only an IDE this operation quit is relaunched. No installed state is
accepted solely from an installation receipt.

1. Resolve the PR's final merge SHA, then create a host-approved linked worktree
   at that exact commit. Its tracked and untracked state must be clean. Resolve
   Java 21 using [Testing prerequisites](TESTING.md#prerequisites), and set
   `JAVA_HOME` for direct Gradle calls. Build without the Gradle build cache:

   ```bash
   ./gradlew --no-build-cache buildPlugin
   ```

   Inspect `com/shiny/inspectionmcp/inspection-build.properties` in the packaged
   plugin jar. Require `plugin.build.commit` to equal the full landing SHA,
   `plugin.build.dirty=false`, and `plugin.build.fingerprint=<landing-sha>-clean`.
   A feature head, dirty build, or matching version number alone is insufficient.
2. Stage the exact candidate in a dated deployment directory under the host's
   approved artifact root. Inventory the plugin's installed jars in each target
   IDE (normally IntelliJ IDEA, PyCharm, and WebStorm), inventory the complete plugin-directory file set and copy it into a
   per-IDE rollback directory, and verify those copies before replacing anything.
   Keep host-specific app selectors and plugin paths in ignored local
   configuration such as `AGENTS.local.md`, not this document. Preserve the rollback copy through acceptance.
3. Coordinate a maintenance window for the target IDEs so new inspections do not
   start during replacement, including helper, MCP, and direct HTTP callers.
   Check route-pinned status for every open project in each target IDE through
   the maintained helper, rather than sampling one project per IDE. For an IDE
   with no open projects, coordinate its callers and use the process/outcome
   checks; there is no project route to query. Also check the outcome log for unfinished work, and the process
   inventory for inspection helpers. The process guard below detects helper
   processes only; it cannot prove MCP/HTTP inactivity. A log's last row
   alone cannot prove inactivity. An installer must enforce a bounded wait and
   abort before quitting or copying when a helper remains active; printing a
   warning is not a guard. For example, this macOS shell-script guard waits at
   most two minutes and fails if process inventory cannot be read. Put it in an
   installer script file, not an interactive shell or a `shell -c` command whose
   arguments themselves contain helper invocations:

   ```bash
   set -euo pipefail
   rollout_wait_deadline=$((SECONDS + 120))
   while :; do
       rollout_processes=$(ps -axww -o ucomm= -o command=) || exit 1
       if ! printf '%s\n' "$rollout_processes" |
           awk 'tolower($1) ~ /^(uv|python[0-9.]*)$/ && /[j]b-inspect[.]py/ { found = 1 } END { exit !found }'; then
           break
       fi
       if (( SECONDS >= rollout_wait_deadline )); then
           printf '%s\n' 'Inspection helper still active; installation aborted.' >&2
           exit 1
       fi
       sleep 2
   done
   ```

   The guard matches uv/Python interpreter processes, not a reviewer/editor
   prompt that merely mentions the helper. Use wide `ps` output; `pgrep -E` is
   not a macOS option. Recheck quiescence
   immediately before replacement, within the coordinated window. If other
   agents cannot pause, retain the candidate and continue source work.
4. Quit each target IDE normally. On macOS, for example:

   ```bash
   osascript -e 'if application "IntelliJ IDEA" is running then' \
       -e 'tell application "IntelliJ IDEA" to quit' \
       -e 'end if'
   ```

   Send quit only to an app that is already running. Use the configured app
   selector for the other IDEs. IntelliJ can require a
   second normal quit request. Check `ps -axww -o command=` for each exact main
   IDE executable (`<bundle>/Contents/MacOS/<launcher>`) until it is gone, with a
   bounded wait. Gradle/Kotlin daemons using the bundle's Java runtime are not
   the main IDE process; do not kill them or count them as an unclosed IDE. A cancelled quit, unsaved
   document, modal dialog, unreadable inventory, or expired wait aborts the
   install. Never force-kill. Do not copy jars while any target IDE is still alive.
5. Inventory both candidate and rollback payload manifests. Replace only this
   plugin's payload, using `cp -f` for jar replacement. The resulting file set
   must exactly match the candidate manifest, including versioned jar names;
   remove only this plugin's old files proven absent from that manifest. Keep
   unrelated plugins and IDE configuration intact. Recheck that every target
   IDE is still stopped after copying and before `cmp` or relaunch. Compare
   every payload file's bytes against the staged candidate, not only its name.
   If an IDE starts during replacement, abort the attempt and reestablish the
   maintenance window and bounded normal quit before any further payload writes.
   On replacement failure, restore the verified per-IDE rollback payload while
   the IDEs are stopped. Remove only candidate files proven absent from the
   rollback manifest; require the restored file set and all bytes to match that
   manifest exactly. After successful verification of either the candidate or
   restored rollback payload, relaunch each target normally with `open -a` and
   its configured selector.
6. Verify each target IDE's runtime fingerprint through the maintained
   `jetbrains-inspection` helper. `list-projects --json` exposes fingerprints on
   project routes, or on `identities[]` when provided for responding IDEs without
   open projects anywhere in the discovery response. No responding identity
   for a target means its fingerprint is
   unproven. Also check each smoke's retained native helper payload: read
   `.payload.route.ide.plugin_build_fingerprint` (or the retained
   `.payload.inspection_attribution.plugin_build_fingerprint` when present),
   requiring available identity fields to agree. The smoke wrapper reports the native
   `.identity.plugin_build_fingerprint`; require it to agree with the payload. Run the product-specific
   [red-lane dogfood](TESTING.md#red-lane-dogfood) once per target IDE, using an
   approved/trusted `SMOKE_ROOT`, an explicit `--helper "$HELPER"` selecting the
   maintained skill, `--keep-project` (and `--work-root` on portable hosts), and a separate
   JSON evidence file for each product. Require the intended fingerprint,
   actionable `RED` with `agent_result.bucket=actionable_findings`, positive
   findings, exact fixture route, helper exit code at most 1, and cleanup `closed`.
   Preserve a fixture and its lease if cleanup is unresolved; the smoke runner preserves unresolved projects and diagnostic evidence.
   A wrong runtime fingerprint or a decisive smoke regression fails rollout
   verification. By default, roll back every IDE targeted by that candidate,
   restoring the previously verified rollout set. Repeat step 3's quiescence
   checks within the authorized window, then step 4's bounded normal quit for
   each target. Restore and verify each exact rollback manifest as in step 5,
   then relaunch and verify the restored identities and smokes. Preserve both sets
   of evidence. Do not copy rollback files into a still-running IDE.
7. Cold IDE warm-up can make a first assessment stale. The smoke wrapper does
   not retry automatically. The helper may already have consumed its bounded
   stale/capture retry and returned `retry=false`; that result is final. Only if
   `retry_policy.retry=true`, allow at most one
   further maintained-helper assessment on the same preserved fixture, after
   its requested wait/readiness condition. Reuse the saved command's exact IDE,
   scope and profile selectors so a new smoke invocation does not create another
   fixture. Keep the same trusted-root environment and apply all step 6
   identity, verdict, finding, exit-code, and cleanup criteria to the retry too.
   Preserve the first result and any internal attempts. Do not add an
   outer retry loop or retry a terminal result. A terminal `UNKNOWN` or unresolved
   cleanup remains unproven acceptance, not a regression or a pass. After
   acceptance evidence is retained and cleanup is proven closed, retire only
   that run's owned worktree through `scripts/smoke-worktree.py retire`, using
   its retained `worktree.json` receipt and native `payload.json`, `--helper`
   and `--out` for the retirement result. The script retains dirty or unknown files, preserves known generated files
   in the run evidence, and delegates SDK retirement/removal to the maintained helper;
   mutations or unresolved leases keep the project and registration intact.
   Declared successful preparation `.venv` state and known IDE project-model files
   are archived and byte-verified first; private or unknown files remain holds.
   On Chris-Studio the host retirement dry-run also requires a published HEAD and
   no foreign working directory; it may conservatively retain an archived build
   directory the host does not classify. Push source first or use `--keep-project`
   / `--keep-worktrees` when that prerequisite cannot be satisfied.
   On portable hosts, copy the retained evidence to durable storage before the
   temporary directory is purged; the printed evidence path contains the receipt.
8. Give the rollback directory a `.retain-until` review date about a week out,
   and use the owner's existing deployment-retention sweep after installation.
   That host workflow is not implemented by this repository. If it is unavailable,
   keep the rollback and record the pending retention review. Expiry alone does
   not dispose of a rollback still needed for acceptance or recovery.
9. Record the full installed fingerprint, each IDE identity, rollback location,
   retention review date, smoke results, retries and cleanup on the merged PR.
   Use a private evidence location when host paths or logs cannot be shared.

A trial build also powers other agents' live inspections. Before replacing it,
identify outcome rows with that trial fingerprint that were not your own runs,
retain their provenance, and distinguish trial evidence from landed-build data.

## Diagnosis techniques

- **Stale first attempts:** in an authorized tracing build, temporarily record
  stack traces from `PsiModificationTracker.TOPIC` and `VFS_CHANGES` paths after
  input capture, then run a red-lane smoke in the selected IDE. Keep source,
  installation identity, and rollback evidence. The smoke report's `attempts=1`
  counts project opens; read `internal_attempts` in the helper outcome log to
  identify inspection retries. Trace paths and stack samples need private review
  before publication. Observation is evidence, not proof of the writing process.
- **File-scope before/after:** the red-lane smoke uses `whole_project`. For
  `files` acceptance, use `inspect-closeout --scope files` with explicit `--file`
  selectors on preserved fixture copies. As [TESTING.md](TESTING.md) specifies,
  clean controls use a separately named fixture profile with the same inspection
  settings. Create that profile only in the preserved fixture copy, leaving
  `test-fixtures/` unchanged; RedLane intentionally makes clean files `UNKNOWN`. Keep that same
  named profile, scope, inputs and runtime fingerprint across before/after runs.
- **Test removals:** plant the fault a candidate test claims to catch in an
  isolated task worktree based on current `main`, then confirm a behavioral
  failure. Do not modify `main` directly. A duplicate/delete candidate may expose
  missing coverage; rewrite it when the fault survives, rather than deleting the
  only behavioral intent. Restore the product before the passing verification.
- **Inlined Kotlin constants:** `const val` values are inlined into tests. For a
  planted production-constant fault, force both compilation lanes so stale test
  bytecode cannot hide it:

  ```bash
  ./gradlew :compileKotlin :compileTestKotlin --rerun-tasks --no-build-cache
  ```

  Use those flags when compilation evidence must prove that an altered constant
  reached both production and test bytecode.
- **Fault runs that can hang:** removing an upper bound can make a unit test loop
  forever. Bound that fault experiment with a poll-count guard in `checkCanceled`
  and preserve the failure. Do not use `assertTimeoutPreemptively` to leave an IDE
  worker running. The bound belongs to the fault experiment, not a production
  wait-skipping seam.
- **Static MockK stubs:** a once-per-class `mockkStatic` setup was observed to stop
  intercepting on the second test instance. Establish and tear down static stubs
  with the test instance's lifecycle; real platform fixtures and statically
  mocked Application classes belong in separate JVMs as [TESTING.md](TESTING.md)
  describes.
- **CodeQL acceptance:** a zero-result PR-head analysis is diff-informed and does
  not prove a preexisting alert is fixed. After merge, inspect the default-branch
  analysis for the exact landing and read the current alert state before closing
  the defect.

## Smoke trust retirement

Trust seeding persists beyond a smoke run. Leave shared trusted roots in place.
For an empty **dedicated smoke parent** after all projects have been retired,
preview removal of its exact Trusted Location and project-trust entries:

```bash
uv run scripts/retire-smoke-trust.py --helper "$HELPER" \
  --ide-app "$EXACT_APP_BUNDLE" --smoke-root "$DEDICATED_SMOKE_PARENT"
```

For apply, pause all inspection clients and quit that exact IDE normally first,
then add `--apply --maintenance-window --artifact-root "$EXISTING_EVIDENCE_ROOT"`.
The command holds the maintained lifecycle/outcome locks, refuses outstanding
leases, verifies the app is stopped and the dedicated root empty, backs up the
settings, and removes only matching trust entries. Other roots and IDE opening
preferences remain intact. It does not quit an IDE or grant trust. The backup
path is returned; restore it only in another stopped maintenance window after
checking that newer settings would be preserved. This exit path does not require
editing or weakening the global helper trust policy.
