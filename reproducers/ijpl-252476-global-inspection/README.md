# IJPL-252476 Global Inspection Reproducer

This is a standalone IntelliJ Platform plugin for
[IJPL-252476](https://youtrack.jetbrains.com/issue/IJPL-252476). It reproduces
the live-IDE behavior discussed in that issue without running the production
Inspection API plugin.

The plugin creates a standard context with
`InspectionManager.createNewGlobalContext()` and offers two separate actions:

- `performInspectionsWithProgressAndExportResults()` with XML export enabled
- `launchInspectionsOffline()` with XML export and no problem consumer

Each action runs in its own modal progress task. Run them in separate IDE
sessions so a failed context cleanup or canceled progress indicator from one
attempt cannot affect the other.

The offline action intentionally does not install an `InspectionProblemConsumer`.
For local tools, the consumer-backed presentation can deliver findings to the
callback instead of writing the per-tool XML file. This reproducer keeps XML as
the only result path so it directly tests the approach requested by JetBrains.

## Build

Java 21 is required. From the repository root:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew -p reproducers/ijpl-252476-global-inspection clean buildPlugin
```

By default the reproducer downloads and runs PyCharm Professional 2026.2.1. To
use a locally installed IDE instead, pass its application path:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./gradlew -p reproducers/ijpl-252476-global-inspection runIde \
  -PlocalIdePath="$HOME/Applications/PyCharm.app"
```

## Reproduce

1. Start the sandbox IDE with the `runIde` command above.
2. Open `fixtures/python-project` as a project.
3. Wait for indexing and code analysis to finish.
4. Confirm that `known_problem.py` visibly reports at least one problem in the
   editor, such as the unused local variable or unresolved reference.
5. Run `Tools → IJPL-252476 Reproducer → Run XML Export`.
6. Save the report path and exit the sandbox IDE.
7. Start a fresh sandbox IDE, reopen the fixture, and run
   `Tools → IJPL-252476 Reproducer → Run Offline Export`.

Each action displays the directory containing its evidence. The directory is
also available under the IDE log directory:

```text
<IDE log directory>/ijpl-252476/<UTC timestamp>-<mode>-<run ID>/
├── report.txt
└── xml/
```

The report records the IDE and project, scope file count, inspection profile,
context implementation, progress-indicator state, call duration, exceptions,
exported paths, actual files, and cleanup outcome. The XML files are
intentionally preserved.

## What To Attach

For each action, attach:

- `report.txt`
- the complete `xml` directory
- the matching section of `idea.log`

The timestamps and run directory in `report.txt` make it possible to correlate
the report with the IDE log.

## Expected Result

The known problem should appear in the exported results. A normal return with
no XML files is important evidence because the project and scope are known to
contain an inspection problem.

The reproducer tests the XML-only path requested in the latest YouTrack reply.
It does not test whether an `InspectionProblemConsumer` could receive the same
local findings instead of XML.

This reproducer deliberately does not inspect `GlobalInspectionContextImpl`
fields, subscribe to `InspectListener`, or read Inspection Results UI state.

The first recorded live run is summarized in [OBSERVED.md](OBSERVED.md).
