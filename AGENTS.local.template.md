# Local Agent Configuration Template

Copy this file to `AGENTS.local.md` and customize for your machine.

`AGENTS.local.md` is intentionally ignored by git (keep local paths, ports, and IDE details out of git).

## IDE + Plugin

```bash
IDE_TYPE="PyCharm"  # or IntelliJ IDEA, WebStorm, etc.
IDE_VERSION=""  # optional config-directory suffix for PyCharm/WebStorm, e.g. 2026.2
IDE_PORT="63341"

# Optional: Java 21 home (helps when /usr/libexec/java_home -v 21 fails)
JAVA_HOME_21=""  # set an absolute JDK 21 path to override discovery

# Optional: where the IDE installs plugins (useful for debugging scripted installs)
PLUGIN_DIR=""  # optional; if empty, scripts try to auto-detect the newest IDE install
```

`test-automated.sh` reads these quoted assignments from this file. It does not
read an `IDE_LOG_PATH` setting; inspect the selected IDE's `idea.log` directly
when debugging.
For IntelliJ IDEA, set `PLUGIN_DIR` to the absolute installed plugins directory:
its configuration prefix (`IntelliJIdea`) differs from the application name
used by `IDE_TYPE`. `IDE_VERSION` alone cannot select that directory.

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
