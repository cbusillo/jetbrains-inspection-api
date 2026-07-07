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
   - Set the **Port** (example: `63340`)
   - The HTTP examples below use this same port.
   - **✅ Check**: "Can accept external connections"
   - **✅ Check**: "Allow unsigned requests"
4. **Apply** settings

### 3. Set Up MCP Client

The plugin bundles the MCP server as a JVM jar.

Fast path: on the first run you’ll see a notification with a **Copy MCP Setup** button.
You can also run `Tools` → `Copy MCP Setup`, pick your MCP client, then paste the command into your terminal.

Manual examples:

```bash
# Every Code
code mcp add inspection-jetbrains "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"

# Codex CLI
codex mcp add inspection-jetbrains -- "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"

# Claude Code
claude mcp add --transport stdio inspection-jetbrains --scope user -- "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"

# Gemini CLI
gemini mcp add -s user inspection-jetbrains "/path/to/java" -jar "/path/to/plugin/lib/jetbrains-inspection-mcp.jar"
```

Notes:
- Use the IDE's bundled Java (from `java.home`) or any Java 21 on your PATH.
- The jar is installed with the plugin under the IDE's plugins directory.
- For source builds, the jar is at `mcp-server-jvm/build/libs/jetbrains-inspection-mcp.jar`.
- By default, the MCP server runs in auto mode and routes by project across discovered JetBrains IDEs.
- To pin one MCP server to one IDE for debugging, add `IDE_PORT=<port>` to the MCP command.

## Usage

### With an MCP client
```bash
# Trigger a full project inspection
inspection_trigger()

# List discovered IDE projects and project keys
inspection_list_projects()

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

# Prefer a project path/key when multiple IDEs or duplicate project names are open
inspection_trigger(project_path="/Users/me/Developer/MyProject")
inspection_get_problems(project_key="path:/Users/me/Developer/MyProject")

# Combine project and severity filtering
inspection_get_problems(project="odoo-ai", severity="error")
```

Note: in MCP auto mode, the router prefers `project_key`, `project_path`, the MCP process working directory, then a unique `project` name. Blank or omitted selectors fall back to the focused/active open project only when unambiguous.

### Direct HTTP API
These examples use the `63340` port from the setup example above. If you
configured a different IDE built-in server port, use that port in each URL.

```bash
# Trigger inspection
curl "http://127.0.0.1:63340/api/inspection/trigger"

# Check inspection status
curl "http://127.0.0.1:63340/api/inspection/status"

# Wait for inspection to finish (long-poll)
curl "http://127.0.0.1:63340/api/inspection/wait?timeout_ms=180000&poll_ms=1000"

# Get all problems in project
curl "http://127.0.0.1:63340/api/inspection/problems"

# Get problems for current file only
curl "http://127.0.0.1:63340/api/inspection/problems?scope=current_file"

# Get only error-level problems
curl "http://127.0.0.1:63340/api/inspection/problems?severity=error"

# Specify which project to inspect (v1.10.5+)
curl "http://127.0.0.1:63340/api/inspection/problems?project=MyProject"

# Trigger inspection for specific project
curl "http://127.0.0.1:63340/api/inspection/trigger?project=odoo-ai"

# Inspect IDE/plugin identity and open project metadata
curl "http://127.0.0.1:63340/api/inspection/identity"

# Resolve a stateless client route before trigger/wait/problems
curl "http://127.0.0.1:63340/api/inspection/route?cwd=/Users/me/Developer/MyProject"
```

Stateless skill or script clients should resolve a route, then pass the returned
`project_key` and `session_id` to trigger, wait, status, and problems calls. If
the IDE restarts, those calls return HTTP 409 with `session_drift: true`; resolve
again and re-trigger before trusting cached results.

## Result Schema

