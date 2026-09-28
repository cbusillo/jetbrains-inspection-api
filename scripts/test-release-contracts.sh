#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq -- "$unexpected" "$file"; then
    fail "$file unexpectedly contains: $unexpected"
  fi
}

test_version_validator() {
  ./scripts/validate-release-version.sh --tag "v$(sed -n 's/^pluginVersion=//p' gradle.properties)" >/dev/null

  if ./scripts/validate-release-version.sh --tag v0.0.0 >/dev/null 2>&1; then
    fail "version validator accepted a mismatched tag"
  fi
  if ./scripts/validate-release-version.sh --tag 1.2.3 >/dev/null 2>&1; then
    fail "version validator accepted a malformed tag"
  fi
  if ./scripts/validate-release-version.sh --tag v1.2.3-beta.1 >/dev/null 2>&1; then
    fail "Stable version validator accepted a prerelease version"
  fi
  if ./scripts/validate-release-version.sh --tag preview/v1.2.3-beta.1 >/dev/null 2>&1; then
    fail "Stable version validator accepted a prerelease tag namespace"
  fi

  local temp_dir
  temp_dir=$(mktemp -d)
  trap 'rm -rf "$temp_dir"' RETURN
  mkdir -p "$temp_dir/src/main/resources/META-INF" "$temp_dir/scripts"
  cp scripts/validate-release-version.sh "$temp_dir/scripts/"
  printf 'pluginVersion=1.2.3\n' > "$temp_dir/gradle.properties"
  printf '<idea-plugin><version>1.2.4</version></idea-plugin>\n' > "$temp_dir/src/main/resources/META-INF/plugin.xml"
  if (cd "$temp_dir" && ./scripts/validate-release-version.sh --tag v1.2.3 >/dev/null 2>&1); then
    fail "version validator accepted a plugin.xml mismatch"
  fi
  rm -rf "$temp_dir"
  trap - RETURN
}

