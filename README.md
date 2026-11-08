<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

# GKUIPhotos (Glimpse)

> [!CAUTION]
> **Switching from a `dev`/`debug` version to a `release` version requires an uninstallation and
> fresh installation, which will result in data loss.** The packages have different application
> IDs (`org.lineageos.glimpse.dev` vs. `org.lineageos.glimpse`) and Android cannot treat them as an in-place upgrade.
>
> **从 `dev`/`debug` 版本切换到 `release` 版本需卸载重装，应用内私密数据会丢失。**
> 包名由 `org.lineageos.glimpse.dev` 变更为 `org.lineageos.glimpse`，系统无法进行原地覆盖更新。

---

## 概述 (Overview)

GKUIPhotos 基于 LineageOS 原生开源图库 **Glimpse** 构建，深度增强离线图像处理、多图工具箱、动态比例裁剪以及证件照标准化导出功能。

---

## 构建与发布 (Build and Release)

### Package and signature behavior

The production Gradle variant has application ID `org.lineageos.glimpse`. The development build
keeps the existing `org.lineageos.glimpse.dev` ID and Android debug signing, so
`./gradlew assembleDebug` remains convenient and can coexist with a production installation.

Every production release must use the same long-lived certificate. Releases signed by that fixed
certificate can upgrade one another without losing app data. Matching the LineageOS built-in
package name does **not** guarantee an upgrade over a ROM copy: Android also requires the ROM APK
and downloaded APK to have the same signing certificate.

### 1. Generate and protect the production keystore

Run this once on a trusted offline/admin machine. Choose strong, unique passwords and retain
multiple encrypted backups; losing this key prevents future in-place upgrades.

```bash
keytool -genkeypair -v \
  -keystore glimpse-release.jks \
  -storetype PKCS12 \
  -alias glimpse \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Never commit the keystore. `*.jks`, `*.keystore`, and `*.b64` are ignored by Git.

### 2. Configure GitHub Actions secrets

In **Repository settings → Secrets and variables → Actions**, add exactly these four repository
secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The complete keystore encoded as one base64 line |
| `KEYSTORE_PASS` | Keystore password |
| `KEY_ALIAS` | Key alias (for example, `glimpse`) |
| `KEY_PASS` | Private-key password |

Create the base64 value on GNU/Linux with:

```bash
base64 -w 0 glimpse-release.jks
```

On macOS or other systems without `-w`, use:

```bash
base64 < glimpse-release.jks | tr -d '\n'
```

Follow [`docs/WORKFLOW.md`](docs/WORKFLOW.md) and manually copy its workflow into
`.github/workflows/release.yml`. The repository owner selected `lineage-23.2`, the current default
branch, as the release trigger because this repository does not currently have a `main` branch.

### 3. Pin the expected signing certificate

`release-signer.sha256` is a public certificate fingerprint—not a private key. It prevents a
changed/replaced Actions keystore from silently publishing an APK under an unexpected identity.

The pinned production certificate fingerprint is:
```text
2eaa964c9eae1ee70e131a6ca5f8f06d7c1c152a037cac33d01f585c758f214a
```

On CI builds, `scripts/ci-build.sh` verifies the APK with `apksigner verify --print-certs` and enforces
that Signer #1 matches `release-signer.sha256` exactly. Changing production keys requires an explicit
reviewed change to this file and will break in-place upgrades from releases signed by the old key.

### 4. Versioning

The Gradle build derives `versionCode` from:

```bash
git rev-list --count HEAD
```

The release workflow checks out full history (`fetch-depth: 0`). On the non-rebased release branch,
the commit count increases monotonically without writing version files or commits back to the
repository. If Git is unavailable in a local checkout, the fallback is `1`; CI fails instead of
using that fallback.

By default, `versionName` is `<UTC date yyyyMMdd>.<versionCode>`, for example
`20261102.1234`. Set the `VERSION_NAME` environment variable to override it for a deliberate
manual build. The build never edits tracked source files, preventing release-trigger loops.

### 5. Local builds

Development is unchanged:

```bash
./gradlew assembleDebug
```

A local release build with no signing variables emits a warning and creates an **unsigned** APK;
it never falls back to the debug certificate:

```bash
./gradlew assembleRelease
```

To produce a locally signed release, export all four Gradle signing inputs:

```bash
export KEYSTORE_FILE="$PWD/glimpse-release.jks"
export KEYSTORE_PASS='...'
export KEY_ALIAS='glimpse'
export KEY_PASS='...'
./gradlew assembleRelease
```

`scripts/ci-build.sh` is stricter than direct local Gradle use: it requires the four GitHub-style
secret values, validates package/debuggable/signature/version metadata, compares the prior
release's `build-info.json`, and writes only ignored deliverables under `dist/`.

### Published files

A successful automated release contains:

- `Glimpse-<versionName>.apk`
- `Glimpse-<versionName>.apk.sha256`
- `build-info.json`

`build-info.json` records `applicationId`, `versionCode`, `versionName`, signer and APK SHA-256
hashes, the exact commit, and its source-code URL for GKUI-Pack and independent verification.

---

### 6. 权限规范与真机实测说明 (Permissions Notice)

关于 `android.permission.MANAGE_MEDIA` 权限的实际运行机制说明如下：
- **保护级别实测**：在真实 Android 设备上执行 `pm list permissions -f`，该权限的保护级别为 **`signature|appop|preinstalled`**。
- **普通用户应用安装**：作为普通应用安装时，系统不会在安装时自动授予该权限。应用启动时会通过 `PermissionsUtils` 引导用户进入系统设置页（`Settings.ACTION_REQUEST_MANAGE_MEDIA`）主动授权，或通过 root 终端执行 `appops set org.lineageos.glimpse MANAGE_MEDIA allow` 手动授权。未获得该权限时，应用在修改/删除媒体文件时会弹出系统原生确认对话框，全功能正常运行。
- **系统预装说明**：权限具有 `preinstalled` 标志，意味着将应用安装在 `/system` 系统分区下“可能”满足系统直接授予条件，此项**标记为待真机实测验证**，不作为确定性结论。
- **特别注意**：该权限**不具备** `privileged` 保护级别标志，因此放置在 `/system/priv-app` 并配置 privapp 白名单 XML 的方案对该权限**不适用**。

---

## 许可证 (License)

本项目遵循 Apache-2.0 许可证，衍生自 LineageOS 开源项目。
详情参阅仓库各源文件头部的 SPDX 声明。
