# JetBrains Inspection API Plugin - Claude Development Guide

## Project Overview

This project provides a JetBrains IDE plugin that exposes inspection results via HTTP API for use with automated tools and AI assistants like Claude Code.

**Target IDE**: JetBrains 2025.x (IntelliJ IDEA, WebStorm, PyCharm, etc.)  
**Language**: Kotlin  
**Build System**: Gradle with IntelliJ Platform Gradle Plugin (see `build.gradle.kts` for the current version)

> **For user documentation, installation, and usage instructions, see [README.md](README.md)**

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
   - Configurable port via IDE_PORT environment variable

### Technical Implementation

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

### Code Style Guidelines

**No Comments Policy**: Code should be self-documenting through descriptive naming and clear patterns. Comments are not used - instead rely on:
- Descriptive function and variable names
- Clear class and method structure
- Meaningful constant names
- Self-explanatory code organization

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

## Development Workflow

1. **Make Changes**: Edit InspectionHandler.kt or MCP server
2. **Run Tests**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test` - All tests must pass
3. **Build**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
4. **Install**: Load .zip from build/distributions/ in IDE
5. **Test**: Use MCP tools or direct HTTP calls
6. **Iterate**: Repeat until issues are resolved
7. **Commit**: Make commits at logical stopping points (feature complete, tests passing, documentation updated)

### Testing Requirements

**Precommit Hook**: Built-in `.git/hooks/pre-commit` runs tests, MCP syntax check, and build  
**Manual Testing**: 
- Plugin: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
- MCP Server: `cd mcp-server && npm test`
**CI/CD**: GitHub Actions run full test suite on push/PR

**Coverage Standards**:
- All new functionality requires tests (unit + integration)
- Test public API behavior, error conditions, and performance
- Avoid testing private methods directly
- Use TDD: write failing tests first

**Current Gaps**: Core process() method, error handling, integration tests

### Debugging
1. Check IDE logs at `~/Library/Logs/JetBrains/IntelliJIdea{version}/idea.log`
2. Test HTTP endpoints directly: `curl http://localhost:63340/api/inspection/problems`
3. Use MCP tools through Claude Code for integration testing

## Version Management & Release

**Versioning**: Change `pluginVersion` in `gradle.properties` and everything else updates automatically via precommit hook.
**Releases**: `git tag v1.X.X && git push origin master && git push origin v1.X.X` to trigger automated GitHub release with changelog.

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