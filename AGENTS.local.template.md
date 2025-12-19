# Local Agent Configuration Template

Copy this file to `AGENTS.local.md` and customize for your machine.

`AGENTS.local.md` is intentionally gitignored (keep local paths, ports, and IDE details out of git).

## IDE + Plugin

```bash
IDE_TYPE="PyCharm"  # or IntelliJ, WebStorm, etc.
IDE_PORT="63341"

# Optional: where the IDE installs plugins (useful for debugging scripted installs)
PLUGIN_DIR=""  # optional; if empty, scripts try to auto-detect the newest IDE install

# Optional: IDE log path
IDE_LOG_PATH=""  # optional
```

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
