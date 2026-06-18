# Inspection Red Lane Fixture

This project is intentionally broken. `DefinitelyRed.java` must reference the
unresolved `MissingType` symbol so `scripts/dogfood-red-lane-smoke.sh` can prove
the live IDE, plugin, and helper report `VERDICT=RED` with `total_problems > 0`.

Do not add `MissingType` or otherwise make this project compile. If the fixture changes, run
`./scripts/test-red-lane-smoke-script.sh` and, when a local IntelliJ IDEA with
the plugin is available, `./scripts/dogfood-red-lane-smoke.sh`.
