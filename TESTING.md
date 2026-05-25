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

The external `jetbrains-inspection` skill uses `scripts/jb-inspect.py` as the
primary agent-facing wrapper around this plugin's HTTP API. Run it from the
installed or checked-out skill when validating behavior the agents rely on:

```bash
HELPER="${CODEX_HOME:-$HOME/.code}/skills/jetbrains-inspection/scripts/jb-inspect.py"
uv run "$HELPER" run \
  --repo "$PWD" \
  --scope changed_files
```

For agent closeout/readiness, prefer `closeout` instead of plain `run`:

```bash
uv run "$HELPER" closeout \
  --repo "$PWD" \
  --scope changed_files
```

`closeout` serializes helper-owned IDE opens, requires an exact current-worktree
route, runs inspection, and calls the plugin lifecycle close endpoint only for
projects opened by the helper. Projects that were open before the helper started
are left open. On macOS, lifecycle opens use `open -g` by default so the IDE should
not take focus while a closeout is preparing a worktree. Auto-open requires a
global trusted-root policy in
`${CODEX_HOME:-$HOME/.code}/jetbrains-inspection.json`; test worktrees should be
created under those roots, not random temp directories.

Before it starts lifecycle auto-open, the helper adds the matching trusted root
to the selected JetBrains product's Trusted Locations and sets project opening
to the separate-window mode. If auto-open stalls, treat it as a blocker: check
for an unsupported IDE config layout, settings sync overwriting the config, or a
missing inspection plugin. The
plugin-side lifecycle open endpoint schedules project opening asynchronously and
uses the worktree directory name as the frame project name so cloned worktrees
with identical checked-in `.idea` metadata can coexist in IntelliJ IDEA,
PyCharm, and WebStorm.

The helper treats `capture_incomplete`, stale results, timeouts, indexing,
session drift, route ambiguity, wrong-worktree routes, and cleanup failures as
non-clean outcomes. Cached stale findings are returned only when the helper is
run with `--include-stale` for explicit diagnostics. When this repo changes
inspection status semantics, route metadata, clean/capture classification,
lifecycle cleanup contracts, or MCP tool response contracts, update the skill
docs/tests/scripts in the `jetbrains-inspection` skill as part of the same
workstream.

Before shipping changes to clean/capture classification, run the focused
`InspectionSnapshotStateTest` coverage, then build the plugin with
`./gradlew buildPlugin`. Smoke the installed plugin from the agent helper in the
JetBrains IDEs that matter for the change, such as PyCharm, WebStorm, and
IntelliJ IDEA. If plugin installation prompts about replacing an existing jar,
handle that explicitly and rerun the smoke; the prompt alone is not install
validation.

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
