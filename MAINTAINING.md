# Maintainer guide

Agents in the Director's catalog inspect through isolated headless IDE workers,
not this plugin. A build reaches users through the Marketplace channels and
GitHub releases; the [release smoke](TESTING.md#release-smoke) is its runtime
acceptance. Follow [DIRECTION.md](DIRECTION.md) and the current task's
authorization.

## Trying a build in your own IDE

Build the archive with `./gradlew --no-build-cache buildPlugin` (add
`-PinspectionBuild=stable` for Stable), then install it through
Settings → Plugins → ⚙ → Install Plugin from Disk and restart the IDE. Confirm
the build through `/api/inspection/identity`: `plugin_build_fingerprint` must be
`<commit>-clean` for the commit you built. Reinstall the previous archive from
GitHub releases to go back.

## Diagnosis techniques

- **Stale first attempts:** in an authorized tracing build, temporarily record
  stack traces from `PsiModificationTracker.TOPIC` and `VFS_CHANGES` paths after
  input capture, then run the [release smoke](TESTING.md#release-smoke), whose
  `responses.jsonl` keeps every plugin response. The idea log's `Project
  quiescence before inspection` line shows what the gate waited for. Trace paths and stack samples need private review before
  publication. Observation is evidence, not proof of the writing process.
- **Test removals:** plant the fault a candidate test claims to catch in an
  isolated task worktree based on current `main`, then confirm a behavioral
  failure. A duplicate/delete candidate may expose
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