test_stable_artifact_publication() {
  local temp_dir fake_bin curl_log archive archive_sha256 invalid_id_archive invalid_version_archive invalid_range_archive dirty_archive wrong_source_archive missing_jar_archive source_sha
  temp_dir=$(mktemp -d)
  fake_bin="$temp_dir/bin"
  curl_log="$temp_dir/curl.log"
  archive="$temp_dir/jetbrains-inspection-api-1.2.3.zip"
  invalid_id_archive="$temp_dir/invalid-id/jetbrains-inspection-api-1.2.3.zip"
  invalid_version_archive="$temp_dir/invalid-version/jetbrains-inspection-api-1.2.3.zip"
  invalid_range_archive="$temp_dir/invalid-range/jetbrains-inspection-api-1.2.3.zip"
  dirty_archive="$temp_dir/dirty/jetbrains-inspection-api-1.2.3.zip"
  wrong_source_archive="$temp_dir/wrong-source/jetbrains-inspection-api-1.2.3.zip"
  missing_jar_archive="$temp_dir/missing-jar/jetbrains-inspection-api-1.2.3.zip"
  source_sha="0123456789abcdef0123456789abcdef01234567"
  export STABLE_SOURCE_SHA="$source_sha"
  trap 'rm -rf "$temp_dir"' RETURN
  mkdir -p "$temp_dir/scripts" "$fake_bin"
  cp \
    scripts/publish-stable-artifact.sh \
    scripts/validate-stable-artifact.sh \
    scripts/validate-marketplace-publication.sh \
    "$temp_dir/scripts/"
  mkdir -p \
    "$(dirname "$invalid_id_archive")" \
    "$(dirname "$invalid_version_archive")" \
    "$(dirname "$invalid_range_archive")" \
    "$(dirname "$dirty_archive")" \
    "$(dirname "$wrong_source_archive")" \
    "$(dirname "$missing_jar_archive")"
  python3 - \
    "$archive" \
    "$invalid_id_archive" \
    "$invalid_version_archive" \
    "$invalid_range_archive" \
    "$dirty_archive" \
    "$wrong_source_archive" \
    "$missing_jar_archive" <<'PY'
from io import BytesIO
from pathlib import Path
import sys
import zipfile

def write_archive(
    path: Path,
    plugin_id: str,
    version: str,
    since_build: str = "251",
    until_build: str = "262.*",
    build_commit: str = "0123456789abcdef0123456789abcdef01234567",
    build_dirty: bool = False,
) -> None:
    plugin_xml = (
        f"<idea-plugin><id>{plugin_id}</id>"
        f"<version>{version}</version>"
        f'<idea-version since-build="{since_build}" until-build="{until_build}"/>'
        "</idea-plugin>"
    )
    state = "dirty" if build_dirty else "clean"
    build_info = "\n".join(
        [
            f"plugin.version={version}",
            f"plugin.build.commit={build_commit}",
            f"plugin.build.short_commit={build_commit[:12]}",
            f"plugin.build.dirty={str(build_dirty).lower()}",
            f"plugin.build.fingerprint={build_commit}-{state}",
            "plugin.build.time=2026-08-10T00\\:00\\:00Z",
            "",
        ]
    )
    plugin_jar = BytesIO()
    with zipfile.ZipFile(plugin_jar, "w") as jar:
        jar.writestr("META-INF/plugin.xml", plugin_xml)
        jar.writestr("com/shiny/inspectionmcp/inspection-build.properties", build_info)
    with zipfile.ZipFile(path, "w") as plugin_zip:
        plugin_zip.writestr(
            "jetbrains-inspection-api/lib/jetbrains-inspection-api-1.2.3.jar",
            plugin_jar.getvalue(),
        )

write_archive(Path(sys.argv[1]), "com.shiny.inspection.api", "1.2.3")
write_archive(Path(sys.argv[2]), "different.plugin.id", "1.2.3")
write_archive(Path(sys.argv[3]), "com.shiny.inspection.api", "1.2.4")
write_archive(Path(sys.argv[4]), "com.shiny.inspection.api", "1.2.3", since_build="262")
write_archive(Path(sys.argv[5]), "com.shiny.inspection.api", "1.2.3", build_dirty=True)
write_archive(
    Path(sys.argv[6]),
    "com.shiny.inspection.api",
    "1.2.3",
    build_commit="89abcdef0123456789abcdef0123456789abcdef",
)
with zipfile.ZipFile(Path(sys.argv[7]), "w") as plugin_zip:
    plugin_zip.writestr("unexpected.txt", "missing plugin jar")
PY
  archive_sha256=$(shasum -a 256 "$archive" | awk '{print $1}')
  cat > "$fake_bin/curl" <<'CURL'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$CURL_LOG"
CURL
  chmod +x "$fake_bin/curl"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test STABLE_SOURCE_SHA='' ./scripts/publish-stable-artifact.sh --archive "$archive" --tag v1.2.3 >/dev/null 2>&1); then
    fail "Stable artifact publisher accepted an absent source SHA"
  fi
  [ ! -e "$curl_log" ] || fail "Stable artifact publisher invoked curl without source provenance"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test ./scripts/publish-stable-artifact.sh --archive "$archive" --tag preview/v1.2.3-beta.1 >/dev/null 2>&1); then
    fail "Stable artifact publisher accepted a prerelease tag"
  fi
  [ ! -e "$curl_log" ] || fail "Stable artifact publisher invoked curl for a prerelease tag"

  for invalid_archive in \
    "$invalid_id_archive" \
    "$invalid_version_archive" \
    "$invalid_range_archive" \
    "$dirty_archive" \
    "$wrong_source_archive" \
    "$missing_jar_archive"; do
    if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test ./scripts/publish-stable-artifact.sh --archive "$invalid_archive" --tag v1.2.3 >/dev/null 2>&1); then
      fail "Stable artifact publisher accepted invalid artifact $invalid_archive"
    fi
    [ ! -e "$curl_log" ] || fail "Stable artifact publisher invoked curl for invalid artifact $invalid_archive"
  done

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN='' ./scripts/publish-stable-artifact.sh --archive "$archive" --tag v1.2.3 --expected-sha256 "$archive_sha256" >/dev/null 2>&1); then
    fail "Stable artifact publisher accepted an absent token"
  fi
  [ ! -e "$curl_log" ] || fail "Stable artifact publisher invoked curl without a token"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test ./scripts/publish-stable-artifact.sh --archive "$archive" --tag v1.2.3 --expected-sha256 "$(printf '0%.0s' {1..64})" >/dev/null 2>&1); then
    fail "Stable artifact publisher accepted an unverified artifact digest"
  fi
  [ ! -e "$curl_log" ] || fail "Stable artifact publisher invoked curl for an unverified artifact digest"

  (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test ./scripts/publish-stable-artifact.sh --archive "$archive" --tag v1.2.3 --expected-sha256 "$archive_sha256" >/dev/null)
  assert_contains "$curl_log" "xmlId=com.shiny.inspection.api"
  assert_contains "$curl_log" "file=@$archive"
  assert_not_contains "$curl_log" "channel="

  unset STABLE_SOURCE_SHA
  rm -rf "$temp_dir"
  trap - RETURN
}

