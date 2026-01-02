# JetBrains Inspection API

A plugin that exposes JetBrains IDE inspection results via HTTP API for automated tools and AI assistants.
It bundles an MCP server so AI clients can call the IDE’s real inspection engine instead of running duplicate linters,
including IDE-only inspections such as PyCharm’s Odoo plugin checks.

## Features

- **Real-time HTTP API access** to inspection results
- **Scope-based filtering** (whole project, current file, or specific files)
- **Severity filtering** (error, warning, weak_warning, info, or all)
- **File/path filtering** for targeted inspection analysis
- **Works with all JetBrains IDEs** (IntelliJ IDEA, PyCharm, WebStorm, etc.)
- **MCP integration** for seamless AI assistant access
- **Comprehensive inspection framework** - mirrors PyCharm's "Inspect Code" functionality
- **Complete inspection coverage** - detects JSCheckFunctionSignatures, ShellCheck, SpellCheck, and all enabled inspections
- **IDE-only inspections** - bring PyCharm-specific checks (like Odoo plugin inspections) into your automated tooling

## Quick Start

### 1. Install Plugin
**From Releases:**
1. Download a `.zip` file from [Releases](https://github.com/cbusillo/jetbrains-inspection-api/releases)
2. In your IDE: `Settings` → `Plugins` → `⚙️` → `Install Plugin from Disk...`
3. Select the downloaded file and restart the IDE

**Build from Source:**
```bash
git clone https://github.com/cbusillo/jetbrains-inspection-api.git
cd jetbrains-inspection-api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin
# Plugin will be in build/distributions/
```

If `/usr/libexec/java_home -v 21` fails, set `JAVA_HOME_21` to your JDK 21
path and rerun the command.

### 2. Configure IDE Built-in Server
1. **Open IDE Settings**: `File` → `Settings` (or `IntelliJ IDEA` → `Preferences` on macOS)
2. **Navigate to**: `Tools` → `Web Browsers and Preview`
3. In the **Built-in Server** section:
   - Set the **Port** (example: `63341`)
   - **✅ Check**: "Can accept external connections"
   - **✅ Check**: "Allow unsigned requests"
4. **Apply** settings

### 3. Set Up MCP Client

The plugin bundles the MCP server as a JVM jar.

Fast path: on the first run you’ll see a notification with a **Copy MCP Setup** button.
You can also run `Tools` → `Copy MCP Setup`, pick your MCP client, then paste the command into your terminal.

Manual examples:

```bash
# Code (Every)
code mcp add --env IDE_PORT=63341 inspection-pycharm "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"

# Codex CLI
codex mcp add inspection-pycharm --env IDE_PORT=63341 -- "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"

# Claude Code
claude mcp add --transport stdio inspection-pycharm --scope user --env IDE_PORT=63341 -- "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"

# Gemini CLI
gemini mcp add -s user -e IDE_PORT=63341 inspection-pycharm "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"
```

Notes:
- Use the IDE's bundled Java (from `java.home`) or any Java 21 on your PATH.
- The jar is installed with the plugin under the IDE's plugins directory.
- For source builds, the jar is at `mcp-server-jvm/build/libs/jetbrains-inspection-mcp.jar`.

## Usage

### With an MCP client
```bash
# Trigger a full project inspection
inspection_trigger()

# Check inspection status
inspection_get_status()

# Wait for inspection to finish
inspection_wait()

# Get all problems in project (after triggering)
inspection_get_problems()

# Get problems with severity filtering
inspection_get_problems(severity="error")

# Get problems for currently open files only
inspection_get_problems(scope="current_file")

# Specify which project to inspect (v1.10.5+)
inspection_get_problems(project="MyProject")

# Combine project and severity filtering
inspection_get_problems(project="odoo-ai", severity="error")
```

### Direct HTTP API
```bash
# Trigger inspection
curl "http://localhost:63340/api/inspection/trigger"

# Check inspection status
curl "http://localhost:63340/api/inspection/status"

# Wait for inspection to finish (long-poll)
curl "http://localhost:63340/api/inspection/wait?timeout_ms=180000&poll_ms=1000"

# Get all problems in project
curl "http://localhost:63340/api/inspection/problems"

# Get problems for current file only
curl "http://localhost:63340/api/inspection/problems?scope=current_file"

# Get only error-level problems
curl "http://localhost:63340/api/inspection/problems?severity=error"

# Specify which project to inspect (v1.10.5+)
curl "http://localhost:63340/api/inspection/problems?project=MyProject"

# Trigger inspection for specific project
curl "http://localhost:63340/api/inspection/trigger?project=odoo-ai"
```

Replace `63340` with your IDE's configured port.

## Result Schema

Typical response (truncated):
```json
{
  "status": "results_available",
  "project": "MyProject",
  "timestamp": 1766165367310,
  "total_problems": 1320,
  "problems_shown": 100,
  "problems": [
    {
      "description": "Unresolved reference 'x'",
      "file": "/abs/path/to/file.py",
      "line": 42,
      "column": 12,
      "severity": "warning",
      "category": "Python",
      "inspectionType": "PyUnresolvedReferencesInspection"
    }
  ],
  "pagination": {
    "limit": 100,
    "offset": 0,
    "has_more": true,
    "next_offset": 100
  },
  "filters": {
    "severity": "all",
    "scope": "whole_project",
    "problem_type": "all",
    "file_pattern": "all"
  },
  "method": "enhanced_tree"
}
```

## API Reference

### Problems Endpoint
**URL**: `GET /api/inspection/problems`

**Parameters**:
- `scope` (optional): `whole_project` (default) | `current_file` | custom path filter
  - `files`, `directory`, `changed_files` are treated as `whole_project` (trigger-only scopes); use `file_pattern` for filtering.
- `severity` (optional): `error` | `warning` | `weak_warning` | `info` | `grammar` | `typo` | `all` (default)
- `problem_type` (optional): Filter by inspection type (e.g., `PyUnresolvedReferencesInspection`, `SpellCheck`, `Unused`)
- `file_pattern` (optional): Filter by file path pattern - simple string or regex (e.g., `*.py`, `src/.*\.js$`)
- `limit` (optional): Maximum problems to return (default: 100)
- `offset` (optional): Number of problems to skip for pagination (default: 0)

**Examples**:
```bash
# Get only Python unresolved reference errors
curl "http://localhost:63340/api/inspection/problems?problem_type=PyUnresolvedReferences&severity=error"

# Get problems in test files only
curl "http://localhost:63340/api/inspection/problems?file_pattern=.*test.*\.py$"

# Paginate through large result sets
curl "http://localhost:63340/api/inspection/problems?limit=50&offset=0"
curl "http://localhost:63340/api/inspection/problems?limit=50&offset=50"

# Combine filters for precise results
curl "http://localhost:63340/api/inspection/problems?severity=error&file_pattern=src/&problem_type=TypeScript"
```

### Trigger Endpoint
**URL**: `GET /api/inspection/trigger`

**Parameters**:
- `project` (optional): Project name to target when multiple projects are open
- `scope` (optional):
  - `whole_project` (default)
  - `current_file` (inspect the currently selected editor file)
  - `directory` (requires `dir`, `directory`, or `path`)
  - `files` (inspect only the provided file list)
  - `changed_files` (inspect the files changed in VCS
- `dir` | `directory` | `path` (optional): Directory to inspect. Relative paths resolve from the project root; absolute paths are accepted.
- `file` (repeatable, optional): File path when `scope=files`. Can be repeated multiple times.
- `files` (optional): Comma or newline‑separated list of file paths when `scope=files`.
- `include_unversioned` (optional): `true|false` when `scope=changed_files` (default `true`).
- `changed_files_mode` (optional): `all|staged|unstaged` (best‑effort; falls back to `all`).
- `max_files` (optional): Positive integer to cap files inspected for responsiveness.
- `profile` (optional): Name of inspection profile to use; falls back to current profile if not found.

**Examples**:
```bash
# Whole project (default)
curl "http://localhost:63340/api/inspection/trigger"

# Current editor file only
curl "http://localhost:63340/api/inspection/trigger?scope=current_file"

# A specific directory (relative to project)
curl "http://localhost:63340/api/inspection/trigger?scope=directory&dir=src"

# A specific directory (absolute path)
curl "http://localhost:63340/api/inspection/trigger?scope=directory&dir=/full/path/to/addons/hr_employee_name_extended"

# Only files you specify
curl "http://localhost:63340/api/inspection/trigger?scope=files&file=src/app.py&file=tests/test_app.py"
curl "http://localhost:63340/api/inspection/trigger?scope=files&files=src/a.py,src/b.py"

# Only changed files (fast inner loop)
curl "http://localhost:63340/api/inspection/trigger?scope=changed_files&include_unversioned=true&max_files=50"

# Use a lighter inspection profile by name
curl "http://localhost:63340/api/inspection/trigger?profile=LLM%20Fast%20Checks"
```

**Response**:
```json
{
  "status": "triggered",
  "message": "Inspection triggered. Wait 10-15 seconds then call /api/inspection/problems",
  "scope": "directory",
  "directory": "src"
}
```

### Status Endpoint  
**URL**: `GET /api/inspection/status`

**Response**:
```json
{
  "project_name": "project-name",
  "is_scanning": false,
  "has_inspection_results": true,
  "clean_inspection": false,
  "inspection_in_progress": false,  
  "time_since_last_trigger_ms": 5000,
  "indexing": false,
  "problems_window_visible": true
}
```

**Key Status Fields**:
- `clean_inspection`: **NEW** - `true` when inspection completed with no problems
- `is_scanning`: `true` if inspection is currently running
- `has_inspection_results`: `true` when problems were found and are available
- `time_since_last_trigger_ms`: Time since last inspection was triggered

## Proper Usage Workflow

**Important**: Always check inspection status before retrieving problems to ensure accurate results.

### Recommended Pattern
```bash
# 1. Trigger inspection
curl "http://localhost:63340/api/inspection/trigger"

# 2. Wait for completion (check every 2-3 seconds)
while true; do
  STATUS=$(curl -s "http://localhost:63340/api/inspection/status")
  IS_SCANNING=$(echo $STATUS | jq -r '.is_scanning')
  HAS_RESULTS=$(echo $STATUS | jq -r '.has_inspection_results')
  
  if [ "$IS_SCANNING" = "false" ] && [ "$HAS_RESULTS" = "true" ]; then
    echo "✅ Inspection complete!"
    break
  else
    echo "⏳ Waiting for inspection to complete..."
    sleep 2
  fi
done

# 3. Get problems when ready
curl "http://localhost:63340/api/inspection/problems?severity=all"
```

### Understanding Status Response

The status endpoint now includes a `clean_inspection` field that makes it crystal clear:

```json
{
  "is_scanning": false,
  "has_inspection_results": false,
  "clean_inspection": true, 
  "time_since_last_trigger_ms": 15000
}
```

**Status Indicators**:
- `is_scanning: true` → Inspection running, wait
- `clean_inspection: true` → Inspection complete. No problems found
- `has_inspection_results: true` → Problems found, retrieve with `/problems`
- All false → No recent inspection, trigger one first

## MCP Server Details

The bundled JVM MCP (Model Context Protocol) server provides integration for any MCP-capable client.

### Tools Provided
- **`inspection_trigger(scope?, dir?)`** - Triggers an inspection (whole project by default; supports `scope=current_file` or `scope=directory&dir=...`)
  - Also supports `scope=files` with `file=...` (repeat) or `files=[...]`, and `scope=changed_files` with `include_unversioned` and `max_files`.
- **`inspection_get_status()`** - Checks inspection status
- **`inspection_wait(timeout_ms?, poll_ms?)`** - Long-poll until results or timeout
- **`inspection_get_problems(scope?, severity?, problem_type?, file_pattern?, limit?, offset?)`** - Gets inspection problems with filtering and pagination

### Requirements
- **Java 21+** (or the IDE's bundled runtime)
- **JetBrains IDE** with this plugin installed
- **IDE built-in server** enabled and configured

### Debugging
```bash
# Test the MCP server directly
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | java -jar /path/to/plugin/lib/jetbrains-inspection-mcp.jar

# Set custom port
IDE_PORT=63340 java -jar /path/to/plugin/lib/jetbrains-inspection-mcp.jar
```

## Development Workflow Example

Add this to your project's `AGENTS.md` (or your repo's agent instructions file):

```markdown
## Code Quality Checks

### JetBrains Inspection API

**Usage**: Use MCP tools for inspection results:

- `inspection_trigger()` - Trigger a full project inspection
- `inspection_trigger(scope="current_file")` - Trigger for the active file only
- `inspection_trigger(scope="directory", dir="src")` - Trigger for a specific directory
- `inspection_trigger(scope="files", files=["src/a.py","src/b.py"])` - Trigger for specific files
- `inspection_trigger(scope="changed_files", max_files=50)` - Trigger for changed files only
- `inspection_get_status()` - Check if inspection is complete
- `inspection_wait()` - Wait for inspection completion (long-poll)
- `inspection_get_problems()` - Get all project problems (paginated)
- `inspection_get_problems(scope="current_file")` - Get problems in open files only
- `inspection_get_problems(severity="error")` - Get only error-level problems
- `inspection_get_problems(problem_type="Unused")` - Get specific inspection types
- `inspection_get_problems(file_pattern="*.test.js")` - Get problems in matching files
- `inspection_get_problems(limit=50, offset=0)` - Paginate through results

**Handling Large Results**: When you encounter token limit errors, use filtering:
- Filter by severity: `severity="error"` (most critical issues only)
- Filter by problem type: `problem_type="PyUnresolvedReferences"`
- Filter by file pattern: `file_pattern="src/"`
- Use pagination: `limit=50` then increment `offset`

**Features**:
- Trigger and monitor inspections
- Supports Kotlin, Java, JavaScript, TypeScript, Python
- Returns detailed problems with description, category, and severity
- Use for project-wide code quality assessment before commits

## Development Workflow

1. Make code changes
2. Check real-time results with `inspection_get_problems()`
3. Fix any critical issues found
4. Run tests
5. Commit changes
```

## Optional commit gate

This repo ships a shared commit gate script in `scripts/commit-gate.sh`.
To use it as a git hook:

```bash
ln -s "$(git rev-parse --show-toplevel)/scripts/commit-gate.sh" "$(git rev-parse --git-path hooks)/pre-commit"
```

Notes:
- Set `JAVA_HOME_21` if `/usr/libexec/java_home -v 21` is unavailable.
- CI uses `./scripts/commit-gate.sh --ci` to enforce the same checks.

## Known Limitations

### Inspection Detection Coverage
- **Some inspections may not be detected**: The plugin extracts results from the IDE's inspection tree, but certain inspection categories (particularly "Entry Points" and some Java-specific inspections) may not be captured
- **IDE-specific variations**: Detection completeness may vary between different JetBrains IDEs (IntelliJ IDEA vs. PyCharm vs. WebStorm)
- **Workaround**: For complete coverage, manually review the IDE's "Problems" view in addition to API results
- **Future improvement**: Enhanced tree traversal logic is planned to improve detection rates

This limitation primarily affects Java projects in IntelliJ IDEA. PyCharm users should see more complete results.

## Testing

### Automated IDE Testing
```bash
# Run complete automated test cycle
./scripts/test-automated.sh
```

This will automatically:
- Build the plugin
- Stop any running IDE
- Install the plugin
- Start the IDE with your test project
- Run comprehensive API tests
- Report results

### Unit Tests
```bash
# Run unit tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
```

## Releases

Release notes live on GitHub Releases:

- https://github.com/cbusillo/jetbrains-inspection-api/releases

Release publishing is tag-based. Bump `pluginVersion`, then tag `vX.Y.Z` and push the tag. The GitHub Actions release workflow will publish to the JetBrains Marketplace (requires `PUBLISH_TOKEN` in GitHub Secrets) and create the GitHub Release.

Shortcut:

```bash
./scripts/release.sh --patch
```

The release script pushes commit + tag by default; use `--no-push` to keep it local.
It enforces the default branch unless you pass `--allow-non-default-branch`, and it runs both test suites (the IDE test will prompt before stopping your IDE unless you pass `--yes`).

## License

MIT License: see [LICENSE](LICENSE) file for details.
