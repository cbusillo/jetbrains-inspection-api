# Private Plugin Recommendation Canary Fixture

This fixture is read-only evidence for issues #273 and #274.

- `main.go` recreates the issue #263 missing-Go-plugin recommendation case.
- `Main.java` exercises an already-installed bundled language plugin.
- `main.js` preserves the previously qualified installed-plugin case.
- `disabled.canary.properties` deterministically exercises the
  installed-but-disabled state with the bundled Properties plugin in an
  isolated profile.
- `main.rs` provides an additional missing-plugin/cold-cache probe.
- `irrelevant.canary_fixture` exercises the no-recommendation path.

The canary endpoint accepts only project file paths and never accepts plugin IDs.
Opening these files or querying the endpoint must not install, enable, disable,
download, update, or prompt for plugins.

For deterministic `recommended` coverage, prepare a disposable JetBrains 262
profile with `scripts/prepare-plugin-recommendation-canary-profile.sh`. The
profile contains only two advertiser-cache rows: `*.go` maps to the absent Go
plugin, and `*.properties` maps to the bundled Properties plugin while that plugin is
disabled in the isolated profile. The script refuses existing profiles,
products that bundle Go, and products without Properties; it never reads or
changes the operator's normal IDE configuration or writes project metadata into
the source fixture.
