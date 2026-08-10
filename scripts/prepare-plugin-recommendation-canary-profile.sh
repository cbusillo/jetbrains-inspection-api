#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
CANARY_TAG=""
EXPECTED_SHA256=""
EXPECTED_SOURCE_COMMIT=""
IDE_HOME=""
PLUGIN_ARCHIVE=""
PROFILE_ROOT=""
PROFILE_CREATED=0
TEMP_DIR=""

usage() {
  cat <<'USAGE'
Usage: scripts/prepare-plugin-recommendation-canary-profile.sh \
  --ide-home <IDE Contents directory> \
  --plugin-archive <verified canary zip> \
  --canary-tag <canary/vX.Y.Z-canary.N> \
  --expected-sha256 <64 lowercase hex characters> \
  --expected-source-commit <40 lowercase hex characters> \
  --profile-root <new directory>

Creates a disposable, non-GUI JetBrains 262 profile with a deterministic
advertiser cache for *.go and *.properties. The source fixture is copied into the
profile so qualification cannot create .idea or module files in the checkout.
USAGE
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

cleanup() {
  local status=$?
  if [ -n "$TEMP_DIR" ]; then
    rm -rf "$TEMP_DIR"
  fi
  if [ "$status" -ne 0 ] && [ "$PROFILE_CREATED" -eq 1 ]; then
    rm -rf "$PROFILE_ROOT"
  fi
  exit "$status"
}
trap cleanup EXIT

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ide-home)
      [ "$#" -ge 2 ] || fail "--ide-home requires a value"
      IDE_HOME=$2
      shift 2
      ;;
    --plugin-archive)
      [ "$#" -ge 2 ] || fail "--plugin-archive requires a value"
      PLUGIN_ARCHIVE=$2
      shift 2
      ;;
    --canary-tag)
      [ "$#" -ge 2 ] || fail "--canary-tag requires a value"
      CANARY_TAG=$2
      shift 2
      ;;
    --expected-sha256)
      [ "$#" -ge 2 ] || fail "--expected-sha256 requires a value"
      EXPECTED_SHA256=$2
      shift 2
      ;;
    --expected-source-commit)
      [ "$#" -ge 2 ] || fail "--expected-source-commit requires a value"
      EXPECTED_SOURCE_COMMIT=$2
      shift 2
      ;;
    --profile-root)
      [ "$#" -ge 2 ] || fail "--profile-root requires a value"
      PROFILE_ROOT=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

for command_name in git jq python3 rg rsync shasum unzip; do
  command -v "$command_name" >/dev/null 2>&1 || fail "Required command not found: $command_name"
done

