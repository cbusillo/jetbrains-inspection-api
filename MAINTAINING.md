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
   IDE (normally IntelliJ IDEA, PyCharm, and WebStorm), copy all of them into a
   per-IDE rollback directory, and verify those copies before replacing anything.
   Keep host-specific app selectors and plugin paths in ignored local
   configuration, not this document. Preserve the rollback copy through acceptance.
3. Coordinate a maintenance window for the target IDEs so new inspections do not
   start during replacement. Check the helper outcome log for unfinished work
   and the current process inventory for inspection helpers. A log's last row
   alone cannot prove inactivity. An installer must enforce a bounded wait and
   abort before quitting or copying when a helper remains active; printing a
   warning is not a guard. For example, this macOS shell-script guard waits at
   most two minutes and fails if process inventory cannot be read:

   ```bash
   set -euo pipefail
   rollout_wait_deadline=$((SECONDS + 120))
   while :; do
       rollout_processes=$(ps -axo command) || exit 1
       if ! printf '%s\n' "$rollout_processes" |
           awk '/[j]b-inspect[.]py/ { found = 1 } END { exit !found }'; then
           break
       fi
       if (( SECONDS >= rollout_wait_deadline )); then
           printf '%s\n' 'Inspection helper still active; installation aborted.' >&2
           exit 1
       fi
       sleep 2
   done
   ```

   Use `ps -axo command`; `pgrep -E` is not a macOS option. Recheck quiescence
   immediately before replacement, within the coordinated window. If other
   agents cannot pause, retain the candidate and continue source work.
4. Quit each target IDE normally. On macOS, for example:

   ```bash
   osascript -e 'tell application "IntelliJ IDEA" to quit'
   ```

   Use the configured app selector for the other IDEs. IntelliJ can require a
   second normal quit request. Check `ps -axo command` for each exact app bundle
   until its IDE process is gone, with a bounded wait. A cancelled quit, unsaved
   document, modal dialog, unreadable inventory, or expired wait aborts the
   install. Never force-kill. Do not copy jars while any target IDE is still alive.
5. Replace only the inventoried plugin payload, using `cp -f` for jar replacement.
   Compare every installed jar against the staged candidate with `cmp`, and
   reconcile the payload manifest so an older version's jar cannot coexist with
   the candidate. Keep unrelated plugins and IDE configuration intact. On any
   failure, leave the IDEs stopped and restore the verified per-IDE rollback
   payload before restarting; verify its bytes too. Relaunch normally with
   `open -a` and the configured app selector.
6. Verify each target IDE's runtime fingerprint through the maintained
   `jetbrains-inspection` helper. `list-projects --json` can verify an existing
   project route; an empty inventory is not fingerprint proof. Also check the
   identity returned by each smoke assessment. Run the product-specific
   [red-lane dogfood](TESTING.md#red-lane-dogfood) once per target IDE, using an
   approved/trusted `SMOKE_ROOT`, `--work-root`, `--keep-project`, and a separate
   JSON evidence file for each product. Require the intended fingerprint,
   actionable `RED`, positive findings, exact fixture route, and cleanup `closed`.
   Preserve a fixture and its lease if cleanup is unresolved; the smoke script's
   legacy deletion behavior is tracked in [#443](https://github.com/cbusillo/jetbrains-inspection-api/issues/443).
7. Cold IDE warm-up can make a first assessment stale. Read the helper's
   `retry_policy.retry` and its diagnosis; follow the maintained helper's bounded
   retry contract rather than adding an outer loop. A terminal `UNKNOWN` or
   unresolved cleanup remains unproven acceptance, not a regression or a pass.
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
  selectors on preserved fixture copies. Use the IDE default profile for clean
  controls: the RedLane profile intentionally makes clean files `UNKNOWN`.
  Match scope, profile, inputs and runtime fingerprint across the comparison.
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
