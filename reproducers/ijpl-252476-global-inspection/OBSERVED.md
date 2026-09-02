# Observed Live Result

The reproducer was run on September 2, 2026 with:

- PyCharm 2026.2.1, build `PY-262.9437.214`
- macOS 27.0 on Apple silicon
- the bundled JetBrains Runtime, Java 25.0.3
- the default inspection profile
- `fixtures/python-project`, after indexing completed

PyCharm showed two editor problems in `known_problem.py`. The reproducer also
counted eight files in the project `AnalysisScope` before either inspection
call.

## XML Export

`performInspectionsWithProgressAndExportResults(scope, false, false, ...)`
returned normally after 25 ms and reported 431 used tools. It returned no result
paths and created no XML files.

The IDE log recorded:

```text
AnalysisScope - Scanning scope took 2 ms
GlobalInspectionContextBase - Cancelling inspection progress
```

## Offline Export

`launchInspectionsOffline(scope, outputDirectory, false, resultPaths)` was run
in a fresh IDE session. It returned normally after 20 ms and reported 431 used
tools. It also returned no result paths and created no XML files.

The IDE log recorded a 3 ms scope scan followed by `Cancelling inspection
progress`.

## Cleanup

For both runs, `context.close(false)` failed because the context had no
`InspectionResultsView`; `context.cleanup()` then completed successfully. The
reproducer records the complete exception in `report.txt`.

These results show the main ambiguity we need help resolving: both methods
returned normally even though the known findings were not exported and the IDE
logged that inspection progress was being canceled.

This evidence covers the XML-only result path. It does not claim that a
separately installed `InspectionProblemConsumer` would also receive zero local
findings.
