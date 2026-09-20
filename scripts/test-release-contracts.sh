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
  if ./scripts/validate-release-version.sh --tag v1.2.3-canary.1 >/dev/null 2>&1; then
    fail "Stable version validator accepted a canary version"
  fi
  if ./scripts/validate-release-version.sh --tag canary/v1.2.3-canary.1 >/dev/null 2>&1; then
    fail "Stable version validator accepted a canary tag namespace"
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

test_canary_version_validator() {
  local temp_dir tag
  temp_dir=$(mktemp -d)
  trap 'rm -rf "$temp_dir"' RETURN
  mkdir -p "$temp_dir/src/main/resources/META-INF" "$temp_dir/scripts"
  cp scripts/validate-canary-release-version.sh "$temp_dir/scripts/"
  printf 'pluginVersion=1.2.3-canary.1\n' > "$temp_dir/gradle.properties"
  printf '<idea-plugin><id>com.shiny.inspection.api</id><version>1.2.3-canary.1</version></idea-plugin>\n' > "$temp_dir/src/main/resources/META-INF/plugin.xml"

  (cd "$temp_dir" && ./scripts/validate-canary-release-version.sh --tag canary/v1.2.3-canary.1 --channel canary >/dev/null)
  ./scripts/validate-canary-release-version.sh --repo "$temp_dir" --tag canary/v1.2.3-canary.1 --channel canary >/dev/null

  if (cd "$temp_dir" && ./scripts/validate-canary-release-version.sh --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary validator accepted an absent channel"
  fi
  if (cd "$temp_dir" && ./scripts/validate-canary-release-version.sh --tag canary/v1.2.3-canary.1 --channel stable >/dev/null 2>&1); then
    fail "canary validator accepted the Stable channel"
  fi

  for tag in \
    v1.2.3-canary.1 \
    canary/v1.2.3 \
    canary/v1.2.3-beta.1 \
    canary/v1.2.3-canary.0 \
    canary/v1.2.3-canary.01; do
    if (cd "$temp_dir" && ./scripts/validate-canary-release-version.sh --tag "$tag" --channel canary >/dev/null 2>&1); then
      fail "canary validator accepted malformed tag $tag"
    fi
  done

  printf 'pluginVersion=1.2.3-canary.2\n' > "$temp_dir/gradle.properties"
  if (cd "$temp_dir" && ./scripts/validate-canary-release-version.sh --tag canary/v1.2.3-canary.1 --channel canary >/dev/null 2>&1); then
    fail "canary validator accepted a gradle.properties mismatch"
  fi
  printf 'pluginVersion=1.2.3-canary.1\n' > "$temp_dir/gradle.properties"
  printf '<idea-plugin><id>different.plugin.id</id><version>1.2.3-canary.1</version></idea-plugin>\n' > "$temp_dir/src/main/resources/META-INF/plugin.xml"
  if (cd "$temp_dir" && ./scripts/validate-canary-release-version.sh --tag canary/v1.2.3-canary.1 --channel canary >/dev/null 2>&1); then
    fail "canary validator accepted a changed plugin ID"
  fi

  rm -rf "$temp_dir"
  trap - RETURN
}

test_canary_artifact_publication() {
  local temp_dir fake_bin curl_log archive archive_sha256 bad_archive bad_version_archive bad_compatibility_archive dirty_archive wrong_source_archive missing_jar_archive source_sha
  temp_dir=$(mktemp -d)
  fake_bin="$temp_dir/bin"
  curl_log="$temp_dir/curl.log"
  archive="$temp_dir/jetbrains-inspection-api-1.2.3-canary.1.zip"
  bad_archive="$temp_dir/bad/jetbrains-inspection-api-1.2.3-canary.1.zip"
  bad_version_archive="$temp_dir/bad-version/jetbrains-inspection-api-1.2.3-canary.1.zip"
  bad_compatibility_archive="$temp_dir/bad-compatibility/jetbrains-inspection-api-1.2.3-canary.1.zip"
  dirty_archive="$temp_dir/dirty/jetbrains-inspection-api-1.2.3-canary.1.zip"
  wrong_source_archive="$temp_dir/wrong-source/jetbrains-inspection-api-1.2.3-canary.1.zip"
  missing_jar_archive="$temp_dir/missing-jar/jetbrains-inspection-api-1.2.3-canary.1.zip"
  source_sha="0123456789abcdef0123456789abcdef01234567"
  export CANARY_SOURCE_SHA="$source_sha"
  trap 'rm -rf "$temp_dir"' RETURN
  mkdir -p "$temp_dir/scripts" "$fake_bin"
  cp \
    scripts/publish-canary-artifact.sh \
    scripts/validate-canary-artifact.sh \
    scripts/validate-marketplace-publication.sh \
    "$temp_dir/scripts/"
  mkdir -p \
    "$(dirname "$bad_archive")" \
    "$(dirname "$bad_version_archive")" \
    "$(dirname "$bad_compatibility_archive")" \
    "$(dirname "$dirty_archive")" \
    "$(dirname "$wrong_source_archive")" \
    "$(dirname "$missing_jar_archive")"
  python3 - \
    "$archive" \
    "$bad_archive" \
    "$bad_version_archive" \
    "$bad_compatibility_archive" \
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
    since_build: str = "262",
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
            "plugin.build.time=2026-08-09T00\\:00\\:00Z",
            "",
        ]
    )
    plugin_jar = BytesIO()
    with zipfile.ZipFile(plugin_jar, "w") as jar:
        jar.writestr("META-INF/plugin.xml", plugin_xml)
        jar.writestr("com/shiny/inspectionmcp/inspection-build.properties", build_info)
    with zipfile.ZipFile(path, "w") as plugin_zip:
        plugin_zip.writestr(
            "jetbrains-inspection-api/lib/jetbrains-inspection-api-1.2.3-canary.1.jar",
            plugin_jar.getvalue(),
        )