Typical response (truncated):
```json
{
  "status": "results_available",
  "project": "MyProject",
  "project_key": "path:/abs/path/to/MyProject",
  "session_id": "4c171eb1-2f1f-4b0c-9b5b-0dd1d5b9e2a8",
  "timestamp": 1766165367310,
  "total_problems": 1320,
  "problems_shown": 100,
  "problems": [
    {
      "description": "Unresolved reference 'x'",
      "file": "/abs/path/to/file.py",
      "line": 42,
      "column": 12,
      "locationKnown": true,
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
  "route": {
    "port": 63340,
    "base_url": "http://127.0.0.1:63340/api/inspection",
    "session_id": "4c171eb1-2f1f-4b0c-9b5b-0dd1d5b9e2a8",
    "project_key": "path:/abs/path/to/MyProject",
    "project_name": "MyProject",
    "base_path": "/abs/path/to/MyProject",
    "focused": true,
    "ide": {
      "name": "IntelliJ IDEA Ultimate",
      "product_code": "IU",
      "plugin_version": "1.10.5",
      "plugin_build_fingerprint": "abc123def456-clean"
    }
  },
  "method": "enhanced_tree"
}
```

Notes:
- `locationKnown=false` means the IDE did not provide a stable file/line (often stale results). Use `locationNote` and re-run inspection.
- `status: "no_results"` uses the same pagination, filters, `total_problems`, `problems_shown`, and `problems` fields as result responses, with an empty problems list.
- `status: "capture_incomplete"` means an inspection finished, but the plugin could not conclusively capture the IDE results. Re-run the inspection or open the Problems/Inspection Results view before treating the project as clean. `capture_incomplete_reason` is a stable machine-readable bucket: `view_not_ready`, `view_updating_unreadable`, `unreadable_tree`, `extractor_failure`, `non_empty_unmapped_tree`, `current_run_psi_churn`, `timeout`, `profile_resolution_error`, `helper_plugin_error`, or `unknown`. Use `capture_diagnostic` only when debugging capture or extractor behavior; normal agent workflows should use the external helper's compact `agent_result` envelope.
- `status: "stale_results"` means project files changed after the last inspection. It is not a clean result. Cached findings are withheld by default; call `/problems?include_stale=true` only when explicitly diagnosing cached data.
- `snapshot_change_kind` explains freshness classification when present. `snapshot_predates_current_trigger` and `unsaved_documents` are stale; `current_run_psi_churn` is a fresh-run PSI tick that the plugin attempts to reconcile before returning results.
- `session_drift: true` means the client sent an old `session_id`; the IDE/plugin session restarted or the port was reused.

## API Reference

### Route Endpoint
**URL**: `GET /api/inspection/route`

Resolves selectors to the current IDE port, project, and session metadata. This
is intended for stateless HTTP clients that cannot rely on MCP process-local
routing state.

**Parameters**:
- `project_key` (optional): Stable project key from `/identity`, `/route`, or response metadata.
- `project_path` (optional): Project root or nested path within the project.
- `worktree_path` (optional): Alias for path-based project/worktree selection.
- `cwd` (optional): Current working directory used by script/skill clients.
- `project` (optional): Project name; use `project_key` or path selectors when names are duplicated.
- `ide` (optional): IDE name or product-code substring, useful when multiple IDEs are open.
- `session_id` (optional): Expected IDE session. A mismatch returns HTTP 409 with `session_drift: true`.

**Response**:
```json
{
  "status": "resolved",
  "route": {
    "port": 63340,
    "base_url": "http://127.0.0.1:63340/api/inspection",
    "session_id": "4c171eb1-2f1f-4b0c-9b5b-0dd1d5b9e2a8",
    "project_key": "path:/Users/me/Developer/MyProject",
    "project_instance_id": "4c171eb1-2f1f-4b0c-9b5b-0dd1d5b9e2a8:123456789",
    "project_name": "MyProject",
    "base_path": "/Users/me/Developer/MyProject",
    "focused": true,
    "ide": {
      "name": "IntelliJ IDEA Ultimate",
      "version": "2025.1.1",
      "product_code": "IU",
      "plugin_version": "1.10.5",
      "plugin_build_fingerprint": "abc123def456-clean"
    }
  },
  "registry": {
    "instances_dir": "/Users/me/Library/Caches/jetbrains-inspection-api/instances",
    "environment": {
      "registry_dir": "JETBRAINS_INSPECTION_REGISTRY_DIR",
      "ports": "JETBRAINS_INSPECTION_PORTS"
    },
    "ttl_ms": 60000
  }
}
```

