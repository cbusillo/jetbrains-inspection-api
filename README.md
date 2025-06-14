# JetBrains Inspection API

A plugin that exposes JetBrains IDE inspection results via HTTP API for automated tools and AI assistants.

## Features

- **HTTP API access** to inspection results
- **Smart status detection** (not run, running, complete)
- **Works with all JetBrains IDEs** (IntelliJ IDEA, PyCharm, WebStorm, etc.)
- **Claude Code MCP integration** for seamless AI assistant access
- **Full project scope** inspection support

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
./gradlew buildPlugin
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

### 3. Set Up Claude Code Integration (Recommended)

First, install MCP server dependencies:

```bash
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
# Trigger project-wide inspection
inspection_trigger()

# Get detailed problems list (wait a few seconds after triggering)
inspection_get_problems()

# Get problem categories summary
inspection_get_categories()
```

### Direct HTTP API
```bash
# Trigger inspection
curl "http://localhost:63341/api/inspection/trigger"

# Get problems
curl "http://localhost:63341/api/inspection/problems"

# Get categories  
curl "http://localhost:63341/api/inspection/inspections"
```

Replace `63341` with your IDE's configured port.

### Response Format
The API returns JSON with:
- `status`: `"no_inspection_run"`, `"no_results_yet"`, or `"results_available"`
- `total_problems`: Total number of issues found
- `problems`: Array of first 100 problems with description, category, and severity

## MCP Server Details

The included MCP (Model Context Protocol) server provides seamless integration with Claude Code:

### Tools Provided
- **`inspection_trigger(scope?)`** - Triggers inspection (scope defaults to "whole_project")
- **`inspection_get_problems()`** - Gets current problems and status
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

**Usage**: Use MCP tools for comprehensive inspection results:

- `inspection_trigger()` - Start project-wide inspection—`inspection_get_problems()` - Get a detailed problems list (wait a few seconds after triggering)
- `inspection_get_categories()` - Get problem categories summary

**Response format**: 
- Status: "no_inspection_run", "no_results_yet", or "results_available"
- Returns up to 100 detailed problems with description, category, and severity
- Use for project-wide code quality assessment before major commits

## Development Workflow

1. Make code changes
2. Run `inspection_trigger()` to start inspection
3. Wait a few seconds, then check results with `inspection_get_problems()`
4. Fix any critical issues found
5. Run tests
6. Commit changes
```

## License

MIT License - see [LICENSE](LICENSE) file for details.