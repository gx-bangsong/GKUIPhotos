#!/usr/bin/env bash
set -Eeuo pipefail

readonly APP_NAME="GKUIPhotos"
readonly EXPECTED_APPLICATION_ID="org.lineageos.glimpse"
readonly SIGNER_FILE="release-signer.sha256"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
normalize_fingerprint() { tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]'; }

for variable in KEYSTORE_BASE64 KEYSTORE_PASS KEY_ALIAS KEY_PASS; do
    [[ -n "${!variable:-}" ]] || fail "Required secret is empty: $variable"
done

commit_hash="$(git rev-parse HEAD)" || fail "Cannot determine the source commit"
repository_slug="${GITHUB_REPOSITORY:-}"
server_url="${GITHUB_SERVER_URL:-https://github.com}"
source_url="${server_url}/${repository_slug}/tree/${commit_hash}"

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

keystore_path="${temporary_dir}/release.keystore"
printf '%s' "$KEYSTORE_BASE64" | base64 --decode >"$keystore_path" || fail "Invalid base64"
chmod 600 "$keystore_path"
export KEYSTORE_FILE="$keystore_path"
export CI=true

./gradlew --no-daemon assembleRelease

mapfile -t release_apks < <(find build/outputs/apk/release -maxdepth 1 -type f -name '*-release.apk' -print 2>/dev/null | sort)
source_apk="${release_apks[0]}"

badging="$(aapt dump badging "$source_apk")" || fail "aapt could not inspect the APK"
package_line="$(printf '%s\n' "$badging" | grep -m1 '^package:')"
# 注意：正则必须以 ^package: 开头，防止贪婪匹配到 compileSdkVersionCodename
application_id="$(printf '%s\n' "$package_line" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
version_code="$(printf '%s\n' "$package_line" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
version_name="$(printf '%s\n' "$package_line" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"

[[ "$application_id" == "$EXPECTED_APPLICATION_ID" ]] || fail "Unexpected applicationId: $application_id (expected $EXPECTED_APPLICATION_ID)"

signer_output="$(apksigner verify --verbose --print-certs "$source_apk" 2>&1)" || fail "Signature verification failed"
signer_sha256="$(printf '%s\n' "$signer_output" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1 | normalize_fingerprint)"

if [[ ! -f "$SIGNER_FILE" ]]; then
    printf 'Detected signer SHA-256: %s\n' "$signer_sha256" >&2
    fail "$SIGNER_FILE is missing; verify the fingerprint, add it, and rerun"
fi
expected_signer="$(grep -Ev '^[[:space:]]*(#|$)' "$SIGNER_FILE" | head -n 1 | normalize_fingerprint || true)"
[[ "$signer_sha256" == "$expected_signer" ]] || fail "Signing certificate mismatch"

rm -rf dist && mkdir -p dist
artifact_name="${APP_NAME}-${version_name}.apk"
artifact_path="dist/${artifact_name}"
cp "$source_apk" "$artifact_path"
apk_sha256="$(sha256sum "$artifact_path" | awk '{print $1}')"
printf '%s  %s\n' "$apk_sha256" "$artifact_name" >"${artifact_path}.sha256"

tag="v${version_name}"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'tag=%s\napplication_id=%s\nversion_code=%s\nversion_name=%s\nsigner_sha256=%s\napk_sha256=%s\nsource_url=%s\n' "$tag" "$application_id" "$version_code" "$version_name" "$signer_sha256" "$apk_sha256" "$source_url" >>"$GITHUB_OUTPUT"
fi
