# Testing

This project has two test surfaces:

- **Plugin (Kotlin/Gradle)**: HTTP handler + inspection extraction/trigger logic.
- **MCP server (Node)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 (required for Gradle plugin builds)
- Node.js 18+

## Local commands

```bash
# Plugin tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# MCP server tests
cd mcp-server && npm test

# Everything (plugin tests + MCP tests + build)
./test-all.sh
```

## Automated IDE smoke test

`./test-automated.sh` can install the plugin into a local IDE, start it with a
test project, and hit a few API endpoints.

- Configure your machine in `AGENTS.local.md` (copy from `AGENTS.local.template.md`).

## CI

GitHub Actions runs on version tags (`v*`) via `.github/workflows/release.yml`:

- `./gradlew test`
- `mcp-server` tests
- `./gradlew buildPlugin`
