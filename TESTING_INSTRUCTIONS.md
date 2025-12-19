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

curl "http://localhost:$IDE_PORT/api/inspection/problems?project=$PROJECT_NAME&severity=error&limit=1"
```

Expected behavior:

- With `project=...`, the plugin targets that project.
- Without `project`, the plugin targets the currently focused project.
- If the project name is wrong, you will get a "Project not found" style error.

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