### Identity And Registry Metadata
**URL**: `GET /api/inspection/identity`

Identity responses and registry instance files share the same schema:
`session_id`, `started_at_ms`, `heartbeat_ms`, `pid`, `port`, `ide_name`,
`ide_version`, `ide_product_code`, `plugin_version`, `plugin_build_fingerprint`,
`plugin_build_commit`, `plugin_build_short_commit`, `plugin_build_dirty`,
`plugin_build_time`, and `open_projects`. Use `plugin_build_fingerprint` to
distinguish same-version local or unreleased plugin builds when diagnosing stale
running IDE processes. Repeated route summaries include only
`plugin_build_fingerprint`; clients that need full build provenance should call
`/identity` or read the registry instance file.
Each project includes `project_key`, `name`, `base_path`, `project_file_path`,
`project_instance_id`, and `focused` as a JSON boolean. `project_instance_id` is
opaque and only stable for the lifetime of that IDE process; clients should use
it only to guard route/session ownership.

Registry files live under the OS cache directory by default:
`jetbrains-inspection-api/instances/<session_id>.json`. Override the directory
with `JETBRAINS_INSPECTION_REGISTRY_DIR`. Clients that cannot reach a known IDE
port can read fresh registry files, verify `heartbeat_ms` is within about 60
seconds, and optionally scan `JETBRAINS_INSPECTION_PORTS` such as
`63340-63350` as a fallback.

### Helper Lifecycle Endpoints

The helper lifecycle endpoints are for automation that needs to open an exact
worktree, run inspection, and clean up only the IDE project it opened. Ordinary
clients should keep using `/route`, `/trigger`, `/wait`, `/status`, and
`/problems`; those endpoints never open projects by themselves.

- `GET /api/inspection/lifecycle/open`: accepts `project_path` or
  `worktree_path`, returns immediately after scheduling an IDE open when the
  exact path is not already open, and uses the worktree directory name as the
  project frame name. Helpers must poll `/route` or `/list` until the exact
  path appears before inspecting.
- `GET /api/inspection/lifecycle/claim`: resolves the same selectors as
  `/route`, verifies optional `project_instance_id`, and returns a one-use
  `close_token` tied to the current `session_id` and project instance.
- `GET /api/inspection/lifecycle/close`: requires `project_key`,
  `project_instance_id`, `session_id`, and `close_token`. It closes the project
  only when all values still match the plugin-side claim. Token mismatch,
  session drift, or route ambiguity returns a skipped/error response and leaves
  the project open.

This contract lets script helpers preserve projects that were already open
before automation started while cleaning up helper-opened worktrees after
readiness inspection.

### Problems Endpoint
**URL**: `GET /api/inspection/problems`

**Parameters**:
- `scope` (optional): `whole_project` (default) | `current_file` | custom path filter
  - `files`, `directory`, `changed_files` are treated as `whole_project` (trigger-only scopes); use `file_pattern` for filtering.
- `severity` (optional): `error` | `warning` | `weak_warning` | `info` | `grammar` | `typo` | `all` (default)
- `problem_type` (optional): Filter by inspection type (e.g., `PyUnresolvedReferencesInspection`, `SpellCheck`, `Unused`).
  Use `all` or leave blank to disable the filter.
- `file_pattern` (optional): Filter by file path pattern. Plain strings are literal;
  globs and regexes are supported (e.g., `app.py`, `*.py`, `src/.*\.js$`).
  Use `all` or leave blank to disable the filter.
- `limit` (optional): Maximum problems to return, `1..1000` (default: 100)
- `offset` (optional): Number of problems to skip for pagination, `>=0` (default: 0)
- `include_stale` (optional): `true` returns cached stale findings for diagnostics when `status` is `stale_results`. Defaults to `false`; stale findings must not be treated as current.
- `project` (optional): Blank or omitted uses the focused or active open project. Nonblank values must match an open project.
- `project_key`, `project_path`, `worktree_path`, `cwd` (optional): Route selectors for stateless clients.
- `session_id` (optional): Expected IDE session; mismatches return HTTP 409 with `session_drift: true`.

