<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

# GKUIPhotos (Glimpse)

> ⚠️ **重要版本升级与数据保留说明 (Upgrade & Data Migration Notice)**
>
> 从此前 GitHub Releases 发布的开发/调试版本（包名带 `.dev` 后缀且为 debug keystore 签名，例如 `org.lineageos.glimpse.dev`）切换至正式 Release 版本（包名 `org.lineageos.glimpse`，正式 Release 签名）时：
> 受限于 Android 系统的安全机制，**不同签名和不同包名的应用绝对无法直接覆盖更新**。
> 首次切换至正式 Release 版本前，**必须先备份应用内私密数据（如证件夹与私密相册）并彻底卸载旧版**，随后重新安装 Release 安装包。

---

## 概述 (Overview)

GKUIPhotos 基于 LineageOS 原生开源图库 **Glimpse** 构建，深度增强离线图像处理、多图工具箱、动态比例裁剪以及证件照标准化导出功能。

---

## 构建与发布 (Build & Release)

### 1. 签名密钥生成与 Base64 编码

生成正式 Release 签名密钥库（PKCS12 / JKS）：

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias glimpse-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

将生成的密钥库文件转换为单行 Base64 字符串供 CI 使用：

```bash
# Linux
cat release.jks | base64 -w 0

# macOS
cat release.jks | base64
```

### 2. GitHub Secrets 配置

在 GitHub 仓库 **Settings -> Secrets and variables -> Actions** 中配置以下 4 个加密环境变量：

- `KEYSTORE_BASE64`: 上述生成的单行 Base64 密钥库文本；
- `KEYSTORE_PASS`: 密钥库访问密码；
- `KEY_ALIAS`: 密钥别名（如 `glimpse-release`）；
- `KEY_PASS`: 私钥访问密码。

### 3. 证书指纹锁定 (`release-signer.sha256`)

为防止密钥库被意外替换或 CI 供应链篡改，项目引入了证书指纹强校验机制：
1. 配置好 Secrets 后首次触发 Release 工作流，`scripts/ci-build.sh` 会从生成的 APK 中提取 Signer #1 证书的 SHA-256 指纹，在工作流日志中打印并退出。
2. 将该 64 位十六进制指纹字符串写入项目根目录下的 `release-signer.sha256` 文件并提交。
3. 之后所有 CI 构建在发布前均强制比对该指纹，任何指纹不符的构建将直接阻断退出。

### 4. 版本号单调递增策略 (`versionCode` & `versionName`)

- **`versionCode`**：采用 `git rev-list --count HEAD`（代码库全量提交总数）作为版本代码。在默认分支（`lineage-23.2`）上，提交数严格单调递增，天然满足 Android 包管理器（PackageManager）版本比对与升级要求。本地无 Git 环境或命令异常时安全回退为 `1`。
- **`versionName`**：默认格式为 `<yyyyMMdd>.<versionCode>`（例如 `20261028.42`），并支持通过环境变量 `VERSION_NAME` 手动覆盖。
- **构建防循环保证**：Gradle 构建过程仅在内存中派生版本号，严格禁止向仓库树回写任何文件，避免触发二次 Git 提交导致 CI 死循环。

### 5. 本地开发与 CI 严格隔离

- **本地开发**：未配置签名环境变量时，本地执行 `./gradlew assembleDebug` 或 `./gradlew assembleRelease` 会安全回退至使用 Android 默认 debug 密钥签名，并输出警告日志，完全不阻碍本地离线调试。
- **CI 环境**：当检测到 `CI=true` 且执行 release 变体构建时，若签名环境变量缺失或无效，构建将立即抛出 `GradleException` 强行阻断，坚决杜绝发布未签名或 debug 签名的 release 包。

### 6. 权限规范与真机实测说明

关于 `android.permission.MANAGE_MEDIA` 权限的实际运行机制说明如下：
- **保护级别实测**：在真实 Android 设备上执行 `pm list permissions -f`，该权限的保护级别为 **`signature|appop|preinstalled`**。
- **普通用户应用安装**：作为普通应用安装时，系统不会在安装时自动授予该权限。应用启动时会通过 `PermissionsUtils` 引导用户进入系统设置页（`Settings.ACTION_REQUEST_MANAGE_MEDIA`）主动授权，或通过 root 终端执行 `appops set org.lineageos.glimpse MANAGE_MEDIA allow` 手动授权。未获得该权限时，应用在修改/删除媒体文件时会弹出系统原生确认对话框，全功能正常运行。
- **系统预装说明**：权限具有 `preinstalled` 标志，意味着将应用安装在 `/system` 系统分区下“可能”满足系统直接授予条件，此项**标记为待真机实测验证**，不作为确定性结论。
- **特别注意**：该权限**不具备** `privileged` 保护级别标志，因此放置在 `/system/priv-app` 并配置 privapp 白名单 XML 的方案对该权限**不适用**。

---

## 许可证 (License)

本项目遵循 Apache-2.0 许可证，衍生自 LineageOS 开源项目。
详情参阅仓库各源文件头部的 SPDX 声明。