write_archive(Path(sys.argv[1]), "com.shiny.inspection.api", "1.2.3-canary.1")
write_archive(Path(sys.argv[2]), "different.plugin.id", "1.2.3-canary.1")
write_archive(Path(sys.argv[3]), "com.shiny.inspection.api", "1.2.3-canary.2")
write_archive(
    Path(sys.argv[4]),
    "com.shiny.inspection.api",
    "1.2.3-canary.1",
    until_build="271.*",
)
write_archive(
    Path(sys.argv[5]),
    "com.shiny.inspection.api",
    "1.2.3-canary.1",
    build_dirty=True,
)
write_archive(
    Path(sys.argv[6]),
    "com.shiny.inspection.api",
    "1.2.3-canary.1",
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

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary CANARY_SOURCE_SHA='' ./scripts/publish-canary-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted an absent source SHA"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl without source provenance"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test ./scripts/publish-canary-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted an absent channel"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl without a channel"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=stable ./scripts/publish-canary-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted the Stable channel"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl with the wrong channel"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$archive" --tag v1.2.3 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted a Stable tag"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for a Stable tag"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$bad_archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted a changed embedded plugin ID"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for an invalid artifact"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$bad_version_archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted a mismatched embedded version"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for a mismatched version"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$bad_compatibility_archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted an untrusted compatibility range"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for an untrusted compatibility range"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$dirty_archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted dirty source provenance"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for dirty source provenance"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$wrong_source_archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted a mismatched source commit"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for a mismatched source commit"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$missing_jar_archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "canary artifact publisher accepted an archive without the plugin jar"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for a missing plugin jar"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN='' MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 --expected-sha256 "$archive_sha256" >/dev/null 2>&1); then
    fail "canary artifact publisher accepted an absent token"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl without a token"

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 --expected-sha256 "$(printf '0%.0s' {1..64})" >/dev/null 2>&1); then
    fail "canary artifact publisher accepted an unverified artifact digest"
  fi
  [ ! -e "$curl_log" ] || fail "canary artifact publisher invoked curl for an unverified artifact digest"

  (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test MARKETPLACE_CHANNEL=canary ./scripts/publish-canary-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 --expected-sha256 "$archive_sha256" >/dev/null)
  assert_contains "$curl_log" "xmlId=com.shiny.inspection.api"
  assert_contains "$curl_log" "file=@$archive"
  assert_contains "$curl_log" "channel=canary"

  unset CANARY_SOURCE_SHA
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

  if (cd "$temp_dir" && PATH="$fake_bin:$PATH" CURL_LOG="$curl_log" PUBLISH_TOKEN=test ./scripts/publish-stable-artifact.sh --archive "$archive" --tag canary/v1.2.3-canary.1 >/dev/null 2>&1); then
    fail "Stable artifact publisher accepted a canary tag"
  fi
  [ ! -e "$curl_log" ] || fail "Stable artifact publisher invoked curl for a canary tag"

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
  ./scripts/validate-marketplace-publication.sh --version 1.2.3-canary.1 --channel canary

  if ./scripts/validate-marketplace-publication.sh --version 1.2.3 --channel canary >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a Stable version with a custom channel"
  fi
  if ./scripts/validate-marketplace-publication.sh --version 1.2.3-canary.1 >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a canary version without a channel"
  fi
  if ./scripts/validate-marketplace-publication.sh --version 1.2.3-canary.1 --channel stable >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted a canary version on the Stable channel"
  fi
  if ./scripts/validate-marketplace-publication.sh --version 1.2.3-beta.1 --channel canary >/dev/null 2>&1; then
    fail "Marketplace publication policy accepted an unsupported version"
  fi
}

