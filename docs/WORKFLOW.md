<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

# Automated Release Workflow Specification

This document provides the complete, production-grade GitHub Actions release workflow specifications for GKUIPhotos (Glimpse). Because external automated agents cannot directly push files under `.github/workflows/`, repository administrators must manually copy and paste the workflow configurations described below into `.github/workflows/release.yml`.

---

## 1. Prerequisites and Secrets Configuration

Before deploying this workflow, the repository owner must configure the following Repository Secrets under **Settings -> Secrets and variables -> Actions**:

| Secret Name | Description | Example / Format |
| :--- | :--- | :--- |
| `KEYSTORE_BASE64` | Base64-encoded string of the release PKCS12 / JKS keystore file | `cat release.jks \| base64 -w 0` |
| `KEYSTORE_PASS` | Password protecting the keystore | Plain text password |
| `KEY_ALIAS` | Key alias identifying the release signing keypair | e.g. `glimpse-release` |
| `KEY_PASS` | Password protecting the release private key | Plain text password |

### Release Signer Pinning (`release-signer.sha256`)
To prevent unexpected keystore replacements or unauthorized credential modifications, the build gate script (`scripts/ci-build.sh`) performs strict certificate fingerprint pinning:
1. On the very first release run, `ci-build.sh` detects the SHA-256 digest of Signer #1 from your keystore, prints it to the workflow log, and fails gracefully.
2. Paste this 64-character lowercase hexadecimal hash into `release-signer.sha256` in the repository root and commit it.
3. All subsequent CI builds will enforce that the generated APK's signer certificate exactly matches `release-signer.sha256`.

---

## 2. Option A: Complete Standalone Release Workflow

Save the following YAML content directly as `.github/workflows/release.yml`:

```yaml
name: Release APK

# Triggers when code is merged into the default branch (lineage-23.2)
# Excludes pure documentation updates to avoid triggering unnecessary release builds.
on:
  push:
    branches:
      - lineage-23.2
    paths-ignore:
      - 'docs/**'
      - '*.md'
  workflow_dispatch:

# Ensure only one release workflow runs at a time to prevent race conditions during tagging
concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

# Required to create GitHub Releases, tag commits, and upload distribution assets
permissions:
  contents: write

jobs:
  release:
    name: Build & Publish Release APK
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      # Step 1: Full checkout with entire git history
      # fetch-depth: 0 is mandatory so that `git rev-list --count HEAD` counts all commits
      # from repository inception, guaranteeing a strictly monotonic versionCode.
      - name: Checkout repository
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      # Step 2: Configure Zulu OpenJDK 17 with Gradle dependency caching
      - name: Setup JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: 'zulu'
          java-version: 17
          cache: 'gradle'

      # Step 3: Install Android build-tools to ensure aapt and apksigner binaries are accessible
      - name: Setup Android Build Tools
        run: |
          set +e
          yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1
          "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "build-tools;36.0.0" > /dev/null 2>&1
          exit 0

      # Step 4: Execute the gated release build script
      # scripts/ci-build.sh decodes the keystore, invokes assembleRelease, verifies metadata
      # (no .dev suffix, debuggable=false, pinned signer SHA-256, monotonic versionCode),
      # and outputs standardized artifacts into dist/.
      - name: Build & Verify Release APK
        id: build
        run: ./scripts/ci-build.sh
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
          KEYSTORE_PASS: ${{ secrets.KEYSTORE_PASS }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASS: ${{ secrets.KEY_PASS }}
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          CI: "true"

      # Step 5: Publish GitHub Release with GPL compliance links and upgrade notice
      - name: Create Release & Upload Artifacts
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ steps.build.outputs.tag }}
          APP_ID: ${{ steps.build.outputs.app_id }}
          VERSION_CODE: ${{ steps.build.outputs.version_code }}
          VERSION_NAME: ${{ steps.build.outputs.version_name }}
          SIGNER_SHA256: ${{ steps.build.outputs.signer_sha256 }}
          APK_SHA256: ${{ steps.build.outputs.apk_sha256 }}
        run: |
          cat > release_notes.md <<EOF
          ## Release ${TAG}

          ### 📦 Application & Build Information
          - **Application ID**: \`${APP_ID}\`
          - **Version Name**: \`${VERSION_NAME}\`
          - **Version Code**: \`${VERSION_CODE}\`
          - **Commit**: [\`${GITHUB_SHA::8}\`](${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA})
          - **Source Tree**: [Browse at commit ${GITHUB_SHA::8}](${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/tree/${GITHUB_SHA})
          - **License**: Apache-2.0 / LineageOS

          ### 🔐 Signatures & Integrity Verification
          - **APK SHA-256**:
            \`\`\`
            ${APK_SHA256}
            \`\`\`
          - **Signer #1 Certificate SHA-256**:
            \`\`\`
            ${SIGNER_SHA256}
            \`\`\`

          ---

          ### ⚠️ 重要升级与迁移提示 (Upgrade Notice)
          > **本版本采用固定正式 Release 密钥签名（applicationId: \`${APP_ID}\`）。**
          > 与此前 Releases 发布的开发/调试版本（含 \`.dev\` 包名后缀及 debug keystore 签名）**无法直接覆盖更新**。
          > 首次从 debug/dev 版本切换到正式 Release 版本时，系统会提示签名不一致，**必须先备份重要数据并彻底卸载旧版，然后重新安装**。
          EOF

          gh release create "${TAG}" dist/* \
            --title "Release ${TAG}" \
            --notes-file release_notes.md \
            --generate-notes
```