Invalid `limit` or `offset` values return HTTP 400 with `error`, `parameter`, and `message` fields.

**Examples**:
```bash
# Get only Python unresolved reference errors
curl "http://127.0.0.1:63340/api/inspection/problems?problem_type=PyUnresolvedReferences&severity=error"

# Get problems in test files only
curl "http://127.0.0.1:63340/api/inspection/problems?file_pattern=.*test.*\.py$"

# Paginate through large result sets
curl "http://127.0.0.1:63340/api/inspection/problems?limit=50&offset=0"
curl "http://127.0.0.1:63340/api/inspection/problems?limit=50&offset=50"

# Combine filters for precise results
curl "http://127.0.0.1:63340/api/inspection/problems?severity=error&file_pattern=src/&problem_type=TypeScript"

# Diagnose cached stale findings without treating them as current
curl "http://127.0.0.1:63340/api/inspection/problems?include_stale=true"
```

### Trigger Endpoint
**URL**: `GET /api/inspection/trigger`

**Parameters**:
- `project` (optional): Project name to target when multiple projects are open
- Blank or omitted `project` uses the focused or active open project. Nonblank values must match an open project.
- `project_key`, `project_path`, `worktree_path`, `cwd` (optional): Route selectors for stateless clients.
- `session_id` (optional): Expected IDE session; mismatches return HTTP 409 with `session_drift: true`.
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
- `max_files` (optional): Positive integer to cap files inspected for responsiveness. Invalid values return HTTP 400 with `error`, `parameter`, and `message` fields.
- `profile` (optional): Name of inspection profile to use. If an explicitly requested profile is missing or cannot be verified, the result is `UNKNOWN`/`capture_incomplete` rather than silently falling back to another profile.

**Examples**:
```bash
# Whole project (default)
curl "http://127.0.0.1:63340/api/inspection/trigger"

# Current editor file only
curl "http://127.0.0.1:63340/api/inspection/trigger?scope=current_file"

# A specific directory (relative to project)
curl "http://127.0.0.1:63340/api/inspection/trigger?scope=directory&dir=src"

# A specific directory (absolute path)
curl "http://127.0.0.1:63340/api/inspection/trigger?scope=directory&dir=/full/path/to/addons/hr_employee_name_extended"

# Only files you specify
curl "http://127.0.0.1:63340/api/inspection/trigger?scope=files&file=src/app.py&file=tests/test_app.py"
curl "http://127.0.0.1:63340/api/inspection/trigger?scope=files&files=src/a.py,src/b.py"

# Only changed files (fast inner loop)
curl "http://127.0.0.1:63340/api/inspection/trigger?scope=changed_files&include_unversioned=true&max_files=50"

# Use a lighter inspection profile by name
curl "http://127.0.0.1:63340/api/inspection/trigger?profile=LLM%20Fast%20Checks"
```

**Response**:
```json
{
  "status": "triggered",
  "message": "Inspection triggered. Wait 10-15 seconds then check status",
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
- `clean_inspection`: `true` when inspection completed with no problems
- `is_scanning`: `true` if inspection is currently running
- `has_inspection_results`: `true` when problems were found and are available
- `time_since_last_trigger_ms`: Time since last inspection was triggered

## Proper Usage Workflow

**Important**: Always check inspection status before retrieving problems to ensure accurate results.

### Recommended Pattern
```bash
# 1. Trigger inspection
curl "http://127.0.0.1:63340/api/inspection/trigger"

# 2. Wait for completion (check every 2-3 seconds)
while true; do
  STATUS=$(curl -s "http://127.0.0.1:63340/api/inspection/status")
  IS_SCANNING=$(echo $STATUS | jq -r '.is_scanning')
  HAS_RESULTS=$(echo $STATUS | jq -r '.has_inspection_results')
  CLEAN=$(echo $STATUS | jq -r '.clean_inspection')
  
  if [ "$IS_SCANNING" = "false" ] && { [ "$HAS_RESULTS" = "true" ] || [ "$CLEAN" = "true" ]; }; then
    echo "✅ Inspection complete!"
    break
  else
    echo "⏳ Waiting for inspection to complete..."
    sleep 2
  fi