test_canary_branch_isolation() {
  local temp_dir repo remote git_env_vars
  temp_dir=$(mktemp -d)
  repo="$temp_dir/repo"
  remote="$temp_dir/remote.git"
  git_env_vars=$(git rev-parse --local-env-vars)
  trap 'rm -rf "$temp_dir"' RETURN

  (
    for variable in $git_env_vars; do
      unset "$variable"
    done

    git init --bare "$remote" >/dev/null
    git --git-dir="$remote" symbolic-ref HEAD refs/heads/main
    git init -b main "$repo" >/dev/null
    mkdir -p "$repo/scripts"
    cp scripts/verify-canary-branch-isolation.sh "$repo/scripts/"
    cd "$repo"
    git config user.name "Canary Contract Test"
    git config user.email "canary-contract@example.invalid"
    printf 'stable\n' > state.txt
    git add .
    git commit -m stable >/dev/null
    git remote add origin "$remote"
    git push -u origin main >/dev/null

    git switch -c canary/experiment >/dev/null
    printf 'canary\n' > state.txt
    git commit -am canary >/dev/null
    ./scripts/verify-canary-branch-isolation.sh main >/dev/null

    git switch main >/dev/null
    if ./scripts/verify-canary-branch-isolation.sh main >/dev/null 2>&1; then
      fail "canary branch isolation accepted a default-branch commit"
    fi
    if ./scripts/verify-canary-branch-isolation.sh missing >/dev/null 2>&1; then
      fail "canary branch isolation accepted a missing default branch"
    fi
  )

  rm -rf "$temp_dir"
  trap - RETURN
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

test_canary_verification_trust_boundary() {
  local temp_dir trusted source archive gradle_log java_home_21 java_home_25 source_sha
  temp_dir=$(mktemp -d)
  trusted="$temp_dir/trusted"
  source="$temp_dir/source"
  archive="$temp_dir/jetbrains-inspection-api-1.2.3-canary.1.zip"
  gradle_log="$temp_dir/gradle.log"
  java_home_21="$temp_dir/jdk-21"
  java_home_25="$temp_dir/jdk-25"
  source_sha="0123456789abcdef0123456789abcdef01234567"
  trap 'rm -rf "$temp_dir"' RETURN

  mkdir -p \
    "$trusted/scripts" \
    "$trusted/config/plugin-verifier" \
    "$java_home_21/bin" \
    "$java_home_25/bin" \
    "$source/trusted/scripts" \
    "$source/build/reports/pluginVerifier/tampered" \
    "$source/config/plugin-verifier"
  cp \
    scripts/validate-canary-artifact.sh \
    scripts/validate-marketplace-publication.sh \
    scripts/verify-canary-artifact.sh \
    "$trusted/scripts/"
  cp config/plugin-verifier/canary-internal-api-allowlist.txt \
    "$trusted/config/plugin-verifier/"

  cat > "$trusted/gradlew" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
test "$PWD" = "$TRUSTED_ROOT"
test -f config/plugin-verifier/canary-internal-api-allowlist.txt
test "$JAVA_HOME" = "$EXPECTED_JAVA_HOME"
printf '%s\n' "$@" > "$GRADLE_LOG"
GRADLE
  chmod +x "$trusted/gradlew"

  cat > "$java_home_21/bin/java" <<'JAVA21'
#!/usr/bin/env bash
echo 'openjdk version "21.0.11"' >&2
JAVA21
  chmod +x "$java_home_21/bin/java"

  cat > "$java_home_25/bin/java" <<'JAVA25'
#!/usr/bin/env bash
echo 'openjdk version "25.0.2"' >&2
JAVA25
  chmod +x "$java_home_25/bin/java"

  cat > "$source/trusted/scripts/verify-canary-artifact.sh" <<'MALICIOUS'
#!/usr/bin/env bash
touch "${TAMPER_MARKER:?}"
exit 0
MALICIOUS
  chmod +x "$source/trusted/scripts/verify-canary-artifact.sh"
  printf 'fabricated report\n' > \
    "$source/build/reports/pluginVerifier/tampered/internal-api-usages.txt"
  printf 'fabricated manifest\n' > \
    "$source/config/plugin-verifier/canary-internal-api-allowlist.txt"

  python3 - "$archive" "$source_sha" <<'PY'
from io import BytesIO
from pathlib import Path
import sys
import zipfile

archive = Path(sys.argv[1])
source_sha = sys.argv[2]
plugin_jar = BytesIO()
with zipfile.ZipFile(plugin_jar, "w") as jar:
    jar.writestr(
        "META-INF/plugin.xml",
        "<idea-plugin><id>com.shiny.inspection.api</id>"
        "<version>1.2.3-canary.1</version>"
        '<idea-version since-build="262" until-build="262.*"/>'
        "</idea-plugin>",
    )
    jar.writestr(
        "com/shiny/inspectionmcp/inspection-build.properties",
        "\n".join(
            [
                "plugin.version=1.2.3-canary.1",
                f"plugin.build.commit={source_sha}",
                f"plugin.build.short_commit={source_sha[:12]}",
                "plugin.build.dirty=false",
                f"plugin.build.fingerprint={source_sha}-clean",
                "plugin.build.time=2026-08-09T00\\:00\\:00Z",
                "",
            ]
        ),
    )
with zipfile.ZipFile(archive, "w") as plugin_zip:
    plugin_zip.writestr(
        "jetbrains-inspection-api/lib/jetbrains-inspection-api-1.2.3-canary.1.jar",
        plugin_jar.getvalue(),
    )
PY

  (
    export GRADLE_LOG="$gradle_log"
    export TAMPER_MARKER="$temp_dir/tampered"
    export TRUSTED_ROOT="$trusted"
    export JAVA_HOME="$java_home_25"
    export JAVA_HOME_21="$java_home_21"
    export EXPECTED_JAVA_HOME="$java_home_21"
    cd "$source"
    "$trusted/scripts/verify-canary-artifact.sh" \
      --archive "$archive" \
      --tag canary/v1.2.3-canary.1 \
      --channel canary \
      --source-sha "$source_sha" >/dev/null
  )

  [ ! -e "$temp_dir/tampered" ] || \
    fail "canary verification executed branch-controlled trusted controls"
  assert_contains "$gradle_log" "--no-build-cache"
  assert_contains "$gradle_log" "verifyPlugin"
  assert_contains "$gradle_log" "-PpluginVersion=1.2.3-canary.1"
  assert_contains "$gradle_log" "-PpluginVerificationArchive=$archive"

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
  ./scripts/verify-internal-api-allowlist.py \
    --manifest config/plugin-verifier/canary-internal-api-allowlist.txt >/dev/null

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
canary_validator = Path("scripts/validate-canary-artifact.sh").read_text(encoding="utf-8")
stable_validator = Path("scripts/validate-stable-artifact.sh").read_text(encoding="utf-8")
build_since = re.search(r'sinceBuild = "([^"]+)"', build)
build_until = re.search(r'untilBuild = "([^"]+)"', build)
canary_since = re.search(r'EXPECTED_SINCE_BUILD="([^"]+)"', canary_validator)
canary_until = re.search(r'EXPECTED_UNTIL_BUILD="([^"]+)"', canary_validator)
stable_since = re.search(r'EXPECTED_SINCE_BUILD="([^"]+)"', stable_validator)
stable_until = re.search(r'EXPECTED_UNTIL_BUILD="([^"]+)"', stable_validator)
if not all((build_since, build_until, canary_since, canary_until, stable_since, stable_until)):
    raise SystemExit("trusted compatibility policy could not be resolved")
if stable_since.group(1) != build_since.group(1) or stable_until.group(1) != build_until.group(1):
    raise SystemExit("Stable artifact compatibility policy must match Gradle")
if canary_since.group(1) != "262" or canary_until.group(1) != "262.*":
    raise SystemExit("canary artifact compatibility policy must remain 262-only")

PY
}

test_version_validator
test_canary_version_validator
test_canary_artifact_publication
test_stable_artifact_publication
test_marketplace_publication_policy
test_canary_branch_isolation
test_internal_api_allowlist
test_canary_verification_trust_boundary
test_release_script_flow
test_static_contracts

echo "Release contract tests passed."
