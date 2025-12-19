## AGENTS.md

This repo is designed to be worked on with **Code** (the Codex CLI fork).

User-facing setup and API usage live in [README.md](README.md).

## Tooling

- Plugin: Kotlin/Gradle (JetBrains 2025.x), requires Java 21.
- MCP server: Node.js 18+ in `mcp-server/`.

## Dev loop (Code)

- Build plugin zip: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`
- Run all tests: `./test-all.sh`
- Plugin unit tests only: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
- MCP server tests only: `cd mcp-server && npm test`

## Local validation

- Enable the JetBrains IDE built-in server (see README for the current settings path).
- Smoke test endpoints:
  - `curl "http://localhost:<port>/api/inspection/status"`
  - `curl "http://localhost:<port>/api/inspection/trigger?scope=whole_project"`
  - `curl "http://localhost:<port>/api/inspection/problems?severity=warning&limit=1"`

## Conventions

- Prefer descriptive names over comments/docstrings; keep commentary minimal.
- Avoid stale docs: keep this file evergreen and link to README for specifics.

## Local-only overrides

If you need machine-specific paths/ports/log locations, copy `AGENTS.local.template.md` to `AGENTS.local.md`.
`AGENTS.local.md` is gitignored.

## Release checklist

- Bump versions: `gradle.properties` (`pluginVersion`) and `mcp-server/package.json`.
- Run tests: `./test-all.sh`.
- Build zip: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin`.
- Tag + push: `git tag vX.Y.Z && git push && git push --tags`.
- Create a GitHub Release and upload the zip from `build/distributions/`.
