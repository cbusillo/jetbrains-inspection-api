# Testing JetBrains Inspection API - Project Parameter Feature

## Quick Test Instructions

To test a project-specific inspection when multiple projects are open:

1. **Ensure you have multiple projects open** in your JetBrains IDE (PyCharm, IntelliJ, etc.)

2. **Using MCP Tools in Claude Code:**
   ```bash
   # Inspect a specific project by name
   mcp__inspection-pycharm__inspection_trigger(project="odoo-intelligence-mcp")
   mcp__inspection-pycharm__inspection_get_status(project="odoo-intelligence-mcp")
   mcp__inspection-pycharm__inspection_get_problems(project="odoo-intelligence-mcp", severity="error")
   
   # Compare with different project
   mcp__inspection-pycharm__inspection_get_problems(project="odoo-ai", severity="error")
   ```

3. **Using Direct HTTP API:**
   ```bash
   # Replace 63341 with your IDE's configured port
   curl "http://localhost:63341/api/inspection/trigger?project=odoo-intelligence-mcp"
   curl "http://localhost:63341/api/inspection/problems?project=odoo-intelligence-mcp&severity=error"
   ```

4. **Expected Behavior:**
   - When you specify a project name, it should inspect ONLY that project
   - Without the project parameter, it inspects the currently focused/active project
   - If the specified project doesn't exist, you'll get a "Project not found" error
   - The project name must match exactly as shown in your IDE's project view

5. **Verify Fix:**
   - Previously, it would always inspect the first project alphabetically (e.g., "odoo-ai" before "odoo-intelligence-mcp")
   - Now it correctly inspects the specified project or the one with focus

Test both with and without the project parameter to confirm the focused project detection also works correctly.

## Fast-Path Scopes (manual smoke tests)

• Changed files only (fast inner loop):
```bash
curl "http://localhost:63341/api/inspection/trigger?scope=changed_files&include_unversioned=true&max_files=25"
curl "http://localhost:63341/api/inspection/status"
```

• Explicit files list:
```bash
curl "http://localhost:63341/api/inspection/trigger?scope=files&file=src/app.py&file=tests/test_app.py"
curl "http://localhost:63341/api/inspection/status"
```

• Light inspection profile:
```bash
curl "http://localhost:63341/api/inspection/trigger?profile=LLM%20Fast%20Checks"
```
