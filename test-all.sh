#!/bin/bash

# Comprehensive Test Suite Runner for JetBrains Inspection API
# Runs all tests (plugin + MCP) with coverage analysis

set -e

echo "🧪 JetBrains Inspection API - Comprehensive Test Suite"
echo "====================================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Track overall results
OVERALL_RESULT=0

# Function to print test section
print_section() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  $1"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
}

# Function to print result
print_result() {
    if [ "$1" -eq 0 ]; then
        echo -e "${GREEN}✅ $2 PASSED${NC}"
    else
        echo -e "${RED}❌ $2 FAILED${NC}"
        OVERALL_RESULT=1
    fi
}

# Step 1: Plugin Tests
print_section "1. Running Kotlin Plugin Tests with Coverage"

# Detect Java 21 location based on OS
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null)
elif [ -n "$JAVA_HOME_21" ]; then
    # Use environment variable if set
    JAVA_HOME="$JAVA_HOME_21"
else
    # Try common Linux locations
    for dir in /usr/lib/jvm/java-21-* /usr/lib/jvm/java-21 /usr/lib/jvm/jdk-21*; do
        if [ -d "$dir" ]; then
            JAVA_HOME="$dir"
            break
        fi
    done
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "❌ ERROR: Java 21 not found. Please set JAVA_HOME_21 environment variable."
    exit 1
fi

export JAVA_HOME

echo "📍 Running plugin tests..."
if ./gradlew test jacocoTestReport; then
    PLUGIN_TEST_RESULT=0
    echo ""
    echo "📊 Coverage Report:"
    echo "   HTML: build/reports/jacoco/test/html/index.html"
    echo "   XML:  build/reports/jacoco/test/jacocoTestReport.xml"
else
    PLUGIN_TEST_RESULT=1
fi

print_result $PLUGIN_TEST_RESULT "Plugin tests"

# Step 2: MCP Server Tests
print_section "2. Running MCP Server Tests with Coverage"

echo "📍 Installing dependencies..."
cd mcp-server
npm install

echo ""
echo "📍 Running MCP server tests..."
if npm run test:coverage; then
    MCP_TEST_RESULT=0
    echo ""
    echo "📊 Coverage Report:"
    echo "   HTML: coverage/index.html"
    echo "   Text: See output above"
else
    MCP_TEST_RESULT=1
fi

print_result $MCP_TEST_RESULT "MCP server tests"
cd ..

# Step 3: Build Verification
print_section "3. Build Verification"

echo "📍 Building plugin..."
if ./gradlew buildPlugin; then
    BUILD_RESULT=0
    PLUGIN_FILE=$(find build/distributions -name "jetbrains-inspection-api-*.zip" | head -n 1)
    if [ -n "$PLUGIN_FILE" ]; then
        FILE_SIZE=$(du -h "$PLUGIN_FILE" | cut -f1)
        echo "   Plugin: $PLUGIN_FILE ($FILE_SIZE)"
    fi
else
    BUILD_RESULT=1
fi

print_result $BUILD_RESULT "Plugin build"

# Step 4: MCP Syntax Check
print_section "4. MCP Server Syntax Check"

echo "📍 Checking MCP server syntax..."
if node --check mcp-server/server.js; then
    SYNTAX_RESULT=0
else
    SYNTAX_RESULT=1
fi

print_result $SYNTAX_RESULT "MCP syntax check"

# Step 5: Coverage Verification
print_section "5. Coverage Verification"

echo "📍 Checking plugin test coverage..."
if ./gradlew jacocoTestCoverageVerification; then
    COVERAGE_RESULT=0
else
    COVERAGE_RESULT=1
    echo -e "${YELLOW}⚠️  Coverage is below 80% threshold${NC}"
fi

# Summary
print_section "📊 Test Summary"

echo "Test Results:"
print_result $PLUGIN_TEST_RESULT "  Plugin tests"
print_result $MCP_TEST_RESULT "  MCP server tests"
print_result $BUILD_RESULT "  Plugin build"
print_result $SYNTAX_RESULT "  MCP syntax"
print_result $COVERAGE_RESULT "  Coverage threshold"

echo ""
if [ $OVERALL_RESULT -eq 0 ]; then
    echo -e "${GREEN}✅ All tests passed!${NC}"
else
    echo -e "${RED}❌ Some tests failed!${NC}"
fi

echo ""
echo "📚 Reports Generated:"
echo "   - Plugin coverage: build/reports/jacoco/test/html/index.html"
echo "   - MCP coverage:    mcp-server/coverage/index.html"
echo "   - Test results:    build/reports/tests/test/index.html"

exit $OVERALL_RESULT