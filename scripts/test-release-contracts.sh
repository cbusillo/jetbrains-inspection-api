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
  local temp_dir fake_bin curl_log archive archive_sha256 bad_archive bad_version_archive bad_compatibility_archive missing_jar_archive
  temp_dir=$(mktemp -d)
  fake_bin="$temp_dir/bin"
  curl_log="$temp_dir/curl.log"
  archive="$temp_dir/jetbrains-inspection-api-1.2.3-canary.1.zip"
  bad_archive="$temp_dir/bad/jetbrains-inspection-api-1.2.3-canary.1.zip"
  bad_version_archive="$temp_dir/bad-version/jetbrains-inspection-api-1.2.3-canary.1.zip"
  bad_compatibility_archive="$temp_dir/bad-compatibility/jetbrains-inspection-api-1.2.3-canary.1.zip"
  missing_jar_archive="$temp_dir/missing-jar/jetbrains-inspection-api-1.2.3-canary.1.zip"
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
    "$(dirname "$missing_jar_archive")"
  python3 - \
    "$archive" \
    "$bad_archive" \
    "$bad_version_archive" \
    "$bad_compatibility_archive" \
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
) -> None:
    plugin_xml = (
        f"<idea-plugin><id>{plugin_id}</id>"
        f"<version>{version}</version>"
        f'<idea-version since-build="{since_build}" until-build="{until_build}"/>'
        "</idea-plugin>"
    )
    plugin_jar = BytesIO()
    with zipfile.ZipFile(plugin_jar, "w") as jar:
        jar.writestr("META-INF/plugin.xml", plugin_xml)
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
with zipfile.ZipFile(Path(sys.argv[5]), "w") as plugin_zip:
    plugin_zip.writestr("unexpected.txt", "missing plugin jar")
PY
  archive_sha256=$(shasum -a 256 "$archive" | awk '{print $1}')
  cat > "$fake_bin/curl" <<'CURL'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$CURL_LOG"
CURL
  chmod +x "$fake_bin/curl"

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

  rm -rf "$temp_dir"
  trap - RETURN
}

