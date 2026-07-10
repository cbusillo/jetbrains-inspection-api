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

  python3 - <<'PY'
from pathlib import Path

workflow = Path(".github/workflows/release.yml").read_text(encoding="utf-8")
validate = workflow.index("Validate release version")
default_branch = workflow.index("Verify tag commit is current default branch")
compatibility = workflow.index("Run release compatibility gate")
github_release = workflow.index("Create GitHub Release")
publish = workflow.index("Publish to JetBrains Marketplace")
if not validate < default_branch < compatibility < github_release < publish:
    raise SystemExit("release workflow validation/verifier/publish ordering is unsafe")
PY

  assert_contains scripts/test-all.sh 'Plugin JaCoCo verification (0% minimum; report-only signal)'
  assert_contains scripts/test-all.sh 'Core coverage is below the configured 85% threshold'
  assert_contains scripts/test-all.sh 'MCP coverage is below the configured 85% threshold'
}

test_version_validator
test_release_script_flow
test_static_contracts

echo "Release contract tests passed."