[ -n "$IDE_HOME" ] || fail "--ide-home is required"
[ -n "$PLUGIN_ARCHIVE" ] || fail "--plugin-archive is required"
[ -n "$CANARY_TAG" ] || fail "--canary-tag is required"
[ -n "$EXPECTED_SHA256" ] || fail "--expected-sha256 is required"
[ -n "$EXPECTED_SOURCE_COMMIT" ] || fail "--expected-source-commit is required"
[ -n "$PROFILE_ROOT" ] || fail "--profile-root is required"
[[ "$EXPECTED_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "--expected-sha256 must be 64 lowercase hex characters"
[[ "$EXPECTED_SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail "--expected-source-commit must be 40 lowercase hex characters"
[[ "$CANARY_TAG" =~ ^canary/v([0-9]+\.[0-9]+\.[0-9]+)-canary\.([1-9][0-9]*)$ ]] || {
  fail "--canary-tag must use canary/vX.Y.Z-canary.N"
}
CANARY_VERSION="${BASH_REMATCH[1]}-canary.${BASH_REMATCH[2]}"

[ -d "$IDE_HOME" ] || fail "IDE home not found: $IDE_HOME"
IDE_HOME=$(cd "$IDE_HOME" && pwd)
[ -d "$IDE_HOME/lib" ] || fail "IDE library directory not found: $IDE_HOME/lib"
[ -f "$IDE_HOME/Resources/product-info.json" ] || fail "IDE product metadata not found"
[ -f "$PLUGIN_ARCHIVE" ] || fail "Plugin archive not found: $PLUGIN_ARCHIVE"
PLUGIN_ARCHIVE=$(cd "$(dirname "$PLUGIN_ARCHIVE")" && pwd)/$(basename "$PLUGIN_ARCHIVE")
PROFILE_ROOT=$(python3 -c 'import os, sys; print(os.path.abspath(sys.argv[1]))' "$PROFILE_ROOT")
[ ! -e "$PROFILE_ROOT" ] || fail "Profile root must not already exist: $PROFILE_ROOT"

ACTUAL_SHA256=$(shasum -a 256 "$PLUGIN_ARCHIVE" | awk '{print $1}')
[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
  fail "Plugin archive SHA-256 $ACTUAL_SHA256 does not match expected $EXPECTED_SHA256"
}
"$ROOT/scripts/validate-canary-artifact.sh" \
  --archive "$PLUGIN_ARCHIVE" \
  --tag "$CANARY_TAG" \
  --channel canary

TEMP_DIR=$(mktemp -d)
PLUGIN_JAR_PATH="jetbrains-inspection-api/lib/jetbrains-inspection-api-$CANARY_VERSION.jar"
unzip -p "$PLUGIN_ARCHIVE" "$PLUGIN_JAR_PATH" > "$TEMP_DIR/plugin.jar" || {
  fail "Plugin archive does not contain $PLUGIN_JAR_PATH"
}
BUILD_INFO_PATH="com/shiny/inspectionmcp/inspection-build.properties"
unzip -p "$TEMP_DIR/plugin.jar" "$BUILD_INFO_PATH" > "$TEMP_DIR/inspection-build.properties" || {
  fail "Plugin jar does not contain $BUILD_INFO_PATH"
}
SOURCE_COMMIT=$(sed -n 's/^plugin.build.commit=//p' "$TEMP_DIR/inspection-build.properties")
SOURCE_DIRTY=$(sed -n 's/^plugin.build.dirty=//p' "$TEMP_DIR/inspection-build.properties")
SOURCE_VERSION=$(sed -n 's/^plugin.version=//p' "$TEMP_DIR/inspection-build.properties")
[ "$SOURCE_COMMIT" = "$EXPECTED_SOURCE_COMMIT" ] || {
  fail "Embedded source commit $SOURCE_COMMIT does not match expected $EXPECTED_SOURCE_COMMIT"
}
[ "$SOURCE_DIRTY" = "false" ] || fail "Canary archive must embed plugin.build.dirty=false"
[ "$SOURCE_VERSION" = "$CANARY_VERSION" ] || {
  fail "Embedded plugin version $SOURCE_VERSION does not match $CANARY_VERSION"
}

BUILD_NUMBER=$(jq -r '.buildNumber // empty' "$IDE_HOME/Resources/product-info.json")
[[ "$BUILD_NUMBER" == 262.* ]] || fail "Expected a 262 IDE, found build '$BUILD_NUMBER'"
ENV_VAR_BASE_NAME=$(jq -r '.envVarBaseName // empty' "$IDE_HOME/Resources/product-info.json")
[ -n "$ENV_VAR_BASE_NAME" ] || fail "IDE environment metadata not found"
VM_OPTIONS_ENV="${ENV_VAR_BASE_NAME}_VM_OPTIONS"
REMOTE_DEV_SERVER="$IDE_HOME/bin/remote-dev-server.sh"
[ -x "$REMOTE_DEV_SERVER" ] || fail "Remote development server not found: $REMOTE_DEV_SERVER"

JDK_HOME="$IDE_HOME/jbr"
if [ -d "$JDK_HOME/Contents/Home" ]; then
  JDK_HOME="$JDK_HOME/Contents/Home"
fi
[ -x "$JDK_HOME/bin/java" ] || fail "IDE runtime java not found"
[ -x "$JDK_HOME/bin/javac" ] || fail "IDE runtime javac not found"

plugin_id_present() {
  local plugin_id=$1
  local descriptor jar plugin_xml
  while IFS= read -r -d '' descriptor; do
    if rg -q -F "<id>$plugin_id</id>" "$descriptor"; then
      return 0
    fi
  done < <(find "$IDE_HOME/plugins" -type f -path '*/META-INF/plugin.xml' -print0 2>/dev/null)
  while IFS= read -r -d '' jar; do
    plugin_xml=$(unzip -p "$jar" META-INF/plugin.xml 2>/dev/null || true)
    if [[ "$plugin_xml" == *"<id>$plugin_id</id>"* ]]; then
      return 0
    fi
  done < <(find "$IDE_HOME/plugins" -type f -name '*.jar' -print0)
  return 1
}

plugin_id_present "com.intellij.properties" || fail "Selected IDE does not bundle the Properties plugin"
if plugin_id_present "org.jetbrains.plugins.go"; then
  fail "Selected IDE bundles the Go plugin; the absent-plugin fixture would be invalid"
fi

mkdir -p \
  "$PROFILE_ROOT/config/options" \
  "$PROFILE_ROOT/system" \
  "$PROFILE_ROOT/plugins" \
  "$PROFILE_ROOT/log" \
  "$PROFILE_ROOT/registry" \
  "$PROFILE_ROOT/project" \
  "$PROFILE_ROOT/evidence"
PROFILE_CREATED=1

SOURCE_FIXTURE="$ROOT/test-fixtures/plugin-recommendation-canary"
rsync -a \
  --exclude '.idea/' \
  --exclude '*.iml' \
  "$SOURCE_FIXTURE/" \
  "$PROFILE_ROOT/project/"
FIXTURE_PROJECT="$PROFILE_ROOT/project"
FIXTURE_PROJECT_XML=$(printf '%s' "$FIXTURE_PROJECT" | python3 -c 'import html, sys; print(html.escape(sys.stdin.read(), quote=True))')

python3 - "$PLUGIN_ARCHIVE" "$PROFILE_ROOT/plugins" <<'PY'
from pathlib import Path, PurePosixPath
import stat
import sys
import zipfile

archive = Path(sys.argv[1])
destination = Path(sys.argv[2]).resolve()
with zipfile.ZipFile(archive) as plugin_zip:
    for entry in plugin_zip.infolist():
        path = PurePosixPath(entry.filename)
        mode = entry.external_attr >> 16
        if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
            raise SystemExit(f"ERROR: Unsafe archive entry: {entry.filename}")
        if stat.S_ISLNK(mode):
            raise SystemExit(f"ERROR: Symlink archive entry is not allowed: {entry.filename}")
    plugin_zip.extractall(destination)
PY

cat > "$PROFILE_ROOT/config/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths">
    <option name="TRUSTED_PROJECT_PATHS">
      <map>
        <entry key="$FIXTURE_PROJECT_XML" value="true" />
      </map>
    </option>
  </component>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="$FIXTURE_PROJECT_XML" />
      </list>
    </option>
  </component>
</application>
EOF

cat > "$PROFILE_ROOT/config/options/updates.xml" <<EOF
<application>
  <component name="UpdatesConfigurable">
    <option name="LAST_BUILD_CHECKED" value="$BUILD_NUMBER" />
    <option name="PLUGINS_AUTO_UPDATE" value="false" />
    <option name="WHATS_NEW_SHOWN_FOR" value="262" />
  </component>
</application>
EOF

cat > "$PROFILE_ROOT/config/options/ide.general.xml" <<'EOF'
<application>
  <component name="GeneralSettings">
    <option name="confirmExit" value="false" />
    <option name="showTipsOnStartup" value="false" />
  </component>
</application>
EOF

CLASS_OUTPUT="$TEMP_DIR/classes"
mkdir -p "$CLASS_OUTPUT"
"$JDK_HOME/bin/javac" \
  --release 25 \
  -cp "$IDE_HOME/lib/*" \
  -d "$CLASS_OUTPUT" \
  "$ROOT/scripts/support/PluginRecommendationCanaryProfileSeeder.java"
"$JDK_HOME/bin/java" \
  -cp "$CLASS_OUTPUT:$IDE_HOME/lib/*" \
  PluginRecommendationCanaryProfileSeeder write "$PROFILE_ROOT/config"

JVM_PROPERTY_PREFIX=-D
VM_OPTIONS_FILE="$PROFILE_ROOT/qualification.vm-options"
printf '%s\n' \
  "${JVM_PROPERTY_PREFIX}idea.config.path=$PROFILE_ROOT/config" \
  "${JVM_PROPERTY_PREFIX}idea.system.path=$PROFILE_ROOT/system" \
  "${JVM_PROPERTY_PREFIX}idea.plugins.path=$PROFILE_ROOT/plugins" \
  "${JVM_PROPERTY_PREFIX}idea.log.path=$PROFILE_ROOT/log" \
  "${JVM_PROPERTY_PREFIX}idea.suppressed.plugins.id=com.intellij.properties" \
  "${JVM_PROPERTY_PREFIX}idea.plugins.host=http://127.0.0.1:1" \
  "${JVM_PROPERTY_PREFIX}ide.no.platform.update=true" \
  "${JVM_PROPERTY_PREFIX}ide.show.tips.on.startup.default.value=false" \
  > "$VM_OPTIONS_FILE"

{
  echo '#!/usr/bin/env bash'
  echo 'set -euo pipefail'
  printf 'export JETBRAINS_INSPECTION_REGISTRY_DIR=%q\n' "$PROFILE_ROOT/registry"
  printf 'export %s=%q\n' "$VM_OPTIONS_ENV" "$VM_OPTIONS_FILE"
  echo 'export REMOTE_DEV_TRUST_PROJECTS=1'
  echo 'export REMOTE_DEV_NON_INTERACTIVE=1'
  printf 'exec %q run %q\n' "$REMOTE_DEV_SERVER" "$FIXTURE_PROJECT"
} > "$PROFILE_ROOT/launch-headless.sh"
chmod +x "$PROFILE_ROOT/launch-headless.sh"

jq -n \
  --arg canary_tag "$CANARY_TAG" \
  --arg canary_version "$CANARY_VERSION" \
  --arg expected_sha256 "$EXPECTED_SHA256" \
  --arg source_commit "$SOURCE_COMMIT" \
  --arg ide_home "$IDE_HOME" \
  --arg ide_build "$BUILD_NUMBER" \
  --arg plugin_archive "$PLUGIN_ARCHIVE" \
  --arg profile_root "$PROFILE_ROOT" \
  --arg fixture_project "$FIXTURE_PROJECT" \
  --arg headless_launcher "$PROFILE_ROOT/launch-headless.sh" \
  --arg registry_dir "$PROFILE_ROOT/registry" \
  --arg vm_options "$VM_OPTIONS_FILE" \
  '{
    schema_version: 1,
    canary_tag: $canary_tag,
    canary_version: $canary_version,
    artifact_sha256: $expected_sha256,
    source_commit: $source_commit,
    ide_home: $ide_home,
    ide_build: $ide_build,
    plugin_archive: $plugin_archive,
    profile_root: $profile_root,
    fixture_project: $fixture_project,
    headless_launcher: $headless_launcher,
    registry_dir: $registry_dir,
    vm_options: $vm_options
  }' > "$PROFILE_ROOT/qualification.json"

{
  printf 'CANARY_TAG=%q\n' "$CANARY_TAG"
  printf 'CANARY_VERSION=%q\n' "$CANARY_VERSION"
  printf 'CANARY_ARTIFACT_SHA256=%q\n' "$EXPECTED_SHA256"
  printf 'CANARY_SOURCE_COMMIT=%q\n' "$SOURCE_COMMIT"
  printf 'CANARY_IDE_HOME=%q\n' "$IDE_HOME"
  printf 'CANARY_IDE_BUILD=%q\n' "$BUILD_NUMBER"
  printf 'CANARY_PLUGIN_ARCHIVE=%q\n' "$PLUGIN_ARCHIVE"
  printf 'CANARY_PROFILE_ROOT=%q\n' "$PROFILE_ROOT"
  printf 'CANARY_FIXTURE_PROJECT=%q\n' "$FIXTURE_PROJECT"
  printf 'CANARY_HEADLESS_LAUNCHER=%q\n' "$PROFILE_ROOT/launch-headless.sh"
} > "$PROFILE_ROOT/qualification.env"

echo "Prepared deterministic canary profile: $PROFILE_ROOT"
echo "IDE build: $BUILD_NUMBER"
echo "Artifact SHA-256: $EXPECTED_SHA256"
echo "Source commit: $SOURCE_COMMIT"
echo "Headless launcher: $PROFILE_ROOT/launch-headless.sh"
echo "Fixture copy: $FIXTURE_PROJECT"
echo "Expected main.go: recommended / org.jetbrains.plugins.go / absent"
echo "Expected disabled.canary.properties: recommended / com.intellij.properties / disabled"