test_marketplace_publication_policy() {
  ./scripts/validate-marketplace-publication.sh --version 1.2.3

  if ./scripts/validate-marketplace-publication.sh --version 1.2.3 --channel preview >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a Stable version with a custom channel"
  fi
  if ./scripts/validate-marketplace-publication.sh --version 1.2.3-beta.1 >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a prerelease version without a channel"
  fi
  if ./scripts/validate-marketplace-publication.sh --version 1.2.3-beta.1 --channel stable >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a prerelease version on the Stable channel"
  fi
  if ./scripts/validate-marketplace-publication.sh --version 1.2.3-beta.1 --channel preview >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a prerelease version with a custom channel"
  fi
}

test_internal_api_allowlist() {
  local temp_dir manifest report
  temp_dir=$(mktemp -d)
  manifest="$temp_dir/allowlist.txt"
  report="$temp_dir/internal-api-usages.txt"
  trap 'rm -rf "$temp_dir"' RETURN

  cat > "$manifest" <<'MANIFEST'
Internal class com.intellij.codeInspection.ex.GlobalInspectionContextImpl is referenced in approved.Location
Internal interface com.intellij.codeInspection.ex.InspectListener is referenced in approved.Listener
MANIFEST
  cat > "$report" <<'REPORT'
Internal interface com.intellij.codeInspection.ex.InspectListener is referenced in approved.Listener. This interface is internal.
Internal class com.intellij.codeInspection.ex.GlobalInspectionContextImpl is referenced in approved.Location. This class is internal.
REPORT
  ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" --report "$report" >/dev/null

  cat >> "$report" <<'REPORT'
Internal class com.intellij.codeInspection.ex.GlobalInspectionContextImpl is referenced in unapproved.Location. This class is internal.
REPORT
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" --report "$report" >/dev/null 2>&1; then
    fail "internal API allowlist accepted an unapproved usage containing an approved class name"
  fi

  cat > "$report" <<'REPORT'
Internal class com.intellij.codeInspection.ex.GlobalInspectionContextImpl is referenced in approved.Location. This class is internal.
REPORT
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" --report "$report" >/dev/null 2>&1; then
    fail "internal API allowlist accepted missing expected usage"
  fi

  printf 'unrecognized verifier output\n' > "$report"
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" --report "$report" >/dev/null 2>&1; then
    fail "internal API allowlist accepted a malformed report line"
  fi

  : > "$manifest"
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" >/dev/null 2>&1; then
    fail "internal API allowlist accepted an empty manifest"
  fi
  cat > "$manifest" <<'MANIFEST'
Internal interface example.Z is referenced in approved.Z
Internal class example.A is referenced in approved.A
MANIFEST
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" >/dev/null 2>&1; then
    fail "internal API allowlist accepted an unsorted manifest"
  fi
  cat > "$manifest" <<'MANIFEST'
Internal class example.A is referenced in approved.A
Internal class example.A is referenced in approved.A
MANIFEST
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" >/dev/null 2>&1; then
    fail "internal API allowlist accepted duplicate manifest entries"
  fi
  cat > "$manifest" <<'MANIFEST'
Internal class example.A is referenced in approved.A
not a verifier finding
MANIFEST
  if ./scripts/verify-internal-api-allowlist.py --manifest "$manifest" >/dev/null 2>&1; then
    fail "internal API allowlist accepted an unrecognized manifest entry"
  fi

  rm -rf "$temp_dir"
  trap - RETURN
}

