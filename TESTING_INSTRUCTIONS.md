# Manual smoke tests

## Project selection (optional)

If you have multiple projects open in the IDE, you can target one by name.

1. Find the project name exactly as shown in the IDE UI.
2. Trigger an inspection with `project=<name>` and poll status.

Direct HTTP example:

```bash
IDE_PORT=63341
PROJECT_NAME="MyProject"

curl "http://localhost:$IDE_PORT/api/inspection/trigger?project=$PROJECT_NAME&scope=whole_project"

# Poll until is_scanning=false and either clean_inspection=true or has_inspection_results=true
curl "http://localhost:$IDE_PORT/api/inspection/status?project=$PROJECT_NAME"

# Or long-poll until results are ready
curl "http://localhost:$IDE_PORT/api/inspection/wait?project=$PROJECT_NAME&timeout_ms=180000&poll_ms=1000"

curl "http://localhost:$IDE_PORT/api/inspection/problems?project=$PROJECT_NAME&severity=error&limit=1"
```

Expected behavior:

- With `project=...`, the plugin targets that project.
- Without `project`, the plugin targets the currently focused project.
- If the project name is wrong, you will get a "Project not found" style error.

## MCP auto-routing smoke

Use this when validating one MCP server against multiple JetBrains IDEs.

1. Install the plugin in two IDE processes and open different projects.
2. Configure one MCP server without `IDE_PORT`.
3. Ask the client to call `inspection_list_projects`.
4. Trigger each project by `project_path`, then wait and fetch problems.
5. Restart one IDE and call `inspection_list_projects` again.

Expected behavior:

- `inspection_list_projects` shows live IDE sessions with project paths and keys.
- Calls with `project_path` route to the IDE containing that project.
- If an IDE restarts on a new port, the MCP router rediscovers it.
- If an IDE restarts during a trigger/wait/problems flow, the MCP response asks
  for a fresh trigger instead of silently using stale state.
- Duplicate project names produce an ambiguity response with paths/project keys.

## Fast-path scopes (manual)

Changed files only (fast inner loop):
```bash
IDE_PORT=63341
curl "http://localhost:$IDE_PORT/api/inspection/trigger?scope=changed_files&include_unversioned=true&max_files=25"
curl "http://localhost:$IDE_PORT/api/inspection/status"
```

Explicit files list:
```bash
IDE_PORT=63341
curl "http://localhost:$IDE_PORT/api/inspection/trigger?scope=files&file=src/app.py&file=tests/test_app.py"
curl "http://localhost:$IDE_PORT/api/inspection/status"
```

Light inspection profile:
```bash
IDE_PORT=63341
curl "http://localhost:$IDE_PORT/api/inspection/trigger?profile=LLM%20Fast%20Checks"
```

After triggering, wait for status to settle, then fetch problems:

```bash
IDE_PORT=63341
curl "http://localhost:$IDE_PORT/api/inspection/problems?severity=warning&limit=1"
```