done

# 3. Get problems when ready
curl "http://127.0.0.1:63340/api/inspection/problems?severity=all"
```

### Understanding Status Response

The status endpoint includes a `clean_inspection` field that makes the outcome explicit:

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
- `results_may_be_stale: true` → Project changed after the last inspection; trigger again before trusting results
- `snapshot_change_kind: "current_run_psi_churn"` → The latest run's snapshot saw a PSI modification-count tick after capture; the plugin keeps waiting instead of treating the snapshot as stale cached data.
- `capture_incomplete_reason: "..."` → The subsystem bucket behind an inconclusive capture. IDE-state/retry buckets are `view_not_ready`, `view_updating_unreadable`, `unreadable_tree`, `current_run_psi_churn`, and `timeout`; profile configuration bucket is `profile_resolution_error`; plugin/helper investigation buckets are `extractor_failure`, `non_empty_unmapped_tree`, `helper_plugin_error`, and `unknown`.
- `clean_inspection: true` → Inspection complete; `inspection_verdict` is `GREEN` for the selected scope/filter
- `has_inspection_results: true` → Problems found, retrieve with `/problems`
- If all three are false and `time_since_last_trigger_ms` is recent, the inspection finished but results were not captured. Re-run the inspection or open the Inspection Results tool window.
- If all three are false and `time_since_last_trigger_ms` is old, there was no recent inspection. Trigger one first.

### Wait Response Notes
`/api/inspection/wait` always includes `wait_completed`, `timed_out`, `completion_reason`, `wait_ms`, `timeout_ms`, and `poll_ms`.

Common completion reasons:
- `results`: inspection completed and problems are ready to fetch.
- `clean`: inspection completed and a clean empty result was confirmed.
- `no_results`: inspection finished, but no trustworthy result was captured. Treat this as `UNKNOWN`, not clean; rerun inspection or open the Inspection Results view for the exact worktree.
- `capture_incomplete`: inspection finished, but the plugin could not conclusively capture the IDE results. The response includes `capture_incomplete_reason` when a bucket can be assigned; re-run the inspection or open the Problems/Inspection Results view.
- `stale_results`: cached results exist, but project files changed after the last inspection. Trigger again before trusting findings. Wait responses expose cached counts as `cached_total_problems`, not `total_problems`.
- `no_recent_inspection`: no inspection run is known for the selected project. Trigger one first.
- `no_project`: no matching project was open during the wait period. This is not reported as `timed_out`; open a project or pass the exact project name.

For agent-facing reports from the plugin API, use `inspection_verdict` and the
companion `inspection_verdict_reason`, `inspection_verdict_message`, and
`inspection_verdict_next_action` fields. The verdict is `GREEN` when inspection
worked and found no actionable findings for the selected scope/filter, `RED`
when inspection worked and returned actionable current findings, and `UNKNOWN`
when the IDE/plugin/helper did not produce a trustworthy result. `UNKNOWN` is
not clean and not red; report the included reason and next action. The external
`jb-inspect.py` helper may wrap these fields in its own compact `agent_result`
envelope for agent workflows.

### Freshness Notes
- The plugin saves documents and refreshes external file changes before starting a new inspection run.
- `/api/inspection/status` and `/api/inspection/problems` refresh project state before evaluating cached snapshots.
- If the project changed after the last inspection, `/api/inspection/status` sets `results_may_be_stale: true` and `/api/inspection/problems` returns `status: "stale_results"`. By default, the response includes cached metadata such as `cached_total_problems` and withholds `problems`; `include_stale=true` returns cached findings for diagnostics while keeping `status: "stale_results"`.
- Fresh snapshots are tied to inspection run metadata. A PSI modification-count change immediately after the same run's capture is classified as `current_run_psi_churn` and reconciled against the live IDE problem tree instead of being reported as stale cached data.

## MCP Server Details

The bundled JVM MCP (Model Context Protocol) server provides integration for any MCP-capable client.

### Tools Provided
- **`inspection_list_projects()`** - Lists discovered JetBrains IDE sessions and open projects for routing/disambiguation
- **`inspection_trigger(scope?, dir?)`** - Triggers an inspection (whole project by default; supports `scope=current_file` or `scope=directory&dir=...`)
  - Also supports `scope=files` with `file=...` (repeat) or `files=[...]`, and `scope=changed_files` with `include_unversioned` and `max_files`.
- **`inspection_get_status()`** - Checks inspection status
- **`inspection_wait(timeout_ms?, poll_ms?)`** - Long-poll until results or timeout
- **`inspection_get_problems(scope?, severity?, problem_type?, file_pattern?, limit?, offset?, include_stale?)`** - Gets inspection problems with filtering and pagination. `include_stale` returns cached stale findings for diagnostics only.

### Requirements
- **Java 21+** (or the IDE's bundled runtime)
- **JetBrains IDE** with this plugin installed
- **IDE built-in server** enabled and configured

### Debugging
```bash
# Test the MCP server directly
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | java -jar /path/to/plugin/lib/jetbrains-inspection-mcp.jar

