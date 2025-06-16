#!/bin/bash

# Automated IDE Lifecycle Testing Script for JetBrains Inspection API
# This script automates the entire testing process without human interaction

set -e

# Configuration (source from CLAUDE.local.md if exists)
if [ -f "CLAUDE.local.md" ]; then
    IDE_TYPE=$(grep "IDE_TYPE=" CLAUDE.local.md | cut -d'"' -f2 || echo "PyCharm")
    IDE_VERSION=$(grep "IDE_VERSION=" CLAUDE.local.md | cut -d'"' -f2 || echo "2025.1")
    IDE_PORT=$(grep "IDE_PORT=" CLAUDE.local.md | cut -d'"' -f2 || echo "63341")
    TEST_PROJECT_PATH=$(grep "TEST_PROJECT_PATH=" CLAUDE.local.md | cut -d'"' -f2 || echo "")
    PLUGIN_DIR=$(grep "PLUGIN_DIR=" CLAUDE.local.md | cut -d'"' -f2 || echo "$HOME/Library/Application Support/JetBrains/${IDE_TYPE}${IDE_VERSION}/plugins")
else
    # Default configuration
    IDE_TYPE="PyCharm"
    IDE_VERSION="2025.1"
    IDE_PORT="63341"
    TEST_PROJECT_PATH=""  # Must be set in CLAUDE.local.md
    PLUGIN_DIR="$HOME/Library/Application Support/JetBrains/${IDE_TYPE}${IDE_VERSION}/plugins"
fi


echo "🤖 Automated IDE Lifecycle Testing"
echo "=================================="
echo ""

# Validate configuration
if [ -z "$TEST_PROJECT_PATH" ]; then
    echo "❌ ERROR: TEST_PROJECT_PATH not set"
    echo ""
    echo "Please create CLAUDE.local.md from CLAUDE.local.template.md"
    echo "and set your test project path."
    exit 1
fi

if [ ! -d "$TEST_PROJECT_PATH" ]; then
    echo "❌ ERROR: Test project not found at: $TEST_PROJECT_PATH"
    exit 1
fi

echo "Configuration:"
echo "  IDE: $IDE_TYPE $IDE_VERSION"
echo "  Port: $IDE_PORT"
echo "  Project: $TEST_PROJECT_PATH"
echo "  Plugin Dir: $PLUGIN_DIR"
echo ""

# Function to check if IDE is running
is_ide_running() {
    pgrep -f "$IDE_TYPE" > /dev/null 2>&1
}

# Function to wait for API to be ready
wait_for_api() {
    echo "⏳ Waiting for API to be ready..."
    for i in {1..60}; do
        if curl -s "http://localhost:$IDE_PORT/api/inspection/problems" > /dev/null 2>&1; then
            echo "✅ API is responding"
            return 0
        fi
        if [ "$i" -eq 60 ]; then
            echo "❌ API not responding after 60 seconds"
            return 1
        fi
        echo -n "."
        sleep 1
    done
}

# Function to run API tests
run_api_tests() {
    echo ""
    echo "🧪 Running API Tests"
    echo "==================="
    
    # First trigger an inspection
    echo ""
    echo "📍 Triggering Inspection..."
    TRIGGER_RESPONSE=$(curl -s "http://localhost:$IDE_PORT/api/inspection/trigger")
    echo "   Response: $(echo "$TRIGGER_RESPONSE" | jq -r '.message // "triggered"')"
    
    # Wait for inspection to complete
    echo "⏳ Waiting for inspection to complete..."
    for i in {1..30}; do
        STATUS=$(curl -s "http://localhost:$IDE_PORT/api/inspection/status")
        IS_SCANNING=$(echo "$STATUS" | jq -r '.is_scanning // true')
        HAS_RESULTS=$(echo "$STATUS" | jq -r '.has_inspection_results // false')
        
        if [ "$IS_SCANNING" = "false" ] && [ "$HAS_RESULTS" = "true" ]; then
            echo "✅ Inspection complete"
            break
        fi
        
        if [ "$i" -eq 30 ]; then
            echo ""
            echo "⚠️  Inspection status check timed out after 30 seconds"
            echo "   Continuing with tests anyway..."
        else
            sleep 1
            echo -n "."
        fi
    done
    echo ""
    
    # Test 1: Get inspection results
    echo ""
    echo "📍 Test 1: Inspection Results"
    RESPONSE=$(curl -s "http://localhost:$IDE_PORT/api/inspection/problems?severity=all")
    METHOD=$(echo "$RESPONSE" | jq -r '.method // "unknown"')
    TOTAL_PROBLEMS=$(echo "$RESPONSE" | jq -r '.total_problems // 0')
    
    echo "   Method: $METHOD"
    echo "   Total Problems: $TOTAL_PROBLEMS"
    
    # Test 2: Check inspection status
    echo ""
    echo "📍 Test 2: Inspection Status"
    STATUS=$(curl -s "http://localhost:$IDE_PORT/api/inspection/status")
    HAS_RESULTS=$(echo "$STATUS" | jq -r '.has_inspection_results // false')
    IS_SCANNING=$(echo "$STATUS" | jq -r '.is_scanning // false')
    echo "   Has results: $HAS_RESULTS"
    echo "   Is scanning: $IS_SCANNING"
    
    # Test 3: Severity filtering
    echo ""
    echo "📍 Test 3: Severity Filtering"
    for severity in "error" "warning" "weak_warning" "info"; do
        count=$(curl -s "http://localhost:$IDE_PORT/api/inspection/problems?severity=$severity" | jq -r '.total_problems // 0')
        echo "   $severity: $count problems"
    done
    
    # Analysis
    echo ""
    echo "🔍 Results Analysis"
    echo "=================="
    
    if [ "$TOTAL_PROBLEMS" -gt 0 ]; then
        echo "✅ Found $TOTAL_PROBLEMS problems (method: $METHOD)"
        if [ "$HAS_RESULTS" = "true" ]; then
            echo "✅ Inspection results are available"
        fi
        return 0
    else
        echo "⚠️  No problems found"
        if [ "$HAS_RESULTS" = "false" ]; then
            echo "   No inspection results available yet"
            echo "   Try triggering inspection with: curl http://localhost:$IDE_PORT/api/inspection/trigger"
        fi
        return 1
    fi
}

