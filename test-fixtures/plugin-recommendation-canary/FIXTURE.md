# Private Plugin Recommendation Canary Fixture

This fixture is read-only evidence for issue #273.

- `main.go` recreates the issue #263 missing-Go-plugin recommendation case.
- `Main.java` exercises an already-installed bundled language plugin.
- `main.js` exercises installed and isolated-config disabled-plugin states in PyCharm.
- `main.rs` provides an additional missing-plugin/cold-cache probe.
- `irrelevant.canary_fixture` exercises the no-recommendation path.

The canary endpoint accepts only project file paths and never accepts plugin IDs.
Opening these files or querying the endpoint must not install, enable, disable,
download, update, or prompt for plugins.
