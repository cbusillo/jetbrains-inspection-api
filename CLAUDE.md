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
   - Handles `/api/inspection/trigger` to initiate inspections
   - Handles `/api/inspection/status` to check inspection progress
   - Uses enhanced tree extraction from the inspection results window
   - Includes severity filtering
   - Requires triggering inspections before getting results

2. **MCP Server** (Model Context Protocol)
   - Node.js server at `mcp-server/server.js`
   - Provides Claude Code integration tools
   - Configurable port via IDE_PORT environment variable

### Technical Implementation

The plugin uses JetBrains 2025.x compatible APIs:

- **Inspection Extraction**: Enhanced tree extraction from `InspectionResultsView` and `InspectionTree`
- **Severity Detection**: Extracts severity from ProblemDescriptionNode's myLevel field, with special handling for GrazieInspection (grammar) and SpellCheckingInspection/AiaStyle (typo)
- **Inspection Triggering**: `GlobalInspectionContext` and `AnalysisScope` for running inspections
- **Enhanced Status Tracking**: Volatile timing flags track inspection progress with timeout detection
- **Threading**: `ReadAction.compute()` and `invokeLater` for safe background execution
- **Window Management**: `ToolWindowManager` for accessing inspection results window

### Key Features

1. **Complete Inspection Coverage**: Extracts results from IDE's inspection window
2. **All Inspection Types**: JavaScript, Shell, Python, SpellCheck, and all enabled inspections  
3. **Scope Control**: Filter by whole project or current file
4. **Severity Filtering**: Filter by error, warning, weak_warning, info, grammar, typo levels or all
5. **Inspection Triggering**: Programmatically trigger IDE inspections
6. **Enhanced Status Monitoring**: Real-time inspection progress tracking with timing and completion detection
7. **Thread Safe**: Background execution with proper `ReadAction` usage
8. **Universal Language Support**: Works with all IDE-supported file types
9. **Clean JSON**: Structured output compatible with automated tools

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
# Build plugin (runs tests first, then builds)
./build.sh

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
**Test Scripts**:
- All tests: `./test-all.sh` - Runs plugin + MCP tests with 80% coverage requirement
- Build + test: `./build.sh` - Tests then builds the plugin
- MCP Server only: `cd mcp-server && npm test`

**Coverage Standards**:
- All new functionality requires tests (unit + integration)
- Test public API behavior, error conditions, and performance
- Avoid testing private methods directly
- Use TDD: write failing tests first

**Test Coverage Achieved**: 57 Kotlin + 21 MCP tests (78 total) including:
- Error handling and HTTP processing
- JSON formatting and parameter validation  
- Enhanced inspection status tracking
- Severity filtering (grammar, typo, etc.)
- Extractor consolidation and cleanup

### Testing & Debugging

**Automated IDE Testing**:
```bash
# Run automated IDE lifecycle test (builds, installs, starts IDE, tests)
./test-automated.sh

# This script will:
# 1. Build the plugin
# 2. Stop any running IDE
# 3. Install plugin automatically
# 4. Start IDE with test project
# 5. Run comprehensive API tests
# 6. Report results
```

**Unit Testing**:
```bash
# Run unit tests (error handling, JSON formatting, HTTP processing)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
```

**Manual API Testing**:
```bash
# Test API endpoint directly
curl -s "http://localhost:63341/api/inspection/problems?severity=all" | jq '.'
```

**Debugging**:
```bash
# Watch debug logs in real-time
tail -f ~/Library/Logs/JetBrains/{IDE}{version}/idea.log | grep "DEBUG:"
```

## Version Management & Release

**Versioning**: Change `pluginVersion` in `gradle.properties` and everything else updates automatically via precommit hook.
**Releases**: `git tag v1.X.X && git push origin master && git push origin v1.X.X` to trigger automated GitHub release with changelog.

## Future Enhancements

### Implementation Status
- ✅ **HTTP API Framework**: Complete
- ✅ **Inspection Extraction**: Complete - Enhanced tree extraction from an inspection window
- ✅ **Inspection Triggering**: Complete - Programmatic inspection execution
- ✅ **Comprehensive Testing**: Complete - All tests passing with updated endpoints
- ✅ **Thread Safety**: Complete - proper ReadAction usage throughout
- ✅ **Error Handling**: Complete - comprehensive exception handling and graceful degradation

### Planned Enhancements
- **VCS Integration**: Uncommitted files only scope
- **Performance**: Result caching and incremental analysis  
- **Configuration**: Custom inspection profile selection
- **Batch Operations**: Multiple file analysis optimization
- **Live Updates**: WebSocket support for real-time results
- **External Tools**: ESLint, SonarQube integration
- **Advanced Filtering**: Pattern-based file selection, diff-based analysis