# Step 1: Build plugin
echo "🔨 Step 1: Building plugin..."
JAVA_HOME=$(/usr/libexec/java_home -v 21)
export JAVA_HOME
./gradlew buildPlugin
if ! ./gradlew buildPlugin; then
    echo "❌ Build failed!"
    exit 1
fi
echo "✅ Plugin built successfully"
echo ""

# Step 2: Kill any running IDE
if is_ide_running; then
    echo "🛑 Step 2: Stopping running $IDE_TYPE instance..."
    pkill -f "$IDE_TYPE"
    sleep 5
    
    # Force kill if still running
    if is_ide_running; then
        echo "   Force killing..."
        pkill -9 -f "$IDE_TYPE"
        sleep 3
    fi
    echo "✅ IDE stopped"
else
    echo "ℹ️  Step 2: No running $IDE_TYPE instance found"
fi
echo ""

# Step 3: Install plugin
echo "📦 Step 3: Installing plugin..."
PLUGIN_ZIP=$(find build/distributions -name "jetbrains-inspection-api-*.zip" | head -n 1)
if [ -z "$PLUGIN_ZIP" ]; then
    echo "❌ Plugin ZIP not found in build/distributions/"
    exit 1
fi

# Create plugin directory if it doesn't exist
mkdir -p "$PLUGIN_DIR"

# Extract plugin
PLUGIN_NAME="jetbrains-inspection-api"
rm -rf "${PLUGIN_DIR:?}/$PLUGIN_NAME"
unzip -q "$PLUGIN_ZIP" -d "$PLUGIN_DIR/"
echo "✅ Plugin installed to $PLUGIN_DIR"
echo ""

# Step 4: Start IDE with project
echo "🚀 Step 4: Starting $IDE_TYPE with project..."
if [ -z "$TEST_PROJECT_PATH" ] || [ ! -d "$TEST_PROJECT_PATH" ]; then
    echo "❌ Test project path not found: $TEST_PROJECT_PATH"
    echo "   Please set TEST_PROJECT_PATH in CLAUDE.local.md"
    exit 1
fi

# Start IDE in background
open -a "$IDE_TYPE" "$TEST_PROJECT_PATH"
echo "✅ IDE started"
echo ""

# Step 5: Wait for API and run tests
echo "⏳ Step 5: Waiting for IDE to be ready..."
echo "   (This may take 30-60 seconds for indexing...)"

if wait_for_api; then
    # Give IDE a bit more time to fully index
    echo "⏳ Waiting additional 15 seconds for project indexing..."
    sleep 15
    
    # Run tests
    run_api_tests
    TEST_RESULT=$?
    
    # If no problems detected, try once more after waiting
    if [ "$TEST_RESULT" -ne 0 ]; then
        echo ""
        echo "🔄 Retrying after additional wait..."
        sleep 15
        run_api_tests
        TEST_RESULT=$?
    fi
else
    echo "❌ Failed to connect to API"
    TEST_RESULT=1
fi

echo ""
echo "🏁 Test Summary"
echo "=============="
if [ $TEST_RESULT -eq 0 ]; then
    echo "✅ All tests passed!"
    echo "   The comprehensive inspection framework is working correctly"
else
    echo "❌ Tests failed!"
    echo "   Check the results above for details"
fi

echo ""
echo "💡 Next Steps:"
echo "   - IDE is still running for manual inspection"
echo "   - To stop IDE: pkill -f $IDE_TYPE"
echo "   - To see logs: tail -f ~/Library/Logs/JetBrains/${IDE_TYPE}${IDE_VERSION}/idea.log"
echo "   - To re-run tests: ./test-automated.sh"

exit $TEST_RESULT