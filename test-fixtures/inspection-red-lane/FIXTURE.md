# Inspection Red Lane Fixture

This project intentionally keeps an actionable Java inspection finding.
`DefinitelyRed.redLaneField` must stay non-final while the `RedLane` profile
enables `UnusedDeclaration`, so `scripts/dogfood-red-lane-smoke.sh` can prove
the live IDE, plugin, and helper report `VERDICT=RED` with `total_problems > 0`.

Do not add `final` to `redLaneField` in this fixture. If the fixture changes,
run `./scripts/test-red-lane-smoke-script.sh` and, when a local IntelliJ IDEA
with the plugin is available, `./scripts/dogfood-red-lane-smoke.sh`.
