# AGENTS.md

This repo is designed to be worked on with **Every Code** (the Codex CLI fork).

User-facing setup, API usage, and release details live in [README.md](README.md).
Testing details live in [TESTING.md](TESTING.md) and manual IDE smoke recipes
live in [TESTING_INSTRUCTIONS.md](TESTING_INSTRUCTIONS.md).

## Tooling

- Plugin: Kotlin/Gradle (JetBrains 2025.x), requires Java 21.
- MCP server: Kotlin/JVM (bundled in plugin, built via `mcp-server-jvm`).

## Repo Skills

- `jetbrains-inspection-workflow`: Use for plugin, MCP server, HTTP API, test,
  smoke, release, or docs work in this repo.

## Always-on rules

- If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
  JDK 21 path (the IDE's bundled runtime is fine).
- In the Every Code sandbox, Gradle may need escalated permissions; if you see
  "Operation not permitted" from NativeServices, re-run with escalation.
- Prefer descriptive names to comments/docstrings; keep commentary minimal.
- Avoid stale docs: keep this file evergreen and link to README for specifics.
- Use `git mv` for file moves so history stays intact.

## Local-only overrides

If you need machine-specific paths/ports/log locations, copy
`AGENTS.local.template.md` to `AGENTS.local.md`.
`AGENTS.local.md` is ignored in git.
