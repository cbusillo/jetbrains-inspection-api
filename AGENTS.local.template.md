# Local Agent Configuration Template

Copy this file to `AGENTS.local.md` and customize for your machine.

`AGENTS.local.md` is intentionally ignored by git (keep local paths, ports, and IDE details out of git).

## IDE + Plugin

```bash
IDE_TYPE="PyCharm"  # or IntelliJ IDEA, WebStorm, etc.
IDE_VERSION=""  # optional exact inspection-helper selector, e.g. 2026.2
IDE_PORT="63341"

# Optional: Java 21 home for build/test scripts (direct Gradle needs JAVA_HOME)
JAVA_HOME_21=""  # reference value: export this in the shell for build/test commands

# Optional: an explicit plugin directory for installer --plugin-dir
PLUGIN_DIR=""  # installer validates this against the exact selected app configuration
```

`test-automated.sh` reads `IDE_TYPE`, `IDE_VERSION` and `TEST_PROJECT_PATH`
from these quoted assignments for its inspection-only default. It does not
install or restart an IDE by default. Build/test shell scripts consume the exported shell
`JAVA_HOME_21` override; they do not read it from this file. Direct Gradle calls
require `JAVA_HOME="$JAVA_HOME_21"` in the shell.
The explicit `--install` command resolves the configuration directory from the
exact app bundle using its bundled `dataDirectoryName`, including `IntelliJIdea`,
`IdeaIC`, `PyCharm` and `PyCharmCE`. An optional `--plugin-dir` must match that
resolved directory. It prints no guessed log path; inspect the selected
configuration's `idea.log` when debugging.

## Test Project

```bash
TEST_PROJECT_PATH="/Users/[username]/Developer/[project-name]"
```

## API smoke tests

```bash
curl -s "http://localhost:$IDE_PORT/api/inspection/status" | jq '.'
curl -s "http://localhost:$IDE_PORT/api/inspection/trigger?scope=whole_project" | jq '.'
curl -s "http://localhost:$IDE_PORT/api/inspection/problems?severity=warning&limit=1" | jq '.'
```