write_gate_stub() {
  local path="$1"
  cat > "$path" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
version=$(sed -n 's/^pluginVersion=//p' gradle.properties)
python3 - "$version" <<'PY'
from pathlib import Path
import re
import sys

path = Path("src/main/resources/META-INF/plugin.xml")
text = path.read_text(encoding="utf-8")
path.write_text(
    re.sub(r"<version>[^<]*</version>", f"<version>{sys.argv[1]}</version>", text, count=1),
    encoding="utf-8",
)
PY
STUB
  chmod +x "$path"
}

test_release_script_flow() {
  local temp_dir repo remote fake_bin gh_log git_env_vars
  temp_dir=$(mktemp -d)
  repo="$temp_dir/repo"
  remote="$temp_dir/remote.git"
  fake_bin="$temp_dir/bin"
  gh_log="$temp_dir/gh.log"
  git_env_vars=$(git rev-parse --local-env-vars)
  trap 'rm -rf "$temp_dir"' RETURN

  (
    for variable in $git_env_vars; do
      unset "$variable"
    done

    git init --bare "$remote" >/dev/null
    git --git-dir="$remote" symbolic-ref HEAD refs/heads/main
    git init -b main "$repo" >/dev/null
    mkdir -p "$repo/scripts" "$repo/src/main/resources/META-INF" "$fake_bin"
    cp scripts/release.sh scripts/validate-release-version.sh "$repo/scripts/"
    printf 'pluginVersion=1.2.3\n' > "$repo/gradle.properties"
    printf '<idea-plugin><version>1.2.3</version></idea-plugin>\n' > "$repo/src/main/resources/META-INF/plugin.xml"

    for script in test-all.sh test-automated.sh release-compatibility-gate.sh; do
      printf '#!/usr/bin/env bash\nexit 0\n' > "$repo/scripts/$script"
      chmod +x "$repo/scripts/$script"
    done
    write_gate_stub "$repo/scripts/commit-gate.sh"

    cat > "$fake_bin/gh" <<'GH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_LOG"
printf 'https://example.invalid/pull/1\n'
GH
    chmod +x "$fake_bin/gh"

    cd "$repo"
    git config user.name "Release Contract Test"
    git config user.email "release-contract@example.invalid"
    git add .
    git commit -m initial >/dev/null
    git remote add origin "$remote"
    git push -u origin main >/dev/null
    git remote set-head origin main
    GH_LOG="$gh_log" PATH="$fake_bin:$PATH" ./scripts/release.sh --patch --yes >/dev/null

    [ "$(git branch --show-current)" = "release/v1.2.4" ] || fail "prepare did not create the release branch"
    [ "$(git show origin/main:gradle.properties)" = "pluginVersion=1.2.3" ] || fail "prepare changed remote main"
    [ "$(git show origin/release/v1.2.4:gradle.properties)" = "pluginVersion=1.2.4" ] || fail "release branch version is wrong"
    git show origin/release/v1.2.4:src/main/resources/META-INF/plugin.xml | grep -Fq '<version>1.2.4</version>' || fail "release branch plugin.xml version is wrong"
    [ -z "$(git tag --list)" ] || fail "prepare created a tag before merge"
    assert_contains "$gh_log" "auth status"
    assert_contains "$gh_log" "pr create --base main --head release/v1.2.4"

    git switch main >/dev/null
    git merge --ff-only release/v1.2.4 >/dev/null
    git push origin main >/dev/null
    ./scripts/release.sh tag v1.2.4 --no-push >/dev/null
    [ "$(git rev-list -n 1 v1.2.4)" = "$(git rev-parse HEAD)" ] || fail "tag does not point at merged main"
  )

  rm -rf "$temp_dir"
  trap - RETURN
}

