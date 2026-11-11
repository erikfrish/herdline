#!/usr/bin/env bash
# Modified for Herdline (2026). Prepare a verified, untested GitHub Release candidate.
# Usage: scripts/release-manifest.sh APK OUT_DIR NOTES_EN [NOTES_ZH]
set -euo pipefail
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo 'usage: scripts/release-manifest.sh APK OUT_DIR NOTES_EN [NOTES_ZH]' >&2
  exit 2
fi
apk=$1
out=$2
notes_en=$3
notes_zh=${4:-}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk ]]; then
  echo 'Set ANDROID_HOME to the Android SDK' >&2
  exit 1
fi
tools=$sdk/build-tools/36.0.0
for tool in aapt2 apksigner; do
  test -x "$tools/$tool" || { echo "Missing Android build tool: $tool" >&2; exit 1; }
done
test -s "$notes_en" || { echo 'Release notes are required' >&2; exit 1; }
certs=$("$tools/apksigner" verify --print-certs "$apk")
signer=$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
expected_signer=016e2d4c697bf40ce13786792318d40866a3cf21de37168a6616dbbada22c794
if [[ $signer != "$expected_signer" ]] || printf '%s\n' "$certs" | grep -q '^Signer #2'; then
  echo 'APK must have the Herdline release signer, not a debug or foreign signer' >&2
  exit 1
fi
badging=$("$tools/aapt2" dump badging "$apk")
package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
version_code=$(sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")
version_name=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
min_sdk=$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging")
if [[ $package != io.github.erikfrish.herdline || ! $version_code =~ ^[1-9][0-9]*$ || ! $version_name =~ ^[0-9A-Za-z.-]{1,32}$ || ! $min_sdk =~ ^[0-9]+$ ]]; then
  echo 'Invalid Herdline APK package/version/SDK identity' >&2
  exit 1
fi
mkdir -p "$out"
name=herdline-$version_name.apk
if [[ -e $out/$name ]]; then
  cmp -s "$apk" "$out/$name" || { echo 'Refusing to overwrite a different versioned APK' >&2; exit 1; }
else
  cp "$apk" "$out/$name"
fi
sha256=$(sha256sum "$out/$name" | cut -d' ' -f1)
size=$(stat -c %s "$out/$name")
notes_zh_text=''
if [[ -n $notes_zh ]]; then notes_zh_text=$(cat "$notes_zh"); fi
jq -n --arg package "$package" --arg signer "$signer" \
  --argjson versionCode "$version_code" --arg versionName "$version_name" \
  --arg url "https://github.com/erikfrish/herdline/releases/download/v$version_name/$name" \
  --arg sha256 "$sha256" --argjson size "$size" --argjson minSdk "$min_sdk" \
  --arg en "$(cat "$notes_en")" --arg zh "$notes_zh_text" \
  '{schema:1,package:$package,signerSha256:$signer,stable:null,candidate:{versionCode:$versionCode,versionName:$versionName,url:$url,sha256:$sha256,size:$size,minSdk:$minSdk,qa:{state:"not_tested",reason:"Developer checked; no runtime QA"},accepted:false,notes:({en:$en,zh:$zh}|with_entries(select(.value!="")))}}' \
  > "$out/latest.json"
chmod 444 "$out/$name"
printf '%s\n' "$out/$name" "$out/latest.json"
