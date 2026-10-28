#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

set -euo pipefail

# Ensure working directory is the repository root
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TMP_KEYSTORE=""
TMP_PREV_DIR=""

cleanup() {
    if [[ -n "${TMP_KEYSTORE}" && -f "${TMP_KEYSTORE}" ]]; then
        rm -f "${TMP_KEYSTORE}"
    fi
    if [[ -n "${TMP_PREV_DIR}" && -d "${TMP_PREV_DIR}" ]]; then
        rm -rf "${TMP_PREV_DIR}"
    fi
}
trap cleanup EXIT INT TERM

echo "=== Step 1: Decode Keystore ==="
if [[ -z "${KEYSTORE_BASE64:-}" ]]; then
    echo "::error::Environment variable KEYSTORE_BASE64 is empty or not set!" >&2
    exit 1
fi
if [[ -z "${KEYSTORE_PASS:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASS:-}" ]]; then
    echo "::error::One or more keystore credentials (KEYSTORE_PASS, KEY_ALIAS, KEY_PASS) are missing!" >&2
    exit 1
fi

TMP_KEYSTORE="$(mktemp -t release-keystore.XXXXXX.jks)"
echo "${KEYSTORE_BASE64}" | base64 -d > "${TMP_KEYSTORE}"
export KEYSTORE_FILE="${TMP_KEYSTORE}"
export KEYSTORE_PASS
export KEY_ALIAS
export KEY_PASS
export CI="true"

echo "=== Step 2: Build Release APK ==="
chmod +x ./gradlew
./gradlew assembleRelease --stacktrace

RAW_APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "${RAW_APK}" ]]; then
    echo "::error::Release APK not found at expected location: ${RAW_APK}" >&2
    exit 1
fi

echo "=== Step 3: Locate Android Build Tools ==="
find_build_tool() {
    local tool_name="$1"
    if command -v "${tool_name}" >/dev/null 2>&1; then
        command -v "${tool_name}"
        return 0
    fi
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/build-tools" ]]; then
        local found
        found="$(find "${ANDROID_HOME}/build-tools" -maxdepth 2 -name "${tool_name}" -type f -perm -111 2>/dev/null | sort -V | tail -n 1)"
        if [[ -n "${found}" && -x "${found}" ]]; then
            echo "${found}"
            return 0
        fi
    fi
    return 1
}

AAPT="$(find_build_tool aapt || true)"
if [[ -z "${AAPT}" ]]; then
    echo "::error::aapt build tool not found in PATH or ANDROID_HOME/build-tools!" >&2
    exit 1
fi

APKSIGNER="$(find_build_tool apksigner || true)"
if [[ -z "${APKSIGNER}" ]]; then
    echo "::error::apksigner tool not found in PATH or ANDROID_HOME/build-tools!" >&2
    exit 1
fi

echo "Using aapt:      ${AAPT}"
echo "Using apksigner: ${APKSIGNER}"

echo "=== Step 4: Gate Check - APK Metadata & Debuggable ==="
BADGING_OUTPUT="$("${AAPT}" dump badging "${RAW_APK}")"

PACKAGE_LINE="$(echo "${BADGING_OUTPUT}" | grep -m 1 "^package: name=" || true)"
if [[ -z "${PACKAGE_LINE}" ]]; then
    echo "::error::Failed to retrieve package information from APK badging!" >&2
    exit 1
fi

APP_ID="$(echo "${PACKAGE_LINE}" | sed -E "s/.*name='([^']*)'.*/\1/")"
VERSION_CODE="$(echo "${PACKAGE_LINE}" | sed -E "s/.*versionCode='([^']*)'.*/\1/")"
VERSION_NAME="$(echo "${PACKAGE_LINE}" | sed -E "s/.*versionName='([^']*)'.*/\1/")"

if [[ -z "${APP_ID}" || -z "${VERSION_CODE}" || -z "${VERSION_NAME}" ]]; then
    echo "::error::Failed to parse applicationId, versionCode, or versionName from APK!" >&2
    exit 1
fi

echo "APK Inspection Results:"
echo "  applicationId: ${APP_ID}"
echo "  versionCode:   ${VERSION_CODE}"
echo "  versionName:   ${VERSION_NAME}"

# Gate 1: applicationId must not contain .dev or .debug
if [[ "${APP_ID}" == *".dev"* || "${APP_ID}" == *".debug"* ]]; then
    echo "::error::Gate check failed: applicationId '${APP_ID}' contains .dev or .debug suffix!" >&2
    exit 1
fi

# Gate 2: debuggable must not be true
if echo "${BADGING_OUTPUT}" | grep -q "application-debuggable"; then
    echo "::error::Gate check failed: APK is marked as application-debuggable!" >&2
    exit 1
fi

echo "=== Step 5: Gate Check - Certificate Fingerprint Pinning ==="
CERTS_OUTPUT="$("${APKSIGNER}" verify --print-certs "${RAW_APK}")"
SIGNER_SHA256="$(echo "${CERTS_OUTPUT}" | grep -m 1 "Signer #1 certificate SHA-256 digest:" | sed -E 's/.*digest:\s*([0-9a-fA-F]+).*/\1/' | tr '[:upper:]' '[:lower:]' || true)"

