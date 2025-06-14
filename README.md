# JetBrains Inspection API

A plugin that exposes JetBrains IDE inspection results via HTTP API for automated tools and AI assistants.

## Features

- **Real-time HTTP API access** to inspection results
- **Scope-based filtering** (whole project, current file, or specific files)
- **Severity filtering** (error, warning, weak_warning, info, or all)
- **File-specific endpoint** for targeted inspection analysis
- **Works with all JetBrains IDEs** (IntelliJ IDEA, PyCharm, WebStorm, etc.)
- **Claude Code MCP integration** for seamless AI assistant access
- **No manual triggering required** - uses live inspection data

## Quick Start

### 1. Install Plugin
**From Releases:**
1. Download the latest `.zip` file from [Releases](https://github.com/cbusillo/jetbrains-inspection-api/releases)
2. In your IDE: `Settings` → `Plugins` → `⚙️` → `Install Plugin from Disk...`
3. Select the downloaded file and restart the IDE

**Build from Source:**
```bash
git clone https://github.com/cbusillo/jetbrains-inspection-api.git
cd jetbrains-inspection-api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin
# Plugin will be in build/distributions/
```

### 2. Configure IDE Built-in Server
1. **Open IDE Settings**: `File` → `Settings` (or `IntelliJ IDEA` → `Preferences` on Mac)
2. **Navigate to**: `Build, Execution, Deployment` → `Debugger`
3. **In the "Built-in server" section**:
   - **Port**: Set to `63341` (PyCharm), `63340` (IntelliJ), or `8080` (avoid default `63342`)
   - **✅ Check**: "Can accept external connections"
   - **✅ Check**: "Allow unsigned requests"
4. **Apply settings and restart IDE**

### 3. Set Up Claude Code MCP

First, clone the repository and install MCP server dependencies:

```bash
git clone https://github.com/cbusillo/jetbrains-inspection-api.git
cd jetbrains-inspection-api/mcp-server
npm install
```

Then add the MCP server using Claude Code CLI:

```bash
# For PyCharm (typically port 63341)
claude mcp add-json inspection-pycharm '{"command": "node", "args": ["/path/to/jetbrains-inspection-api/mcp-server/server.js"], "env": {"IDE_PORT": "63341"}}'

# For IntelliJ (typically port 63340)
claude mcp add-json inspection-intellij '{"command": "node", "args": ["/path/to/jetbrains-inspection-api/mcp-server/server.js"], "env": {"IDE_PORT": "63340"}}'

# Verify configuration
claude mcp list

# Restart Claude Code
```

**Replace `/path/to/jetbrains-inspection-api`** with actual location of your cloned repository.

## Usage

### With Claude Code (Recommended)
```bash
# Get all problems in project (real-time, no triggering needed)
inspection_get_problems()

# Get problems for currently open files only
inspection_get_problems(scope="current_file")

# Get problems with severity filtering
inspection_get_problems(severity="error")

# Get problems for specific file
inspection_get_file_problems(file_path="/path/to/file.py")

# Get problem categories summary
inspection_get_categories()
```

### Direct HTTP API
```bash
# Get all problems in project
curl "http://localhost:63340/api/inspection/problems"

# Get problems for current file only
curl "http://localhost:63340/api/inspection/problems?scope=current_file"

# Get only error-level problems
curl "http://localhost:63340/api/inspection/problems?severity=error"

# Get problems for specific file
curl "http://localhost:63340/api/inspection/problems/path/to/file.py"

# Get categories
curl "http://localhost:63340/api/inspection/inspections"
```

Replace `63340` with your IDE's configured port.

## API Reference

### Problems Endpoint
**URL**: `GET /api/inspection/problems?scope={scope}&severity={severity}`

**Parameters**:
- `scope` (optional): `whole_project` (default) | `current_file`
- `severity` (optional): `error` | `warning` | `weak_warning` | `info` | `all` (default)

### File-Specific Problems Endpoint
**URL**: `GET /api/inspection/problems/{file-path}?severity={severity}`

**Parameters**:
- `file-path`: Absolute path to the file to inspect
- `severity` (optional): Same options as above

**Response**:
```json
{
  "status": "results_available",
  "project": "project-name",
  "timestamp": 1234567890,
  "total_problems": 5,
  "problems_shown": 5,
  "scope": "whole_project",
  "method": "highlighting_api",
  "problems": [
    {
      "description": "Unused import directive",
      "file": "/path/to/file.kt",
      "line": 42,
      "column": 10,
      "severity": "warning",
      "category": "KotlinUnusedImport",
      "source": "highlighting_api"
    }
  ]
}
```

### Categories Endpoint
**URL**: `GET /api/inspection/inspections`

**Response**:
```json
{
  "status": "results_available",
  "project": "project-name",
  "timestamp": 1234567890,
  "categories": [
    {"name": "KotlinUnusedImport", "problem_count": 3},
    {"name": "DuplicatedCode", "problem_count": 2}
  ]
}
```

## MCP Server Details

The included MCP (Model Context Protocol) server provides seamless integration with Claude Code:

### Tools Provided
- **`inspection_get_problems(scope?, severity?)`** - Gets real-time problems with optional filtering
- **`inspection_get_file_problems(file_path, severity?)`** - Gets problems for a specific file
- **`inspection_get_categories()`** - Gets problem categories summary

### Requirements
- **Node.js 18.0.0+**
- **JetBrains IDE** with this plugin installed
- **IDE built-in server** enabled and configured

### Debugging
```bash
# Test the MCP server directly
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | node mcp-server/server.js

# Set custom port
IDE_PORT=63340 node mcp-server/server.js
```

## Development Workflow Example

Add this to your project's `CLAUDE.md`:

```markdown
## Code Quality Checks

### JetBrains Inspection API

**Usage**: Use MCP tools for real-time inspection results:

- `inspection_get_problems()` - Get all project problems
- `inspection_get_problems(scope="current_file")` - Get problems in open files only
- `inspection_get_problems(severity="error")` - Get only error-level problems
- `inspection_get_file_problems(file_path="/path/to/file.py")` - Get problems for a specific file
- `inspection_get_categories()` - Get problem categories summary

**Features**:
- Real-time results (no triggering needed)
- Supports Kotlin, Java, JavaScript, TypeScript, Python
- Returns up to 100 detailed problems with description, category, and severity
- Use for project-wide code quality assessment before commits

## Development Workflow

1. Make code changes
2. Check real-time results with `inspection_get_problems()`
3. Fix any critical issues found
4. Run tests
5. Commit changes
```

## Version History

### v1.5.0 (Latest)
- ✅ **File-specific endpoint** - Target inspection analysis to individual files
- ✅ **Severity filtering** - Filter by error, warning, weak_warning, info levels
- ✅ **Improved descriptions** - Better extraction of inspection issue details
- ✅ **Code quality improvements** - Eliminated duplication, enhanced maintainability

### v1.4.0
- ✅ **Real-time inspection** - No manual triggering required
- ✅ **Scope filtering** - Whole project or current file only
- ✅ **JetBrains 2025.x compatibility** - Latest IDE support
- ✅ **Universal file support** - All IDE-supported file types
- ✅ **Comprehensive coverage** - Including spell check and info-level inspections

### v1.1.0
- Updated for JetBrains 2025.x compatibility
- Replaced internal APIs with public alternatives

## License

MIT License: see [LICENSE](LICENSE) file for details.
