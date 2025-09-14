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
   - Use enhanced tree extraction from the inspection results window
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
- **Inspection Triggering**: `GlobalInspectionContext` and `AnalysisScope` for running inspections (supports `whole_project`, `current_file`, and `directory` scopes on `/api/inspection/trigger`)
- **Enhanced Status Tracking**: Volatile timing flags track inspection progress with timeout detection
- **Threading**: `ReadAction.compute()` and `invokeLater` for safe background execution
- **Window Management**: `ToolWindowManager` for accessing inspection results window

### Key Features

- **Complete Inspection Coverage**: All inspection types (JavaScript, Python, SpellCheck, etc.)
- **Filtering**: By scope (project/file/path) and severity (error, warning, info, grammar, typo)
- **Inspection Control**: Trigger inspections and monitor real-time progress
- **Universal Support**: Works with all IDE-supported file types

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
2. **Write Tests**: Add comprehensive tests for all new functionality before committing
3. **Run Tests**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test` - All tests must pass
4. **Build**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
5. **Install**: Load .zip from build/distributions/ in IDE
6. **Test**: Use MCP tools or direct HTTP calls
7. **Iterate**: Repeat until issues are resolved
8. **Commit**: Make commits at logical stopping points (feature complete, tests passing, documentation updated)

### Testing Requirements

**IMPORTANT**: All code changes MUST have comprehensive test coverage before committing. While coverage tools show artificially low metrics due to IntelliJ plugin architecture limitations (classloader isolation prevents proper instrumentation), this does NOT excuse writing tests. The tests DO exercise the code properly even if tools can't measure it.

**Precommit Hook**: Built-in `.git/hooks/pre-commit` runs tests, MCP syntax check, and build  
**Test Scripts**:
- All tests: `./test-all.sh` - Runs plugin + MCP tests
- Build + test: `./build.sh` - Tests then builds the plugin
- MCP Server only: `cd mcp-server && npm test`

**Test Coverage Requirements**:
- **MANDATORY**: Write tests for ALL new functionality before committing
- **MANDATORY**: Ensure existing tests still pass after changes
- **MANDATORY**: Test both happy paths and error conditions
- Write unit tests for business logic
- Write integration tests for API endpoints
- Test edge cases and error handling
- Use TDD: write failing tests first, then implement
- Aim for logical coverage even if tools can't measure it

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

## Commit Requirements

**Before ANY commit**:
1. **Tests Written**: Ensure comprehensive tests exist for all new/modified functionality
2. **Tests Pass**: Run `./test-all.sh` - ALL tests must pass
3. **Build Succeeds**: Run `./build.sh` to verify the plugin builds
4. **Manual Testing**: Test your changes in a real IDE environment
5. **Documentation**: Update relevant documentation if behavior changes

**Note on Coverage**: JaCoCo shows 0% coverage due to IntelliJ plugin classloader isolation. This is a known limitation, NOT an excuse to skip writing tests. The 65+ existing tests DO exercise the code properly - the tool just can't measure it.

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

## CLAUDE.md Example

For AI assistants using this plugin, add the following to your CLAUDE.md:

```markdown
### Inspection Usage Guidelines

When using the JetBrains inspection tools:
1. Trigger inspection with `inspection_trigger` (optionally `scope=current_file` or `scope=directory&dir=...`)
2. Check status with `inspection_get_status` until complete
3. **Key**: The status response will clearly tell you:
   - `clean_inspection: true` → Inspection passed with no problems (stop here!)
   - `has_inspection_results: true` → Problems found, call `inspection_get_problems`
   - Otherwise, no recent inspection, trigger one-first

### Handling Large Inspection Results

If you encounter token limit errors (response exceeds 25000 tokens), use filtering to reduce the response size:

1. **Start with critical issues only**: `inspection_get_problems(severity="error")`
2. **Filter by problem type**: `inspection_get_problems(problem_type="PyUnresolvedReferences")`
3. **Filter by file pattern**: `inspection_get_problems(file_pattern="src/")`
4. **Use pagination**: `inspection_get_problems(limit=50, offset=0)`, then increment offset
5. **Combine filters**: `inspection_get_problems(severity="error", file_pattern="*.py", limit=50)`

Example workflow for large projects:
- First get error count: `inspection_get_problems(severity="error", limit=1)` (check total_problems)
- Then paginate if needed: `inspection_get_problems(severity="error", limit=50, offset=0)`
- Continue with warnings after fixing errors
```

### Speeding Up In-Flow Fixes

Use targeted scopes to keep latency low while coding:

- Only changed files:
  - `inspection_trigger(scope="changed_files", max_files=50)`
- Only files you just edited:
  - `inspection_trigger(scope="files", files=["src/a.py","src/b.py"])`
- Use a lighter profile (if configured):
  - `inspection_trigger(profile="LLM Fast Checks")`

Then poll `inspection_get_status` and fetch results with `inspection_get_problems`.
