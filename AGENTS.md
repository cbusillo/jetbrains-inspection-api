# AGENTS.md

This repo is designed to be worked on with **Every Code** (the Codex CLI fork).

User-facing setup, API usage, and release details live in [README.md](README.md).
Testing details live in [TESTING.md](TESTING.md) and manual IDE smoke recipes
live in [TESTING_INSTRUCTIONS.md](TESTING_INSTRUCTIONS.md).
Use `.github/github.json` for non-secret repo workflow facts,
validation commands, GitHub signal availability, docs routing, important
workflows, and cleanup policy.

## Tooling

- Plugin: Kotlin/Gradle (JetBrains 2025.x), requires Java 21.
- MCP server: Kotlin/JVM (bundled in plugin, built via `mcp-server-jvm`).
- Agent inspection helper: the external `jetbrains-inspection` skill's
  `scripts/jb-inspect.py` is the primary LLM-facing path for this plugin; keep
  its status/clean/capture contracts in mind when changing HTTP inspection
  behavior.

## Always-on rules

- If `/usr/libexec/java_home -v 21` fails on macOS, set `JAVA_HOME_21` to your
  JDK 21 path (the IDE's bundled runtime is fine).
- In the Every Code sandbox, Gradle may need escalated permissions; if you see
  "Operation not permitted" from NativeServices, re-run with escalation.
- Prefer descriptive names to comments/docstrings; keep commentary minimal.
- Avoid stale docs: keep this file evergreen and link to README for specifics.
- If API status semantics, route metadata, clean/capture classification, or
  MCP tool contracts change, check whether the installed or checked-out
  `jetbrains-inspection` skill docs/tests/scripts need a matching update.

## Local-only overrides

If you need machine-specific paths/ports/log locations, copy
`AGENTS.local.template.md` to `AGENTS.local.md`.
`AGENTS.local.md` is ignored in git.
