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
JAVA_HOME_21="${JAVA_HOME_21:-}"

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

urlencode() {
    printf '%s' "$1" | jq -sRr @uri
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
if [ -f "$TEST_PROJECT_PATH/.idea/.name" ]; then
    PROJECT_NAME=$(head -n 1 "$TEST_PROJECT_PATH/.idea/.name" | tr -d '\r')
else
    PROJECT_NAME=$(basename "$TEST_PROJECT_PATH")
fi
echo "  Project Name: $PROJECT_NAME"
echo ""

# Function to check if IDE is running
is_ide_running() {
    pgrep -f "$IDE_TYPE" > /dev/null 2>&1
}

# Route discovery overrides the static IDE_PORT from local config. JetBrains can
# choose a different built-in server port after restart, especially when other
# JetBrains products are already running.
IDE_PORT_RANGE="${JETBRAINS_INSPECTION_PORTS:-63340 63341 63342 63343 63344 63345 63346 63347 63348 63349}"
INSPECTION_API_PATH="/api/inspection"
PROJECT_PATH_ENCODED=$(urlencode "$TEST_PROJECT_PATH")
PROJECT_PARAM="project_path=$PROJECT_PATH_ENCODED&worktree_path=$PROJECT_PATH_ENCODED"

port_candidates() {
    printf '%s\n%s\n' "$IDE_PORT" "$IDE_PORT_RANGE" | awk '
        BEGIN { FS = "[,[:space:]]+" }
        {
            for (idx = 1; idx <= NF; idx++) {
                token = $idx
                if (token ~ /^[0-9]+$/) {
                    if (!seen[token]++) print token
                    continue
                }
                if (token !~ /^[0-9]+-[0-9]+$/) continue
                split(token, range, "-")
                start = range[1] + 0
                end = range[2] + 0
                if (start > end) continue
                for (port = start; port <= end; port++) {
                    if (!seen[port]++) print port
                }
            }
        }
    '
}

inspection_url() {
    local port="$1"
    local endpoint="$2"
    local params="${3:-}"
    if [ -n "$params" ]; then
        printf 'http://localhost:%s%s/%s?%s' "$port" "$INSPECTION_API_PATH" "$endpoint" "$params"
    else
        printf 'http://localhost:%s%s/%s' "$port" "$INSPECTION_API_PATH" "$endpoint"
    fi
}

api_get() {
    local port="$1"
    local endpoint="$2"
    local params="${3:-}"
    local timeout="${4:-5}"
    curl -s --max-time "$timeout" "$(inspection_url "$port" "$endpoint" "$params")"
}

api_get_current() {
    local endpoint="$1"
    local params="${2:-}"
    local timeout="${3:-5}"
    api_get "$IDE_PORT" "$endpoint" "$params" "$timeout"
}

normalize_ide_text() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d ' _-'
}

identity_matches_ide() {
    local identity="$1"
    local requested
    local ide_name
    local product_code

    requested=$(normalize_ide_text "$IDE_TYPE")
    ide_name=$(echo "$identity" | jq -r '.ide_name // .name // ""')
    product_code=$(echo "$identity" | jq -r '.ide_product_code // .product_code // ""')
    ide_name=$(normalize_ide_text "$ide_name")
    product_code=$(normalize_ide_text "$product_code")

    case "$requested" in
        pycharm|pycharmce)
            [ "${ide_name#*pycharm}" != "$ide_name" ] || [ "$product_code" = "py" ] || [ "$product_code" = "pc" ]
            ;;
        intellijidea|intellij|idea)
            [ "${ide_name#*intellij}" != "$ide_name" ] || [ "$product_code" = "iu" ] || [ "$product_code" = "ic" ]
            ;;
        webstorm)
            [ "${ide_name#*webstorm}" != "$ide_name" ] || [ "$product_code" = "ws" ]
            ;;
        *)
            case "$ide_name" in
                *"$requested"*) return 0 ;;
                *) return 1 ;;
            esac
            ;;
    esac
}

