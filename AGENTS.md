## AGENTS.md

This repo is designed to be worked on with **Code** (the Codex CLI fork).

User-facing setup and API usage live in [README.md](README.md).

## Tooling

- Plugin: Kotlin/Gradle (JetBrains 2025.x), requires Java 21.
- MCP server: Kotlin/JVM (bundled in plugin, built via `mcp-server-jvm`).

## Dev loop (Code)

- Build plugin zip: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
- Run all tests: `./scripts/test-all.sh`
- Plugin unit tests only: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
- MCP server tests only: `./gradlew :mcp-server-jvm:test`
- MCP server jar only: `./gradlew :mcp-server-jvm:mcpServerJar`

Optional commit gate checks are in `scripts/commit-gate.sh` (see README for setup).

Notes:

- If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
  JDK 21 path (the IDE's bundled runtime is fine).
- In the Code sandbox, Gradle may need escalated permissions; if you see
  "Operation not permitted" from NativeServices, re-run with escalation.

## Local validation

- Enable the JetBrains IDE built-in server (see README for the current settings path).
- Smoke test endpoints:
  - `curl "http://localhost:<port>/api/inspection/status"`
  - `curl "http://localhost:<port>/api/inspection/trigger?scope=whole_project"`
  - `curl "http://localhost:<port>/api/inspection/problems?severity=warning&limit=1"`

## Conventions

- Prefer descriptive names to comments/docstrings; keep commentary minimal.
- Avoid stale docs: keep this file evergreen and link to README for specifics.
- Use `git mv` for file moves so history stays intact.

## Local-only overrides

If you need machine-specific paths/ports/log locations, copy `AGENTS.local.template.md` to `AGENTS.local.md`.
`AGENTS.local.md` is ignored in git.

## Release checklist

- Bump versions: `gradle.properties` (`pluginVersion`).
- Optional shortcut: `./scripts/release.sh --patch` (pushes tag + runs full tests; use `--no-push` to keep it local).
- Run tests: `./scripts/test-all.sh`.
- Build zip: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`.
- Tag + push: `git tag vX.Y.Z && git push && git push --tags`.
- Create a GitHub Release and upload the zip from `build/distributions/`.