if [[ -z "${SIGNER_SHA256}" ]]; then
    echo "::error::Gate check failed: Unable to extract Signer #1 SHA-256 certificate digest from APK!" >&2
    exit 1
fi

echo "  Actual Signer #1 SHA-256: ${SIGNER_SHA256}"

FINGERPRINT_FILE="${REPO_ROOT}/release-signer.sha256"
EXPECTED_SHA256=""
if [[ -f "${FINGERPRINT_FILE}" ]]; then
    EXPECTED_SHA256="$(grep -v '^[[:space:]]*#' "${FINGERPRINT_FILE}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]' || true)"
fi

if [[ -z "${EXPECTED_SHA256}" ]]; then
    echo "::error::Release certificate pin file '${FINGERPRINT_FILE}' is missing or does not contain a valid SHA-256 hash!" >&2
    echo "::notice::Detected Signer #1 SHA-256 fingerprint: ${SIGNER_SHA256}"
    echo "ACTION REQUIRED: Please write '${SIGNER_SHA256}' into '${FINGERPRINT_FILE}' to lock release signing." >&2
    exit 1
fi

if [[ "${SIGNER_SHA256}" != "${EXPECTED_SHA256}" ]]; then
    echo "::error::Gate check failed: Certificate fingerprint mismatch!" >&2
    echo "  Expected (from ${FINGERPRINT_FILE}): ${EXPECTED_SHA256}" >&2
    echo "  Actual (from APK):                 ${SIGNER_SHA256}" >&2
    exit 1
fi
echo "Certificate fingerprint matched successfully."

echo "=== Step 6: Gate Check - Monotonic versionCode Progression ==="
PREV_VERSION_CODE=""
if command -v gh >/dev/null 2>&1; then
    TMP_PREV_DIR="$(mktemp -d -t prev-release.XXXXXX)"
    if gh release download --pattern "build-info.json" --dir "${TMP_PREV_DIR}" 2>/dev/null; then
        if [[ -f "${TMP_PREV_DIR}/build-info.json" ]]; then
            PREV_VERSION_CODE="$(grep -m 1 '"versionCode":' "${TMP_PREV_DIR}/build-info.json" | sed -E 's/.*"versionCode":\s*([0-9]+).*/\1/' || true)"
        fi
    fi
fi

if [[ -n "${PREV_VERSION_CODE}" && "${PREV_VERSION_CODE}" =~ ^[0-9]+$ ]]; then
    echo "Previous release versionCode: ${PREV_VERSION_CODE}"
    echo "Current build versionCode:    ${VERSION_CODE}"
    if (( VERSION_CODE <= PREV_VERSION_CODE )); then
        echo "::error::Gate check failed: Current versionCode (${VERSION_CODE}) is not strictly greater than previous release versionCode (${PREV_VERSION_CODE})!" >&2
        exit 1
    fi
    echo "versionCode progression check passed: ${VERSION_CODE} > ${PREV_VERSION_CODE}."
else
    echo "No prior release build-info.json found or gh CLI not available. Skipping versionCode progression check."
fi

echo "=== Step 7: Packaging Artifacts & build-info.json ==="
DIST_DIR="${REPO_ROOT}/dist"
mkdir -p "${DIST_DIR}"

APP_PREFIX="Glimpse"
FINAL_APK_NAME="${APP_PREFIX}-${VERSION_NAME}.apk"
FINAL_APK_PATH="${DIST_DIR}/${FINAL_APK_NAME}"

cp "${RAW_APK}" "${FINAL_APK_PATH}"

(cd "${DIST_DIR}" && sha256sum "${FINAL_APK_NAME}" > "${FINAL_APK_NAME}.sha256")
APK_SHA256="$(awk '{print $1}' "${DIST_DIR}/${FINAL_APK_NAME}.sha256")"

COMMIT_SHA="$(git rev-parse HEAD)"
GITHUB_SERVER="${GITHUB_SERVER_URL:-https://github.com}"
GITHUB_REPO="${GITHUB_REPOSITORY:-gx-bangsong/GKUIPhotos}"
SOURCE_URL="${GITHUB_SERVER}/${GITHUB_REPO}/tree/${COMMIT_SHA}"

cat > "${DIST_DIR}/build-info.json" <<EOF
{
  "applicationId": "${APP_ID}",
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "signer_sha256": "${SIGNER_SHA256}",
  "apk_sha256": "${APK_SHA256}",
  "commit": "${COMMIT_SHA}",
  "source_url": "${SOURCE_URL}"
}
EOF

echo "=== Step 8: Exporting Workflow Outputs ==="
TAG_NAME="v${VERSION_NAME}"
echo "Generated release artifacts in dist/:"
ls -la "${DIST_DIR}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        echo "tag=${TAG_NAME}"
        echo "version_name=${VERSION_NAME}"
        echo "version_code=${VERSION_CODE}"
        echo "app_id=${APP_ID}"
        echo "signer_sha256=${SIGNER_SHA256}"
        echo "apk_sha256=${APK_SHA256}"
    } >> "${GITHUB_OUTPUT}"
fi

echo "ci-build.sh completed successfully. Ready to release ${TAG_NAME}."