route_matches_project() {
    local route="$1"
    local base_path
    local project_file_path

    base_path=$(echo "$route" | jq -r '.base_path // ""')
    project_file_path=$(echo "$route" | jq -r '.project_file_path // ""')

    if [ "$base_path" = "$TEST_PROJECT_PATH" ]; then
        return 0
    fi
    case "$project_file_path" in
        "$TEST_PROJECT_PATH"/*) return 0 ;;
        *) return 1 ;;
    esac
}

discover_project_route() {
    local identity
    local route_response
    local route
    local route_error
    local matched_ide_count=0
    local best_status=""

    for port in $(port_candidates); do
        identity=$(api_get "$port" identity || true)
        if ! echo "$identity" | jq -e '.port // .ide_name // .name' > /dev/null 2>&1; then
            continue
        fi
        if ! identity_matches_ide "$identity"; then
            continue
        fi
        matched_ide_count=$((matched_ide_count + 1))
        route_response=$(api_get "$port" route "$PROJECT_PARAM" || true)
        route_error=$(echo "$route_response" | jq -r '.error // empty' 2>/dev/null || true)
        route=$(echo "$route_response" | jq -c '.route // empty' 2>/dev/null || true)
        if [ -n "$route" ] && route_matches_project "$route"; then
            IDE_PORT=$(echo "$route" | jq -r '.port // empty')
            if [ -z "$IDE_PORT" ]; then
                IDE_PORT="$port"
            fi
            PROJECT_NAME=$(echo "$route" | jq -r '.project_name // empty')
            if [ -z "$PROJECT_NAME" ]; then
                PROJECT_NAME=$(basename "$TEST_PROJECT_PATH")
            fi
            echo "✅ Project route found: $PROJECT_NAME on port $IDE_PORT"
            return 0
        fi
        if [ -n "$route_error" ]; then
            best_status="port $port: $route_error"
        else
            best_status="port $port: route not found for $TEST_PROJECT_PATH"
        fi
    done

    if [ "$matched_ide_count" -eq 0 ]; then
        echo "❌ No $IDE_TYPE inspection plugin identity found on scanned ports"
    else
        echo "❌ $IDE_TYPE is responding, but no route matched the test project"
        if [ -n "$best_status" ]; then
            echo "   Last route status: $best_status"
        fi
    fi
    return 1
}

wait_for_api() {
    echo "⏳ Waiting for API to be ready..."
    for i in {1..90}; do
        if discover_project_route; then
            echo "✅ API route is responding on port $IDE_PORT"
            return 0
        fi
        if [ "$i" -eq 90 ]; then
            echo "❌ API route not responding after 90 seconds"
            return 1
        fi
        echo -n "."
        sleep 1
    done
}

wait_for_project_ready() {
    echo "⏳ Waiting for project to be ready..."
    INDEXING=""
    IS_SCANNING=""
    for i in {1..240}; do
        if [ "$i" -eq 1 ] || [ $((i % 15)) -eq 0 ]; then
            if ! discover_project_route; then
                INDEXING=""
                IS_SCANNING=""
                sleep 1
                continue
            fi
        fi
        STATUS=$(api_get_current status "$PROJECT_PARAM")
        STATUS_ERROR=$(echo "$STATUS" | jq -r '.error // empty')
        PROJECT_READY=$(echo "$STATUS" | jq -r '.project_name // empty')
        INDEXING=$(echo "$STATUS" | jq -r '.indexing // false')
        IS_SCANNING=$(echo "$STATUS" | jq -r '.is_scanning // false')
        if [ -n "$PROJECT_READY" ] && [ -z "$STATUS_ERROR" ]; then
            echo "✅ Project ready: $PROJECT_READY"
            if [ "$INDEXING" = "true" ] || [ "$IS_SCANNING" = "true" ]; then
                echo "   Project is still indexing/scanning; inspection wait will handle completion."
            fi
            return 0
        fi
        if [ "$i" -eq 240 ]; then
            echo "❌ Project not ready after 240 seconds"
            echo "   Last checked port: $IDE_PORT"
            if [ -n "$STATUS_ERROR" ]; then
                echo "   Last error: $STATUS_ERROR"
            fi
            if [ "$INDEXING" = "true" ] || [ "$IS_SCANNING" = "true" ]; then
                echo "   Last status: project route present but indexing=$INDEXING is_scanning=$IS_SCANNING"
            fi
            return 1
        fi
        sleep 1
    done
}

run_api_tests() {
    echo ""
    echo "🧪 Running API Tests"
    echo "==================="

    echo ""
    echo "📍 Triggering Inspection..."
    TRIGGER_RESPONSE=$(api_get_current trigger "$PROJECT_PARAM")
    TRIGGER_ERROR=$(echo "$TRIGGER_RESPONSE" | jq -r '.error // empty')
    if [ -n "$TRIGGER_ERROR" ]; then
        echo "❌ Trigger failed: $TRIGGER_ERROR"
        return 1
    fi
    echo "   Response: $(echo "$TRIGGER_RESPONSE" | jq -r '.message // "triggered"')"

    echo "⏳ Waiting for inspection to complete..."
    WAIT_TIMEOUT_MS=180000
    WAIT_CLIENT_TIMEOUT=$((WAIT_TIMEOUT_MS / 1000 + 10))
    WAIT_RESPONSE=$(api_get_current wait "$PROJECT_PARAM&timeout_ms=$WAIT_TIMEOUT_MS&poll_ms=1000" "$WAIT_CLIENT_TIMEOUT")
    WAIT_COMPLETED=$(echo "$WAIT_RESPONSE" | jq -r '.wait_completed // false')
    WAIT_REASON=$(echo "$WAIT_RESPONSE" | jq -r '.completion_reason // "unknown"')
    WAIT_TIMED_OUT=$(echo "$WAIT_RESPONSE" | jq -r '.timed_out // false')
    WAIT_NOTE=$(echo "$WAIT_RESPONSE" | jq -r '.wait_note // empty')

    if [ "$WAIT_COMPLETED" = "true" ]; then
        echo "✅ Inspection wait completed ($WAIT_REASON)"
    elif [ "$WAIT_TIMED_OUT" = "true" ]; then
        WAIT_TIMEOUT_S=$((WAIT_TIMEOUT_MS / 1000))
        echo "⚠️  Inspection wait timed out after ${WAIT_TIMEOUT_S}s"
        echo "   Continuing with tests anyway..."
    else
        echo "⚠️  Inspection wait did not complete"
        echo "   Continuing with tests anyway..."
    fi
    if [ -n "$WAIT_NOTE" ]; then
        echo "   Note: $WAIT_NOTE"
    fi

    echo ""
    echo "📍 Test 1: Inspection Results"
    RESPONSE=$(api_get_current problems "$PROJECT_PARAM&severity=all")
    PROBLEMS_STATUS=$(echo "$RESPONSE" | jq -r '.status // "unknown"')
    METHOD=$(echo "$RESPONSE" | jq -r '.method // "unknown"')
    TOTAL_PROBLEMS=$(echo "$RESPONSE" | jq -r '.total_problems // 0')

    echo "   Status: $PROBLEMS_STATUS"
    echo "   Method: $METHOD"
    echo "   Total Problems: $TOTAL_PROBLEMS"

    echo ""
    echo "📍 Test 2: Inspection Status"
    STATUS=$(api_get_current status "$PROJECT_PARAM")
    HAS_RESULTS=$(echo "$STATUS" | jq -r '.has_inspection_results // false')
    IS_SCANNING=$(echo "$STATUS" | jq -r '.is_scanning // false')
    CLEAN_INSPECTION=$(echo "$STATUS" | jq -r '.clean_inspection // false')
    STATUS_ERROR=$(echo "$STATUS" | jq -r '.error // empty')
    if [ -n "$STATUS_ERROR" ]; then
        echo "   Error: $STATUS_ERROR"
        return 1
    fi
    echo "   Has results: $HAS_RESULTS"
    echo "   Is scanning: $IS_SCANNING"
    echo "   Clean inspection: $CLEAN_INSPECTION"

    echo ""
    echo "📍 Test 3: Severity Filtering"
    for severity in "error" "warning" "weak_warning" "info"; do
        count=$(api_get_current problems "$PROJECT_PARAM&severity=$severity" | jq -r '.total_problems // 0')
        echo "   $severity: $count problems"
    done

    echo ""
    echo "🔍 Results Analysis"
    echo "=================="

    if [ "$TOTAL_PROBLEMS" -gt 0 ]; then
        echo "✅ Found $TOTAL_PROBLEMS problems (method: $METHOD)"
        if [ "$HAS_RESULTS" = "true" ]; then
            echo "✅ Inspection results are available"
        fi
        return 0
    fi

    if [ "$WAIT_COMPLETED" = "true" ] && \
        { [ "$WAIT_REASON" = "clean" ] || [ "$CLEAN_INSPECTION" = "true" ]; }; then
        echo "✅ Inspection completed with no problems ($WAIT_REASON)"
        return 0
    fi

    if [ "$WAIT_REASON" = "no_results" ]; then
        echo "❌ Inspection finished but no results were captured"
        echo "   Open the Inspection Results tool window and retry."
        return 1
    fi

    echo "⚠️  Inspection did not prove GREEN or RED"
    if [ "$HAS_RESULTS" = "false" ]; then
        echo "   No inspection results available yet"
        echo "   Try triggering inspection through port $IDE_PORT after the IDE finishes indexing."
    else
        echo "   The API returned zero actionable findings, but clean was not confirmed."
    fi
    return 1
}

# Step 1: Build plugin
echo "🔨 Step 1: Building plugin..."
JAVA_HOME=$(resolve_java_home) || {
    echo "❌ ERROR: Java 21 not found. Please set JAVA_HOME_21."
    exit 1
}

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
    if ! wait_for_project_ready; then
        TEST_RESULT=1
    else
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
echo "   - To re-run tests: ./scripts/test-automated.sh"

exit $TEST_RESULT