---

## 3. Option B: Reusable Workflow Pattern

If you prefer modularity or manage multiple repositories with identical release steps, you can split the configuration into a reusable workflow and a compact caller workflow.

### File 1: `.github/workflows/reusable-release.yml`
```yaml
name: Reusable Release Workflow

on:
  workflow_call:
    secrets:
      KEYSTORE_BASE64:
        required: true
      KEYSTORE_PASS:
        required: true
      KEY_ALIAS:
        required: true
      KEY_PASS:
        required: true

jobs:
  build-and-release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v5
        with:
          distribution: 'zulu'
          java-version: 17
          cache: 'gradle'
      - name: Setup Android Build Tools
        run: |
          set +e
          yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1
          "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "build-tools;36.0.0" > /dev/null 2>&1
          exit 0
      - name: Build & Verify Release APK
        id: build
        run: ./scripts/ci-build.sh
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
          KEYSTORE_PASS: ${{ secrets.KEYSTORE_PASS }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASS: ${{ secrets.KEY_PASS }}
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          CI: "true"
      - name: Create Release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ steps.build.outputs.tag }}
          APP_ID: ${{ steps.build.outputs.app_id }}
          VERSION_CODE: ${{ steps.build.outputs.version_code }}
          VERSION_NAME: ${{ steps.build.outputs.version_name }}
          SIGNER_SHA256: ${{ steps.build.outputs.signer_sha256 }}
          APK_SHA256: ${{ steps.build.outputs.apk_sha256 }}
        run: |
          gh release create "${TAG}" dist/* \
            --title "Release ${TAG}" \
            --notes "Release ${TAG} (versionCode: ${VERSION_CODE}, Signer: ${SIGNER_SHA256})" \
            --generate-notes
```

### File 2: `.github/workflows/release-caller.yml` (10-Line Caller)
```yaml
name: Release Caller
on:
  push:
    branches: [ lineage-23.2 ]
    paths-ignore: [ 'docs/**', '*.md' ]
  workflow_dispatch:
jobs:
  release:
    uses: gx-bangsong/GKUIPhotos/.github/workflows/reusable-release.yml@lineage-23.2
    secrets: inherit
    permissions:
      contents: write
```

---

## 4. Step-by-Step Technical Rationale

1. **`fetch-depth: 0` in `actions/checkout`**:
   Default GitHub Actions checkout uses shallow clones (`depth: 1`). A shallow clone only sees the tip commit, causing `git rev-list --count HEAD` to evaluate to `1`. Full history ensures accurate, monotonically increasing commit counts.
2. **`build-tools;36.0.0` installation**:
   Guarantees that `aapt` and `apksigner` binaries are installed and accessible by `scripts/ci-build.sh` regardless of the host runner image version.
3. **Execution in `scripts/ci-build.sh`**:
   Encapsulates all cryptographic decoding, validation rules, gate checks, and packaging into a testable bash script under version control, keeping workflow files minimal and declarative.
4. **`CI: "true"` environment flag**:
   Enforces the Gradle build script rule: if signing credentials are not supplied in CI, the build immediately aborts with `GradleException`, preventing the generation of unverified or debug-signed release artifacts.
5. **Release metadata and source code link**:
   Fulfills open-source distribution transparency and licensing best practices (Apache-2.0 / LineageOS) by linking directly to the exact source commit from which the binary was produced.