test_static_contracts() {
  ./scripts/verify-internal-api-allowlist.py \
    --manifest config/plugin-verifier/stable-internal-api-allowlist.txt >/dev/null

  python3 - <<'PY'
from pathlib import Path

boundary = Path("src/main/kotlin/com/shiny/inspectionmcp/GlobalInspectionContextBoundary.kt")
implementation_name = "GlobalInspectionContextImpl"
violations = []
for source in Path("src/main/kotlin").rglob("*.kt"):
    if source == boundary:
        continue
    if implementation_name in source.read_text(encoding="utf-8"):
        violations.append(str(source))
if violations:
    raise SystemExit(
        "GlobalInspectionContextImpl must remain confined to "
        f"{boundary}: {', '.join(sorted(violations))}"
    )

stable_entries = {
    line.strip()
    for line in Path("config/plugin-verifier/stable-internal-api-allowlist.txt")
        .read_text(encoding="utf-8")
        .splitlines()
    if line.strip() and not line.lstrip().startswith("#")
}
misattributed = sorted(
    entry
    for entry in stable_entries
    if implementation_name in entry
    and "com.shiny.inspectionmcp.GlobalInspectionContextBoundary" not in entry
    and "com.shiny.inspectionmcp.NativeAttestedGlobalInspectionContext" not in entry
)
if misattributed:
    raise SystemExit(
        "Stable GlobalInspectionContextImpl findings escaped the named boundary:\n"
        + "\n".join(misattributed)
    )
PY

  python3 - <<'PY'
from pathlib import Path
import re

workflow_uses = re.compile(r"^(?:-\s+)?uses:")
pinned_workflow_action = re.compile(
    r"^(?:-\s+)?uses:\s+[^@#\s]+@[0-9a-f]{40}(?:\s+#.*)?$"
)

def is_unpinned_workflow_action(line: str) -> bool:
    return bool(workflow_uses.match(line)) and not bool(pinned_workflow_action.fullmatch(line))

valid_pinned_actions = (
    "uses: actions/example@0123456789abcdef0123456789abcdef01234567 # v6.0.0",
    "- uses: actions/example@0123456789abcdef0123456789abcdef01234567 # arbitrary note",
    "uses: actions/example@0123456789abcdef0123456789abcdef01234567",
)
invalid_pinned_actions = (
    "uses:",
    "- uses:",
    "uses: #@0123456789abcdef0123456789abcdef01234567",
    "uses: actions/example@0123456789abcdef0123456789abcdef0123456 # short",
    "uses: actions/example@0123456789abcdef0123456789abcdef0123456g # nonhex",
    "uses: actions/example@v6 # floating",
    "uses: actions/example@0123456789abcdef0123456789abcdef012345678 # long",
    "uses: actions/example@0123456789abcdef0123456789abcdef01234567 trailing",
)
if any(is_unpinned_workflow_action(line) for line in valid_pinned_actions):
    raise SystemExit("workflow action pin regression fixture unexpectedly rejected")
if not all(is_unpinned_workflow_action(line) for line in invalid_pinned_actions):
    raise SystemExit("workflow action pin regression fixture unexpectedly accepted")

workflow_paths = sorted(
    (*Path(".github/workflows").glob("*.yml"), *Path(".github/workflows").glob("*.yaml")),
)
for workflow_path in workflow_paths:
    for line in workflow_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if is_unpinned_workflow_action(stripped):
            raise SystemExit(
                f"workflow action is not pinned to a full commit SHA in {workflow_path}: {stripped}"
            )

build = Path("build.gradle.kts").read_text(encoding="utf-8")
stable_validator = Path("scripts/validate-stable-artifact.sh").read_text(encoding="utf-8")
build_since = re.search(r'sinceBuild = "([^"]+)"', build)
build_until = re.search(r'untilBuild = "([^"]+)"', build)
stable_since = re.search(r'EXPECTED_SINCE_BUILD="([^"]+)"', stable_validator)
stable_until = re.search(r'EXPECTED_UNTIL_BUILD="([^"]+)"', stable_validator)
if not all((build_since, build_until, stable_since, stable_until)):
    raise SystemExit("trusted compatibility policy could not be resolved")
if stable_since.group(1) != build_since.group(1) or stable_until.group(1) != build_until.group(1):
    raise SystemExit("Stable artifact compatibility policy must match Gradle")

PY
}

test_version_validator
test_stable_artifact_publication
test_marketplace_publication_policy
test_internal_api_allowlist
test_release_script_flow
test_static_contracts

echo "Release contract tests passed."
