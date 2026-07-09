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

# Poll until is_scanning=false and either clean_inspection=true or has_inspection_results=true
curl "http://127.0.0.1:$IDE_PORT/api/inspection/status?project=$PROJECT_NAME"

# Or long-poll until results are ready
curl "http://127.0.0.1:$IDE_PORT/api/inspection/wait?project=$PROJECT_NAME&timeout_ms=180000&poll_ms=1000"

curl "http://127.0.0.1:$IDE_PORT/api/inspection/problems?project=$PROJECT_NAME&severity=error&limit=1"
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
- Calls with `project_key` or path selectors (`project_path`, `worktree_path`,
  or `cwd`) route to the IDE containing that project. Prefer these selectors
  over `project` when duplicate project names are possible.
- If an IDE restarts on a new port, the MCP router rediscovers it.
- If an IDE restarts during a trigger/wait/problems flow, the MCP response asks
  for a fresh trigger instead of silently using stale state.
- Duplicate project names produce an ambiguity response with paths/project keys.

## Helper lifecycle smoke

Use this when validating worktree readiness inspection behavior from the
external helper.
For release, merge-readiness, or dogfood-exit validation, prefer the repeatable
matrix runner first:

```bash
./scripts/dogfood-smoke-matrix.sh --json-out tmp/dogfood-smoke-matrix.json
```

Use the manual sequence below when debugging one matrix row or a
product-specific IDE failure.

1. Pick a repo worktree that is not currently open in the target IDE.
2. Run `jb-inspect.py inspect-closeout --repo <worktree> --scope changed_files`.
3. Confirm the helper reports an exact route with `opened_by_helper=true` and a
   cleanup status of `closed` after inspection.
4. Confirm no Trust Project, Safe Mode, or Open Project mode prompt appears; the
   external helper should seed JetBrains Trusted Locations and project-opening
   policy before lifecycle auto-open.
5. If the helper times out waiting for auto-open, treat it as a blocker. Inspect
   the helper diagnostics for unsupported IDE config layout, settings sync
   overwriting the config, or a missing inspection plugin.
6. If debugging a product-specific failure, call `/api/inspection/lifecycle/open`
   directly and confirm the endpoint returns `status=opening` quickly; the
   helper polling loop must then prove the project appears in `/list`.
7. Open the same worktree manually in the IDE and rerun `inspect-closeout`.
8. Confirm cleanup reports `not_needed` and the manually opened project remains
   open.
9. For linked worktrees, confirm the route `base_path` exactly equals the linked
   worktree, not the main checkout.

## Red lane live smoke

Use this when changing verdict classification, extraction, or helper readiness inspection
behavior and you need to prove a real IDE finding reaches agents as `RED`:

```bash
./scripts/dogfood-red-lane-smoke.sh \
  --product intellij \
  --ide "IntelliJ IDEA" \
  --json-out tmp/dogfood-red-lane.json

./scripts/dogfood-red-lane-smoke.sh \
  --product pycharm \
  --ide "PyCharm" \
  --json-out tmp/dogfood-red-lane-pycharm.json

./scripts/dogfood-red-lane-smoke.sh \
  --product webstorm \
  --ide "WebStorm" \
  --json-out tmp/dogfood-red-lane-webstorm.json
```

The command copies the selected `test-fixtures/inspection-red-lane*` fixture to
a disposable project under
`~/.code/working/jetbrains-inspection-api/red-lane-smoke`, runs helper
`inspect-closeout` with `scope=whole_project`, and passes only when the
structured helper JSON reports `VERDICT=RED`, reports `total_problems > 0`, and
closes the helper-owned project. The helper may exit non-zero because `RED` is
not readiness-clean.

## Fast-path scopes (manual)

Changed files only (fast inner loop):

```bash
IDE_PORT=63341
curl "http://127.0.0.1:$IDE_PORT/api/inspection/trigger?scope=changed_files&include_unversioned=true&max_files=25"
curl "http://127.0.0.1:$IDE_PORT/api/inspection/status"
```

Explicit files list:

```bash
IDE_PORT=63341
curl "http://127.0.0.1:$IDE_PORT/api/inspection/trigger?scope=files&file=src/app.py&file=tests/test_app.py"
curl "http://127.0.0.1:$IDE_PORT/api/inspection/status"
```

Light inspection profile:

```bash
IDE_PORT=63341
curl "http://127.0.0.1:$IDE_PORT/api/inspection/trigger?profile=LLM%20Fast%20Checks"
```

After triggering, wait for status to settle, then fetch problems:

```bash
IDE_PORT=63341
curl "http://127.0.0.1:$IDE_PORT/api/inspection/problems?severity=warning&limit=1"
```
