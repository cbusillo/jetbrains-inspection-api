#!/bin/bash
# Build script with Java 21

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
./gradlew test
./gradlew buildPlugin "$@"