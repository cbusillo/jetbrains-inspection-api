# Testing Guide

## Testing Philosophy

This project follows test-driven development (TDD) principles with comprehensive coverage across all components. Tests serve as living documentation and ensure code reliability across IDE versions and environments.

## Test Structure

### Plugin Tests (Kotlin)
- **Framework**: JUnit 5 with Mockito
- **Location**: `src/test/kotlin/`
- **Focus**: HTTP routing, JSON utilities, core functionality
- **Run**: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`

### MCP Server Tests (JavaScript)
- **Framework**: Node.js built-in test runner
- **Location**: `mcp-server/test/`
- **Focus**: Server startup, tool registration, HTTP handling, error recovery
- **Run**: `cd mcp-server && npm test`

## Test Categories

### Unit Tests
- Test individual functions and methods in isolation
- Mock external dependencies
- Fast execution (< 5 seconds total)
- Cover edge cases and error conditions

### Integration Tests
- Test component interactions
- Use real HTTP connections where possible
- Validate end-to-end workflows
- Include timeout and error scenarios

### Performance Tests
- Measure response times for critical paths
- Test with large files and datasets
- Validate memory usage patterns
- Ensure scalability across IDE versions

## Testing Standards

### Code Coverage
- Aim for high coverage on public APIs
- Focus on critical business logic
- Don't test trivial getters/setters
- Prioritize edge cases over happy path redundancy

### Test Quality
- Tests should be independent and isolated
- Use descriptive test names that explain behavior
- Follow the arrange-act-assert pattern
- Clean up resources in teardown methods

### Error Testing
- Test all error conditions and recovery paths
- Validate proper error messages and logging
- Test network failures and timeouts
- Ensure graceful degradation

## Continuous Integration

### Precommit Hook
- Runs all tests before allowing commits
- Validates MCP server syntax
- Builds plugin to catch compilation issues
- Prevents broken code from entering the repository

### GitHub Actions
- Executes a full test suite on push/PR
- Tests against a clean environment
- Generates test reports and artifacts
- Validates both plugin and MCP components

## Development Workflow

### Adding New Features
1. Write failing tests first (TDD)
2. Implement minimum code to pass tests
3. Refactor while keeping tests green
4. Add integration tests for complex workflows

### Debugging Test Failures
1. Run tests locally to reproduce issues
2. Use IDE debugger for step-through analysis
3. Check test logs for detailed error information
4. Validate test assumptions and mocks

### Maintaining Tests
- Update tests when changing APIs
- Remove obsolete tests for deprecated features
- Keep test data and fixtures minimal
- Review test performance regularly

## Best Practices

### Do
- Test behavior, not implementation
- Use meaningful assertions with clear messages
- Mock external dependencies consistently
- Write tests that document expected behavior

### Don't
- Test private methods directly
- Create brittle tests tied to implementation details
- Ignore flaky tests - fix them immediately
- Skip testing error conditions

## IDE-Specific Considerations

### JetBrains Platform Testing
- Use IntelliJ Platform test framework for complex IDE interactions
- Mock highlighting API calls for unit tests
- Test against multiple IDE versions when possible
- Validate plugin descriptor and configuration

### MCP Protocol Testing
- Test tool registration and invocation
- Validate JSON serialization/deserialization
- Test network communication patterns
- Ensure protocol compliance