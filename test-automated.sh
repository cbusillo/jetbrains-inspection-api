#!/bin/bash

# Automated IDE Lifecycle Testing Script for JetBrains Inspection API
# This script automates the entire testing process without human interaction

set -e

# Configuration (source from AGENTS.local.md if present)

IDE_TYPE="PyCharm"
IDE_VERSION=""
IDE_PORT="63341"
TEST_PROJECT_PATH=""  # Must be set in AGENTS.local.md
PLUGIN_DIR=""
JAVA_HOME_21=""

if [ -f "AGENTS.local.md" ]; then
    IDE_TYPE=$(grep "IDE_TYPE=" AGENTS.local.md | cut -d'"' -f2 || echo "$IDE_TYPE")
    IDE_VERSION=$(grep "IDE_VERSION=" AGENTS.local.md | cut -d'"' -f2 || echo "$IDE_VERSION")
    IDE_PORT=$(grep "IDE_PORT=" AGENTS.local.md | cut -d'"' -f2 || echo "$IDE_PORT")
    TEST_PROJECT_PATH=$(grep "TEST_PROJECT_PATH=" AGENTS.local.md | cut -d'"' -f2 || echo "$TEST_PROJECT_PATH")
    PLUGIN_DIR=$(grep "PLUGIN_DIR=" AGENTS.local.md | cut -d'"' -f2 || echo "$PLUGIN_DIR")
    if [ -z "$JAVA_HOME_21" ]; then
        JAVA_HOME_21=$(grep "JAVA_HOME_21=" AGENTS.local.md | cut -d'"' -f2 || echo "$JAVA_HOME_21")
    fi
fi

if [ -z "$PLUGIN_DIR" ]; then
    base="$HOME/Library/Application Support/JetBrains"

    if [ -n "$IDE_VERSION" ]; then
        PLUGIN_DIR="$base/${IDE_TYPE}${IDE_VERSION}/plugins"
    else
        ide_home=$(ls -dt "$base/${IDE_TYPE}"* 2>/dev/null | head -n 1)
        if [ -n "$ide_home" ]; then
            PLUGIN_DIR="$ide_home/plugins"
        else
            PLUGIN_DIR="$base/${IDE_TYPE}/plugins"
        fi
    fi
fi


echo "🤖 Automated IDE Lifecycle Testing"
echo "=================================="
echo ""

# Validate configuration
if [ -z "$TEST_PROJECT_PATH" ]; then
    echo "❌ ERROR: TEST_PROJECT_PATH not set"
    echo ""
    echo "Please create AGENTS.local.md from AGENTS.local.template.md"
    echo "and set your test project path."
    exit 1
fi

if [ ! -d "$TEST_PROJECT_PATH" ]; then
    echo "❌ ERROR: Test project not found at: $TEST_PROJECT_PATH"
    exit 1
fi

echo "Configuration:"
echo "  IDE: $IDE_TYPE${IDE_VERSION:+ $IDE_VERSION}"
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

    # Wait for inspection to complete (long-poll)
    echo "⏳ Waiting for inspection to complete..."
    WAIT_RESPONSE=$(curl -s "http://localhost:$IDE_PORT/api/inspection/wait?timeout_ms=180000&poll_ms=1000")
    WAIT_COMPLETED=$(echo "$WAIT_RESPONSE" | jq -r '.wait_completed // false')
    WAIT_REASON=$(echo "$WAIT_RESPONSE" | jq -r '.completion_reason // "unknown"')
    WAIT_TIMED_OUT=$(echo "$WAIT_RESPONSE" | jq -r '.timed_out // false')

    if [ "$WAIT_COMPLETED" = "true" ]; then
        echo "✅ Inspection wait completed ($WAIT_REASON)"
    elif [ "$WAIT_TIMED_OUT" = "true" ]; then
        echo "⚠️  Inspection wait timed out after 120s"
        echo "   Continuing with tests anyway..."
    else
        echo "⚠️  Inspection wait did not complete"
        echo "   Continuing with tests anyway..."
    fi
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
if [ -n "$JAVA_HOME_21" ]; then
    JAVA_HOME="$JAVA_HOME_21"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "❌ ERROR: Java 21 not found. Please set JAVA_HOME_21."
    exit 1
fi

export JAVA_HOME
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
PLUGIN_ZIP=$(ls -t build/distributions/jetbrains-inspection-api-*.zip 2>/dev/null | head -n 1)
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
	    echo "   Please set TEST_PROJECT_PATH in AGENTS.local.md"
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
