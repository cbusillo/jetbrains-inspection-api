#!/usr/bin/env bash

# Comprehensive Test Suite Runner for JetBrains Inspection API
# Runs all tests (plugin + MCP) with coverage analysis

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

echo "JetBrains Inspection API - Comprehensive Test Suite"
echo "===================================================="
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
  echo "-----------------------------------------------------"
  echo "  $1"
  echo "-----------------------------------------------------"
  echo ""
}

# Function to print result
print_result() {
  if [ "$1" -eq 0 ]; then
    echo -e "${GREEN}[OK] $2 PASSED${NC}"
  else
    echo -e "${RED}[FAIL] $2 FAILED${NC}"
    OVERALL_RESULT=1
  fi
}

# Step 1: Plugin Tests
print_section "1. Running Kotlin Plugin Tests with Coverage"

java_major() {
  local java_bin="$1/bin/java"
  if [ ! -x "$java_bin" ]; then
    return 1
  fi
  local major
  major=$("$java_bin" -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')
  if [ "$major" = "1" ]; then
    major=$("$java_bin" -version 2>&1 | awk -F'[".]' '/version/ {print $3; exit}')
  fi
  echo "$major"
}

is_java21() {
  [ "$(java_major "$1")" = "21" ]
}

resolve_java_home() {
  local candidate

  if [ -n "${JAVA_HOME_21:-}" ]; then
    if is_java21 "$JAVA_HOME_21"; then
      echo "$JAVA_HOME_21"
      return 0
    fi
    echo "ERROR: JAVA_HOME_21 is set but not Java 21." >&2
    return 1
  fi

  if [ -n "${JAVA_HOME:-}" ] && is_java21 "$JAVA_HOME"; then
    echo "$JAVA_HOME"
    return 0
  fi

  if [[ "$OSTYPE" == "darwin"* ]]; then
    candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    if [ -n "$candidate" ] && is_java21 "$candidate"; then
      echo "$candidate"
      return 0
    fi
  else
    for candidate in /usr/lib/jvm/java-21-* /usr/lib/jvm/java-21 /usr/lib/jvm/jdk-21*; do
      if [ -d "$candidate" ] && is_java21 "$candidate"; then
        echo "$candidate"
        return 0
      fi
    done
  fi

  return 1
}

JAVA_HOME=$(resolve_java_home) || {
  echo "ERROR: Java 21 not found. Please set JAVA_HOME_21 environment variable." >&2
  exit 1
}

export JAVA_HOME

echo "Running plugin tests..."
if ./gradlew test jacocoTestReport; then
  PLUGIN_TEST_RESULT=0
  echo ""
  echo "Coverage Report:"
  echo "  HTML: build/reports/jacoco/test/html/index.html"
  echo "  XML:  build/reports/jacoco/test/jacocoTestReport.xml"
else
  PLUGIN_TEST_RESULT=1
fi

print_result $PLUGIN_TEST_RESULT "Plugin tests"

# Step 2: Core Tests
print_section "2. Running Core Tests (JVM)"

echo "Running core tests..."
if ./gradlew :inspection-core:test :inspection-core:jacocoTestReport; then
  CORE_TEST_RESULT=0
else
  CORE_TEST_RESULT=1
fi

print_result $CORE_TEST_RESULT "Core tests"

# Step 3: MCP Server Tests
print_section "3. Running MCP Server Tests (JVM)"

echo "Running MCP server tests..."
if ./gradlew :mcp-server-jvm:test :mcp-server-jvm:jacocoTestReport :mcp-server-jvm:mcpServerJar; then
  MCP_TEST_RESULT=0
else
  MCP_TEST_RESULT=1
fi

print_result $MCP_TEST_RESULT "MCP server tests"

# Step 4: Build Verification
print_section "4. Build Verification"

echo "Building plugin..."
if ./gradlew buildPlugin; then
  BUILD_RESULT=0
  PLUGIN_FILE=""
  if [ -d build/distributions ]; then
    shopt -s nullglob
    plugin_files=(build/distributions/jetbrains-inspection-api-*.zip)
    shopt -u nullglob
    if [ ${#plugin_files[@]} -gt 0 ]; then
      PLUGIN_FILE=$(ls -t "${plugin_files[@]}" | head -n 1)
    fi
  fi
  if [ -n "$PLUGIN_FILE" ]; then
    FILE_SIZE=$(du -h "$PLUGIN_FILE" | cut -f1)
    echo "  Plugin: $PLUGIN_FILE ($FILE_SIZE)"
  fi
else
  BUILD_RESULT=1
fi

print_result $BUILD_RESULT "Plugin build"

# Step 5: Coverage Verification
print_section "5. Coverage Verification"

echo "Checking plugin test coverage..."
if ./gradlew jacocoTestCoverageVerification; then
  PLUGIN_COVERAGE_RESULT=0
else
  PLUGIN_COVERAGE_RESULT=1
  echo -e "${YELLOW}WARNING: Coverage is below 80% threshold${NC}"
fi

# Step 6: Core Coverage Verification
print_section "6. Core Coverage Verification"

echo "Checking core test coverage..."
if ./gradlew :inspection-core:jacocoTestCoverageVerification; then
  CORE_COVERAGE_RESULT=0
else
  CORE_COVERAGE_RESULT=1
  echo -e "${YELLOW}WARNING: Core coverage is below 85% threshold${NC}"
fi

# Step 7: MCP Coverage Verification
print_section "7. MCP Coverage Verification"

echo "Checking MCP server test coverage..."
if ./gradlew :mcp-server-jvm:jacocoTestCoverageVerification; then
  MCP_COVERAGE_RESULT=0
else
  MCP_COVERAGE_RESULT=1
  echo -e "${YELLOW}WARNING: MCP coverage is below 80% threshold${NC}"
fi

# Summary
print_section "Test Summary"

echo "Test Results:"
print_result $PLUGIN_TEST_RESULT "  Plugin tests"
print_result $CORE_TEST_RESULT "  Core tests"
print_result $MCP_TEST_RESULT "  MCP server tests"
print_result $BUILD_RESULT "  Plugin build"
print_result $PLUGIN_COVERAGE_RESULT "  Plugin coverage threshold"
print_result $CORE_COVERAGE_RESULT "  Core coverage threshold"
print_result $MCP_COVERAGE_RESULT "  MCP coverage threshold"

echo ""
if [ $OVERALL_RESULT -eq 0 ]; then
  echo -e "${GREEN}All tests passed!${NC}"
else
  echo -e "${RED}Some tests failed!${NC}"
fi

echo ""
echo "Reports Generated:"
echo "  - Plugin coverage: build/reports/jacoco/test/html/index.html"
echo "  - Core coverage:   inspection-core/build/reports/jacoco/test/html/index.html"
echo "  - MCP coverage:    mcp-server-jvm/build/reports/jacoco/test/html/index.html"
echo "  - MCP jar:         mcp-server-jvm/build/libs/jetbrains-inspection-mcp.jar"
echo "  - Test results:    build/reports/tests/test/index.html"

exit $OVERALL_RESULT
