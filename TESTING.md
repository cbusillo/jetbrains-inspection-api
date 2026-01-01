# Testing

This project has two test surfaces:

- **Plugin (Kotlin/Gradle)**: HTTP handler and inspection extraction/trigger logic.
- **MCP server (JVM)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 (required for Gradle builds)

If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
JDK 21 path before running the scripts.

In the Code sandbox, Gradle may need escalated permissions. If you see
"Operation not permitted" from NativeServices, re-run the command with
escalation.

## Local commands

```bash
# Plugin tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# MCP server tests
./gradlew :mcp-server-jvm:test

# MCP server jar
./gradlew :mcp-server-jvm:mcpServerJar

# Everything (plugin tests + MCP tests + plugin build)
./scripts/test-all.sh
```

## Automated IDE smoke test

`./scripts/test-automated.sh` can install the plugin into a local IDE, start it with a
test project, and hit a few API endpoints.

- Configure your machine in `AGENTS.local.md` (copy from `AGENTS.local.template.md`).

## CI

GitHub Actions runs on version tags (`v*`) via `.github/workflows/release.yml`:

- `./gradlew test`
- `./gradlew :mcp-server-jvm:test`
- `mcp-server-jvm` build
- `./gradlew buildPlugin`
