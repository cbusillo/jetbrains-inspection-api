# JetBrains Inspection API Plugin - Claude Development Guide

## Project Overview

This project provides a JetBrains IDE plugin that exposes inspection results via HTTP API for use with automated tools and AI assistants like Claude Code.

**Target IDE**: JetBrains 2025.x (IntelliJ IDEA, WebStorm, PyCharm, etc.)  
**Language**: Kotlin  
**Build System**: Gradle with IntelliJ Platform Gradle Plugin (see `build.gradle.kts` for the current version)  

## Architecture

### Core Components

1. **InspectionHandler.kt** - Main HTTP request handler
   - Handles `/api/inspection/problems` with optional scope and severity parameters
   - Handles `/api/inspection/problems/{file-path}` for file-specific analysis
   - Handles `/api/inspection/inspections` for category summaries
   - Uses modern highlighting API for real-time problem detection
   - Includes severity filtering and improved description extraction
   - No longer requires manual triggering

2. **MCP Server** (Model Context Protocol)
   - Node.js server at `mcp-server/server.js`
   - Provides Claude Code integration tools
   - Runs on port 63340/63341

### API Endpoints

#### 1. Problems Endpoint
**URL**: `GET /api/inspection/problems?scope={scope}&severity={severity}`

**Parameters**:
- `scope`: `whole_project` (default) | `current_file`
- `severity`: `error` | `warning` | `weak_warning` | `info` | `all` (default)

#### 2. File-Specific Problems Endpoint
**URL**: `GET /api/inspection/problems/{file-path}?severity={severity}`

**Response**: Same as problems endpoint but with `"scope": "single_file"` and `"file_path"` field

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
      "description": "Problem description",
      "file": "/path/to/file.kt",
      "line": 42,
      "column": 10,
      "severity": "warning|error|weak_warning|info",
      "category": "InspectionCategory",
      "source": "highlighting_api"
    }
  ]
}
```

#### 3. Categories Endpoint
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

## Technical Implementation

### Modern API Usage

The plugin uses JetBrains 2025.x compatible APIs:

- **Highlighting API**: `DaemonCodeAnalyzerImpl.getHighlights()` for real-time problem detection
- **Threading**: `ReadAction.compute()` instead of deprecated `invokeAndWait`
- **Public APIs**: Replaced internal `InspectionManagerEx` with `InspectionManager`

### Key Features

1. **Real-time Detection**: No manual triggering needed, uses IDE's live highlighting
2. **Scope Control**: Filter by whole project, current file, or specific files
3. **Severity Filtering**: Filter by error, warning, weak_warning, info levels or all
4. **File-Specific Analysis**: Target inspection analysis to individual files
5. **Universal File Support**: Processes all file types supported by the IDE
6. **Improved Descriptions**: 5-layer fallback strategy for better issue descriptions
7. **Comprehensive Coverage**: Captures all severity levels including spell check and informational inspections
8. **Modern Threading**: Compatible with 2025.x threading model
9. **Clean JSON**: Manual JSON formatting to handle special characters

## Development Setup

### Prerequisites
- JetBrains 2025.x IDE
- Java 21+ (use `JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Node.js 18+ (for MCP server)

### Build Commands
```bash
# Build plugin
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin

# Install plugin from build/distributions/jetbrains-inspection-api-{version}.zip
```

### Key Dependencies
See `build.gradle.kts` and `gradle.properties` for current versions:
- **Gradle Plugin**: `org.jetbrains.intellij.platform` 
- **Kotlin**: Latest stable version
- **Target Platform**: IntelliJ 2025.x
- **Compatibility**: See `build.gradle.kts` ideaVersion block

## MCP Integration

### Setup
```bash
# Add MCP servers to Claude Code (replace /path/to with actual repo path).
claude mcp add-json inspection-intellij '{"command": "node", "args": ["/path/to/jetbrains-inspection-api/mcp-server/server.js"], "env": {"IDE_PORT": "63340"}}'
claude mcp add-json inspection-pycharm '{"command": "node", "args": ["/path/to/jetbrains-inspection-api/mcp-server/server.js"], "env": {"IDE_PORT": "63341"}}'
```

### Available MCP Tools
- `inspection_get_problems` - Get all inspection problems with optional scope and severity filtering
- `inspection_get_file_problems` - Get inspection problems for a specific file
- `inspection_get_categories` - Get problem categories summary

### Debugging
1. Check IDE logs at `~/Library/Logs/JetBrains/IntelliJIdea{version}/idea.log`
2. Test HTTP endpoints directly: `curl http://localhost:63340/api/inspection/problems`
3. Use MCP tools through Claude Code for integration testing

## Development Workflow

1. **Make Changes**: Edit InspectionHandler.kt or MCP server
2. **Build**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
3. **Install**: Load .zip from build/distributions/ in IDE
4. **Test**: Use MCP tools or direct HTTP calls
5. **Iterate**: Repeat until issues are resolved

## Version Management & Release

**Versioning**: Change `pluginVersion` in `gradle.properties` and everything else updates automatically via precommit hook.
**Releases**: Push a `v*` git tag (e.g., `git tag v1.4.1 && git push origin v1.4.1`) to trigger automated GitHub release with changelog.

## Future Enhancements

### Potential Improvements
- Add VCS scope (uncommitted files only)
- Implement caching for better performance
- Add configuration options for inspection selection
- Add batch operations for multiple files

### API Extensions
- Live updates via WebSocket
- Batch problem resolution tracking
- Integration with external tools (ESLint, SonarQube, etc.)
- Pattern-based file filtering (e.g., `*.{js,ts}`)
- Diff-based inspection (only changed lines)
