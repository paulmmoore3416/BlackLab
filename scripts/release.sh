#!/usr/bin/env bash
# One-command release for BlackLab.
#
#   scripts/release.sh 5.11.0              bump the version, run every check, build + stage the signed APK
#   scripts/release.sh 5.11.0 --install    …and install it on the phone over adb
#   scripts/release.sh 5.11.0 --publish    …and tag, push the tag and create the GitHub Release
#                                          (APK + .sha256 attached, notes taken from CHANGELOG.md)
#
# Steps, in order (any failure stops the release):
#   1. version bump in app/build.gradle.kts (versionCode +1, versionName = X.Y.Z; skipped if already set)
#   2. CHANGELOG.md must have a "## [X.Y.Z]" section — it becomes the release notes
#   3. secret scan + privacy scan
#   4. unit tests + lint (release build, R8)
#   5. releases/BlackLab-vX.Y.Z.apk + .sha256 (the in-app updater requires both, sha256sum format)
#   6. optional: adb install / git tag + gh release create
#
# Environment: JAVA_HOME (defaults to the user-space toolchain), PHONE_SERIAL (defaults to the first
# non-emulator adb device). It never commits for you: commit the bump + changelog before --publish.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

version="${1:-}"; shift || true
install=0; publish=0
for a in "$@"; do
  case "$a" in
    --install) install=1 ;;
    --publish) publish=1 ;;
    *) echo "Unknown option: $a" >&2; exit 2 ;;
  esac
done
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: scripts/release.sh X.Y.Z [--install] [--publish]" >&2; exit 2
fi

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/blacklab-toolchain/jdk-21.0.12.1+1}"
gradle_file=app/build.gradle.kts
apk_name="BlackLab-v$version.apk"
step() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

step "1/6 Version"
current_name=$(grep -oP 'versionName = "\K[^"]+' "$gradle_file")
current_code=$(grep -oP 'versionCode = \K[0-9]+' "$gradle_file")
if [ "$current_name" = "$version" ]; then
  echo "Already at $version (versionCode $current_code)."
else
  newest=$(printf '%s\n%s\n' "$current_name" "$version" | sort -V | tail -1)
  [ "$newest" = "$version" ] || die "$version is older than the current $current_name"
  new_code=$((current_code + 1))
  sed -i "s/versionCode = $current_code/versionCode = $new_code/; s/versionName = \"$current_name\"/versionName = \"$version\"/" "$gradle_file"
  echo "$current_name ($current_code) → $version ($new_code)"
fi

step "2/6 Changelog"
notes=$(awk -v v="$version" '
  $0 ~ "^## \\[" v "\\]" { on = 1; next }
  on && /^## \[/ { exit }
  on { print }' CHANGELOG.md)
[ -n "${notes//[[:space:]]/}" ] || die "CHANGELOG.md has no \"## [$version]\" section"
echo "$(printf '%s\n' "$notes" | grep -c .) lines of release notes."

step "3/6 Secret + privacy scans"
scripts/secret-scan.sh
scripts/privacy-scan.sh

step "4/6 Unit tests, lint, release build"
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --quiet
built=app/build/outputs/apk/release/app-release.apk
[ -f "$built" ] || die "No release APK at $built"
if [ ! -f keystore/keystore.properties ]; then
  echo "⚠ keystore/keystore.properties is missing: this APK is signed with the DEBUG key and can't update the installed app."
  [ "$publish" -eq 0 ] || die "Refusing to publish a debug-signed APK"
fi

step "5/6 Stage releases/$apk_name"
mkdir -p releases
cp "$built" "releases/$apk_name"
(cd releases && sha256sum "$apk_name" > "$apk_name.sha256")
cat "releases/$apk_name.sha256"

if [ "$install" -eq 1 ]; then
  step "Install on the phone"
  serial="${PHONE_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')}"
  [ -n "$serial" ] || die "No phone connected over adb (set PHONE_SERIAL)"
  adb -s "$serial" install -r "releases/$apk_name"
fi

if [ "$publish" -eq 1 ]; then
  step "6/6 Tag + GitHub Release"
  git diff --quiet && git diff --cached --quiet || die "Commit the version bump and changelog first (working tree is dirty)"
  tag="v$version"
  git rev-parse -q --verify "refs/tags/$tag" >/dev/null || git tag -a "$tag" -m "BlackLab $version"
  git push origin "$tag"
  title="BlackLab $version"
  printf '%s\n' "$notes" > "releases/.notes-$version.md"
  gh release create "$tag" "releases/$apk_name" "releases/$apk_name.sha256" \
    --title "$title" --notes-file "releases/.notes-$version.md" --latest
  rm -f "releases/.notes-$version.md"
  echo "Published: $(gh release view "$tag" --json url -q .url)"
else
  echo
  echo "Staged. Next: commit, then  scripts/release.sh $version --publish  (or --install to put it on the phone)."
fi