test_canary_verification_trust_boundary() {
  local temp_dir trusted source archive gradle_log
  temp_dir=$(mktemp -d)
  trusted="$temp_dir/trusted"
  source="$temp_dir/source"
  archive="$temp_dir/jetbrains-inspection-api-1.2.3-canary.1.zip"
  gradle_log="$temp_dir/gradle.log"
  trap 'rm -rf "$temp_dir"' RETURN

  mkdir -p \
    "$trusted/scripts" \
    "$trusted/config/plugin-verifier" \
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
printf '%s\n' "$@" > "$GRADLE_LOG"
GRADLE
  chmod +x "$trusted/gradlew"

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

  python3 - "$archive" <<'PY'
from io import BytesIO
from pathlib import Path
import sys
import zipfile

archive = Path(sys.argv[1])
plugin_jar = BytesIO()
with zipfile.ZipFile(plugin_jar, "w") as jar:
    jar.writestr(
        "META-INF/plugin.xml",
        "<idea-plugin><id>com.shiny.inspection.api</id>"
        "<version>1.2.3-canary.1</version>"
        '<idea-version since-build="251" until-build="262.*"/>'
        "</idea-plugin>",
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
    cd "$source"
    "$trusted/scripts/verify-canary-artifact.sh" \
      --archive "$archive" \
      --tag canary/v1.2.3-canary.1 \
      --channel canary >/dev/null
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
  assert_not_contains scripts/release.sh 'git push "$REMOTE" HEAD'
  assert_not_contains scripts/release.sh '--allow-non-default-branch'
  assert_contains scripts/release.sh 'release/v${new_version}'
  assert_contains scripts/release.sh 'gh pr create'
  assert_contains scripts/release-compatibility-gate.sh 'buildPlugin verifyPluginStructure verifyPlugin'
  assert_contains build.gradle.kts 'stable-internal-api-allowlist.txt'
  assert_contains build.gradle.kts 'canary-internal-api-allowlist.txt'
  assert_contains build.gradle.kts 'providers.gradleProperty("pluginVerificationArchive")'
  assert_contains build.gradle.kts 'archiveFile.set(layout.file(externalVerificationArchive.map { file(it) }))'
  assert_contains build.gradle.kts 'scripts/validate-marketplace-publication.sh'
  assert_contains scripts/validate-marketplace-publication.sh 'Canary plugin versions require -PmarketplaceChannel=canary.'
  assert_contains scripts/validate-marketplace-publication.sh 'Stable plugin versions must use the default Marketplace channel.'
  assert_not_contains build.gradle.kts 'usage.contains("com.intellij.codeInspection.ex.GlobalInspectionContextImpl")'
  assert_not_contains build.gradle.kts 'usage.contains("com.intellij.codeInspection.ex.InspectListener")'
  ./scripts/verify-internal-api-allowlist.py \
    --manifest config/plugin-verifier/stable-internal-api-allowlist.txt >/dev/null
  ./scripts/verify-internal-api-allowlist.py \
    --manifest config/plugin-verifier/canary-internal-api-allowlist.txt >/dev/null

  python3 - <<'PY'
from pathlib import Path
import re

build = Path("build.gradle.kts").read_text(encoding="utf-8")
validator = Path("scripts/validate-canary-artifact.sh").read_text(encoding="utf-8")
build_since = re.search(r'sinceBuild = "([^"]+)"', build)
build_until = re.search(r'untilBuild = "([^"]+)"', build)
validator_since = re.search(r'EXPECTED_SINCE_BUILD="([^"]+)"', validator)
validator_until = re.search(r'EXPECTED_UNTIL_BUILD="([^"]+)"', validator)
if not all((build_since, build_until, validator_since, validator_until)):
    raise SystemExit("trusted compatibility policy could not be resolved")
if build_since.group(1) != validator_since.group(1) or build_until.group(1) != validator_until.group(1):
    raise SystemExit("trusted artifact and Gradle compatibility policies diverged")

workflow = Path(".github/workflows/release.yml").read_text(encoding="utf-8")
validate = workflow.index("Validate release version")
default_branch = workflow.index("Verify tag commit is current default branch")
compatibility = workflow.index("Run release compatibility gate")
github_release = workflow.index("Create GitHub Release")
publish = workflow.index("Publish to JetBrains Marketplace")
if not validate < default_branch < compatibility < github_release < publish:
    raise SystemExit("release workflow validation/verifier/publish ordering is unsafe")
PY

  assert_contains .github/workflows/release.yml '- "v*"'
  assert_not_contains .github/workflows/release.yml 'canary/v'
  assert_not_contains .github/workflows/release.yml 'validate-canary-release-version.sh'
  assert_contains .github/workflows/release.yml 'run: ./gradlew publishPlugin'

  assert_contains .github/workflows/canary-release.yml 'workflow_dispatch:'
  assert_contains .github/workflows/canary-release.yml 'Existing isolated tag in canary/vX.Y.Z-canary.N format'
  assert_not_contains .github/workflows/canary-release.yml 'tags:'
  assert_not_contains .github/workflows/canary-release.yml './scripts/validate-release-version.sh'
  assert_contains .github/workflows/canary-release.yml 'MARKETPLACE_CHANNEL: canary'
  assert_contains .github/workflows/canary-release.yml 'CANARY_PUBLISH_TOKEN'
  assert_contains .github/workflows/canary-release.yml 'environment: canary-marketplace'
  assert_contains .github/workflows/canary-release.yml '--prerelease'
  assert_contains .github/workflows/canary-release.yml 'trusted/scripts/publish-canary-artifact.sh'
  assert_contains .github/workflows/canary-release.yml 'trusted/scripts/validate-canary-artifact.sh'
  assert_contains .github/workflows/canary-release.yml 'trusted/scripts/verify-canary-artifact.sh'
  assert_not_contains .github/workflows/canary-release.yml 'source/config/plugin-verifier/canary-internal-api-allowlist.txt'
  assert_not_contains .github/workflows/canary-release.yml 'cache: "gradle"'
  assert_contains .github/workflows/canary-release.yml 'if: always()'
  assert_not_contains .github/workflows/canary-release.yml 'softprops/action-gh-release'
  assert_contains scripts/publish-canary-artifact.sh '--expected-sha256'
  assert_not_contains .github/workflows/canary-release.yml 'generate-release-notes.sh'
  assert_not_contains .github/workflows/canary-release.yml '--tag "${{ inputs.tag }}"'
  assert_contains .github/workflows/canary-release.yml 'CANARY_TAG: ${{ inputs.tag }}'
  assert_contains .github/workflows/canary-release.yml 'ref: ${{ github.sha }}'
  assert_not_contains .github/workflows/canary-release.yml 'ref: ${{ github.event.repository.default_branch }}'
  assert_contains .github/github.json '.github/workflows/canary-release.yml'

  python3 - <<'PY'
from pathlib import Path
import re

workflow = Path(".github/workflows/canary-release.yml").read_text(encoding="utf-8")
build = workflow.split("\n  build:\n", 1)[1].split("\n  verify:\n", 1)[0]
verify = workflow.split("\n  verify:\n", 1)[1].split("\n  publish:\n", 1)[0]
publish = workflow.split("\n  publish:\n", 1)[1].split("\n  release:\n", 1)[0]
release = workflow.split("\n  release:\n", 1)[1]

for line in workflow.splitlines():
    stripped = line.strip()
    if stripped.startswith("uses:") and not re.search(r"@[0-9a-f]{40}(?:\s+#\s+v\d+)?$", stripped):
        raise SystemExit(f"canary workflow action is not pinned to a full commit SHA: {stripped}")

build_steps = [
    "Verify trusted workflow ref",
    "Validate canary source metadata",
    "Verify exact canary tag and branch isolation",
    "Run canary commit gate",
    "Run canary release compatibility gate",
    "Upload canary plugin artifact",
]
if [build.index(step) for step in build_steps] != sorted(build.index(step) for step in build_steps):
    raise SystemExit("canary build workflow gate ordering is unsafe")
if "CANARY_PUBLISH_TOKEN" in build:
    raise SystemExit("canary build job must not receive Marketplace secrets")
if "verify-internal-api-allowlist.py" in build or "internal-api-usages.txt" in build:
    raise SystemExit("canary build job must not make the trusted verification decision")
if build.count("persist-credentials: false") != 2:
    raise SystemExit("canary build checkouts must not persist credentials")

verify_steps = [
    "Verify trusted workflow ref",
    "Check out trusted verification controls",
    "Download canary plugin artifact",
    "Revalidate canary tag provenance",
    "Independently verify canary artifact",
    "Record verified artifact digest",
    "Upload trusted Plugin Verifier reports",
]
if [verify.index(step) for step in verify_steps] != sorted(verify.index(step) for step in verify_steps):
    raise SystemExit("canary verification workflow gate ordering is unsafe")
if "path: source" in verify or "working-directory: source" in verify:
    raise SystemExit("trusted verification job must not check out branch-controlled source")
if "CANARY_PUBLISH_TOKEN" in verify or "environment:" in verify:
    raise SystemExit("trusted verification job must not receive publication authority")
if "trusted/scripts/verify-canary-artifact.sh" not in verify:
    raise SystemExit("trusted verification job must independently inspect the downloaded artifact")
if "archive_sha256:" not in verify:
    raise SystemExit("trusted verification job must bind publication to the verified artifact digest")
if "test \"$(git rev-parse \"refs/tags/$CANARY_TAG^{commit}\")\" = \"$EXPECTED_SOURCE_SHA\"" not in verify:
    raise SystemExit("trusted verification must enforce the source tag digest")

publish_steps = [
    "Verify trusted workflow ref",
    "Download canary plugin artifact",
    "Revalidate canary tag provenance",
    "Validate trusted canary artifact",
    "Verify canary Marketplace token",
    "Publish trusted artifact to JetBrains Marketplace canary channel",
]
if [publish.index(step) for step in publish_steps] != sorted(publish.index(step) for step in publish_steps):
    raise SystemExit("canary publish workflow gate ordering is unsafe")
if "path: source" in publish or "working-directory: source" in publish:
    raise SystemExit("canary publish job must not check out or execute branch-controlled source")
if "contents: read" not in publish or "contents: write" in publish:
    raise SystemExit("Marketplace publication must not receive GitHub contents write authority")
if "needs: [build, verify]" not in publish:
    raise SystemExit("canary publication must depend on trusted artifact verification")
if "EXPECTED_ARCHIVE_SHA256: ${{ needs.verify.outputs.archive_sha256 }}" not in publish:
    raise SystemExit("canary publication must require the trusted verification digest")
if 'test "$actual_sha256" = "$EXPECTED_ARCHIVE_SHA256"' not in publish:
    raise SystemExit("canary publication must compare the downloaded artifact to the trusted digest")
if '--expected-sha256 "$EXPECTED_ARCHIVE_SHA256"' not in publish:
    raise SystemExit("Marketplace upload must recheck the independently verified artifact digest")
if "test \"$(git rev-parse \"refs/tags/$CANARY_TAG^{commit}\")\" = \"$EXPECTED_SOURCE_SHA\"" not in publish:
    raise SystemExit("canary publication must enforce the source tag digest")
if publish.index("EXPECTED_ARCHIVE_SHA256") > publish.index("Verify canary Marketplace token"):
    raise SystemExit("verified artifact identity must be checked before publication authority")

release_steps = [
    "Verify trusted workflow ref",
    "Check out trusted publication controls",
    "Download verified canary plugin artifact",
    "Revalidate verified artifact digest",
    "Revalidate canary tag provenance",
    "Create GitHub prerelease",
]
if [release.index(step) for step in release_steps] != sorted(release.index(step) for step in release_steps):
    raise SystemExit("canary GitHub release workflow ordering is unsafe")
if "needs: [build, verify, publish]" not in release:
    raise SystemExit("GitHub prerelease creation must follow successful Marketplace publication")
if "contents: write" not in release or "CANARY_PUBLISH_TOKEN" in release:
    raise SystemExit("GitHub release authority must remain isolated from Marketplace authority")
if "path: source" in release or "working-directory: source" in release:
    raise SystemExit("GitHub release job must not check out branch-controlled source")
if "persist-credentials: false" not in release:
    raise SystemExit("GitHub release trusted checkout must not persist credentials")
if 'test "$actual_sha256" = "$EXPECTED_ARCHIVE_SHA256"' not in release:
    raise SystemExit("GitHub prerelease must use the independently verified artifact")
if "test \"$(git rev-parse \"refs/tags/$CANARY_TAG^{commit}\")\" = \"$EXPECTED_SOURCE_SHA\"" not in release:
    raise SystemExit("GitHub prerelease must enforce the verified source tag digest")
PY

  assert_contains scripts/test-all.sh 'Plugin JaCoCo verification (0% minimum; report-only signal)'
  assert_contains scripts/test-all.sh 'Core coverage is below the configured 85% threshold'
  assert_contains scripts/test-all.sh 'MCP coverage is below the configured 85% threshold'
}

test_version_validator
test_canary_version_validator
test_canary_artifact_publication
test_marketplace_publication_policy
test_canary_branch_isolation
test_internal_api_allowlist
test_canary_verification_trust_boundary
test_release_script_flow
test_static_contracts

echo "Release contract tests passed."