# Pin to one IDE instead of auto-routing
IDE_PORT=63340 java -jar /path/to/plugin/lib/jetbrains-inspection-mcp.jar

# Override auto-discovery registry/scan range when debugging
JETBRAINS_INSPECTION_REGISTRY_DIR=/tmp/inspection-ides java -jar /path/to/plugin/lib/jetbrains-inspection-mcp.jar
JETBRAINS_INSPECTION_PORTS=63340-63350 java -jar /path/to/plugin/lib/jetbrains-inspection-mcp.jar
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

## Local cleanup

Use `./scripts/clean-local.sh` to remove disposable local files such as
`.DS_Store` files and old `tmp/*.log` test logs. The script intentionally leaves
local configuration, agent state, Gradle caches, IDE sandboxes, and build output
alone.

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

Release publishing is tag-based. Bump `pluginVersion`, then tag, and push the tag. The GitHub Actions release workflow will publish to the JetBrains Marketplace (requires `PUBLISH_TOKEN` in GitHub Secrets) and create the GitHub Release.

Before publishing a compatibility-range update, capture release evidence for the
target IDE line:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyPluginStructure
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyPlugin
./scripts/dogfood-red-lane-smoke.sh --product intellij --ide "IntelliJ IDEA" --ide-app "IntelliJ IDEA" --ide-channel eap --ide-version 2026.2 --timeout-ms 300000 --prepare-timeout-ms 300000
./scripts/dogfood-red-lane-smoke.sh --product pycharm --ide "PyCharm" --ide-app "PyCharm" --ide-channel eap --ide-version 2026.2 --timeout-ms 300000 --prepare-timeout-ms 300000
./scripts/dogfood-red-lane-smoke.sh --product webstorm --ide "WebStorm" --ide-app "WebStorm 2026.2 EAP" --ide-channel eap --ide-version 2026.2 --timeout-ms 300000 --prepare-timeout-ms 300000
```

For normal agent-facing worktree proof, run the maintained installed-IDE
exec-harness scenario in
`test-fixtures/exec-harness/jetbrains-inspection-installed-worktree-live.json`.
It copies the red-lane fixture into an isolated workspace and requires the
helper to prove a `RED` result, cleanup, and exact route matching in the latest
installed stable IntelliJ IDEA. The 2026.2 fixture in
`test-fixtures/exec-harness/jetbrains-inspection-262-worktree-live.json` is an
exact EAP compatibility gate; use it only when the matching 2026.2 EAP app and
config directory are installed. Set `JETBRAINS_INSPECTION_API_REPO` to this
checkout and `CODE_EXEC_HARNESS_ROOT` to the checkout that contains
`tools/code-exec-harness` when running either scenario directly. Set
`JETBRAINS_INSPECTION_IDE_CONFIG_DIR` to the installed stable IntelliJ IDEA
config directory for the installed-IDE scenario.

Shortcut:

```bash
./scripts/release.sh --patch
```

The release script pushes commit + tag by default; use `--no-push` to keep it local.
It enforces the default branch unless you pass `--allow-non-default-branch`, and it runs both test suites (the IDE test will prompt before stopping your IDE unless you pass `--yes`).

## License

MIT License: see [LICENSE](LICENSE) file for details.
