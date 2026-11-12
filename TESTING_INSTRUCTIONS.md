# Manual smoke tests

## Project selection (optional)

If you have multiple projects open in the IDE, you can target one by name.

1. Find the project name exactly as shown in the IDE UI.
2. Trigger an inspection with `project=<name>` and poll status.

Direct HTTP example:

```bash
IDE_PORT=63341
PROJECT_NAME="MyProject"

curl "http://127.0.0.1:$IDE_PORT/api/inspection/trigger?project=$PROJECT_NAME&scope=whole_project"

# Inspect the verdict and reason; availability flags alone do not prove clean
curl "http://127.0.0.1:$IDE_PORT/api/inspection/status?project=$PROJECT_NAME"

# Or long-poll until results are ready
curl "http://127.0.0.1:$IDE_PORT/api/inspection/wait?project=$PROJECT_NAME&timeout_ms=180000&poll_ms=1000"

curl "http://127.0.0.1:$IDE_PORT/api/inspection/problems?project=$PROJECT_NAME&severity=error&limit=1"
```

Expected behavior:

- With `project=...`, the plugin targets that project.
- Without `project`, HTTP selection prefers the focused/active project and
  falls back to the first usable open project. For automation, use the exact
  route- and run-pinned [recommended pattern](README.md#recommended-pattern).
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
- Calls with `project_key` or path selectors (`project_path`, `worktree_path`,
  or `cwd`) route to the IDE containing that project. Prefer these selectors
  over `project` when duplicate project names are possible.
- If an IDE restarts on a new port, the MCP router rediscovers it.
- If an IDE restarts during a trigger/wait/problems flow, the MCP response asks
  for a fresh trigger instead of silently using stale state.
- Wait and problems calls carry the trigger's accepted `inspection_run_id`; a
  replacement run returns `run_changed`/`UNKNOWN` and does not expose the
  replacement run's findings as current evidence.
- Selector-free `inspection_get_problems` reuses the trigger's targeted scope
  and its `dir`, `files`, `include_unversioned`, `changed_files_mode`, and
  `max_files` values unless the caller explicitly supplies another scope.
- Duplicate project names produce an ambiguity response with paths/project keys.

## Release smoke

Use the [release smoke](TESTING.md#release-smoke) when changing verdict
classification, extraction, or lifecycle behavior and you need to prove a real
IDE returns GREEN, RED and UNKNOWN for the fixture.

## Fast-path scopes (manual)

Use the changed-files, explicit-files and profile examples under
[Trigger Endpoint](README.md#trigger-endpoint), then fetch results through
[Problems Endpoint](README.md#problems-endpoint).
