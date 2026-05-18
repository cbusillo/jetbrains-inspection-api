# Testing

This project has three test surfaces:

- **Core (Kotlin/JVM)**: shared filtering and JSON helpers (no IDE dependencies).
- **Plugin (Kotlin/Gradle)**: HTTP handler and inspection extraction/trigger logic.
- **MCP server (JVM)**: tool wiring + URL/param handling + error behavior.

## Prerequisites

- Java 21 (required for Gradle builds)

If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
JDK 21 path before running the scripts.

In the Every Code sandbox, Gradle may need escalated permissions. If you see
"Operation not permitted" from NativeServices, re-run the command with
escalation.

## Local commands

```bash
# Plugin tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test

# Core tests
./gradlew :inspection-core:test

# MCP server tests
./gradlew :mcp-server-jvm:test

# Focused MCP auto-routing coverage lives in McpServerTest and covers
# inspection_list_projects, project_path routing, and duplicate-name ambiguity.

# MCP server jar
./gradlew :mcp-server-jvm:mcpServerJar

# Everything (plugin tests + MCP tests + plugin build)
./scripts/test-all.sh
```

## Automated IDE smoke test

`./scripts/test-automated.sh` can install the plugin into a local IDE,
start it with a test project, and hit a few API endpoints.

- Configure your machine in `AGENTS.local.md` (copy from `AGENTS.local.template.md`).

## Agent inspection helper

The external `jetbrains-inspection` skill uses `scripts/jb-inspect.py` as an
agent-facing wrapper around this plugin's HTTP API. Run it from the installed or
checked-out skill when validating behavior the agents rely on:

```bash
HELPER="${CODEX_HOME:-$HOME/.code}/skills/jetbrains-inspection/scripts/jb-inspect.py"
uv run "$HELPER" run \
  --repo "$PWD" \
  --scope changed_files
```

The helper treats `capture_incomplete`, stale results, timeouts, indexing,
session drift, and route ambiguity as non-clean outcomes. When this repo changes
inspection status semantics, route metadata, clean/capture classification, or
MCP tool response contracts, update the skill docs/tests/scripts in
the `jetbrains-inspection` skill as part of the same workstream.

## Local cleanup

`./scripts/clean-local.sh` removes disposable local files such as `.DS_Store`
files and old `tmp/*.log` test logs without deleting local configuration, agent
state, Gradle caches, IDE sandboxes, or build output.

## CI

GitHub Actions runs the commit gate on pull requests and pushes to `main` via
`.github/workflows/ci.yml`:

- `./gradlew test`
- `./gradlew :mcp-server-jvm:test`
- `./gradlew buildPlugin`

Version tags (`v*`) run `.github/workflows/release.yml`, which repeats the
commit gate before publishing to the JetBrains Marketplace and creating the
GitHub Release.
