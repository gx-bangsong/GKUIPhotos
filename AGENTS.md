# AGENTS.md — GKUI 应用仓库约定

本文件对在本仓库工作的所有自动化 agent 生效。agent 每次开始任务前必须完整阅读本文件。
与任务指令冲突时，以任务指令中**明确引用本文件并声明覆盖**的条款为准；未声明覆盖的，本文件优先。

## 0. 本仓库参数（每个仓库各自填写，其余章节五个仓库完全一致）

| 项 | 值 |
|---|---|
| 仓库 | gx-bangsong/GKUIPhotos |
| 应用名（产物前缀） | Glimpse（如 ExactCalculator / Etar / Glimpse / DeskClock / Recorder） |
| 默认分支 | lineage-23.2（跟随 LineageOS 上游命名，如 lineage-23.2；**不创建 main**） |
| 上游 | LineageOS/android_packages_apps_Glimpse |
| 生产 applicationId | org.lineageos.glimpse（与 LineageOS 内置版本一致；见 §3） |
| debug applicationId | org.lineageos.glimpse.dev（保持现状，不改） |
| 许可证 | 跟随上游（Apache-2.0 或 GPL-3.0），不得更改 |
| 参考实现 | gx-bangsong/GKUICalculator（已由 CI 实测可用） |

## 1. 事实优先，禁止推断

- 包名、路径、指纹、版本号、保护级别等一律来自实测（aapt / apksigner / 设备 pm 输出 / CI 日志）。
  不确定的值写 `TODO` 并让构建失败，**严禁编造**。
- 禁止从仓库名、上游名、命名规律推断 applicationId。已知反例：GKUICalendar fork 自 LineageOS Etar，
  但其 applicationId 曾为 ws.xsoh.etar 而非 org.lineageos.etar。
- 禁止从 manifest 的 `package` 属性读取 applicationId（AGP 8 下被忽略，且 Etar 系存在历史残留）；
  以 `build.gradle.kts` 的 `applicationId` 与 `aapt dump badging` 为准。
- 无法验证的事项在 PR 描述中如实标注"未验证"，不得假装完成。

## 2. 工作范围

### 允许
- 构建配置（`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`）、`scripts/`、`docs/`、`README.md`、
  `.gitignore`、`release-signer.sha256`、本文件。
- 任务明确要求时的 applicationId 对齐改动（须单独提交）。

### 禁止
- 修改应用功能代码、资源、UI（除非任务明确要求，且限于包名对齐所需的硬编码字符串）。
- 提交任何二进制：`*.apk` `*.aab` `*.jks` `*.keystore` `*.b64` `*.so` `dist/` `build/`。
- 回写仓库文件作为构建副作用（现有 CI 在构建后检查 `git status` 必须干净）。
- 删除或"清理" `<original-package>`、`<uses-permission>`、`sharedUserId`、provider authority 等 manifest 身份元素。
  **DeskClock 仓库特别禁止删除 `<original-package android:name="com.android.deskclock"/>`**，
  它已被真机证明是系统接管 stock 数据的关键。
- 合并 PR、打 tag、创建或删除 Release、删除分支——这些由所有者手动执行。
- 下载 GitHub Release 资产（沙箱网络不可达，只会消耗 API 配额）。

## 3. applicationId 策略

- 生产（release）变体的 applicationId 必须等于 LineageOS 内置版本的包名，使下游 GKUI-Pack 能以 replace 模式替换。
- debug 变体保留 `.dev` / `.debug` 后缀，用于本地开发与 AOSP 树共存。
- release 变体必须：`applicationIdSuffix = null`、`versionNameSuffix = null`、`isDebuggable = false`。
- `namespace` 可与 applicationId 不同（R 类包名跟随上游 `--custom-package`），不要为对齐 applicationId 而改 namespace。
- 如需改 applicationId：先 grep 全部 manifest / res / 源码中的硬编码旧包名（`targetPackage`、`android:authorities`、
  AppSearch 库名、快捷方式等），逐处列出并处理；provider authority 改为 `${applicationId}` 形式；单独提交。

## 4. 签名策略

- 生产签名只从环境变量读取：`KEYSTORE_FILE`（已解码的文件路径）、`KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS`。
  不读 `~/.gradle/gradle.properties`，不读仓库内任何文件。
- `CI=true` 且四者任一为空 → Gradle **配置阶段**即抛 `GradleException`，不得等到打包才失败。
- 本地缺失 → 产出未签名 APK 并打印醒目警告；**绝不回退 debug 签名**。
- keystore 为 PKCS12，`KEY_PASS` 与 `KEYSTORE_PASS` 相同；workflow 中 secret 名为
  `KEYSTORE_FILE`（base64 内容）、`KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS`。
- 生产证书 SHA-256（CI 实测）：
  `2eaa964c9eae1ee70e131a6ca5f8f06d7c1c152a037cac33d01f585c758f214a`
  仓库根目录 `release-signer.sha256` 直接提交该值；`ci-build.sh` 比对不一致即失败。
  这把 key 永不更换；任何建议"重新生成 keystore"的改动都必须停下来问所有者。

## 5. 版本号策略

- `versionCode = git rev-list --count HEAD`；CI 校验 ≥ 1（需 `fetch-depth: 0`）；本地无 git 回退为 1。
- `versionName = yyyyMMdd.<versionCode>`（UTC），可被环境变量 `VERSION_NAME` 覆盖。
- tag = `v<versionName>`；产物名 `<APP>-<versionName>.apk`。
- 若现有 CI 在 `generateBp` 后检查工作区干净，`generateBp` 配置块固定注入 `versionCode 1` / `versionName "1.0"`，
  避免动态版本号污染 `Android.bp`。
- `ci-build.sh` 读取上一 Release 的 `build-info.json`，新 versionCode 必须大于旧值；上一 Release 无该文件时跳过并打印说明。

## 6. 构建脚本与 workflow

### scripts/ci-build.sh
- 以参考实现为准照抄，只改 `APP_NAME` 与 `EXPECTED_APPLICATION_ID` 两个常量。
- 职责：解码 keystore → `./gradlew --no-daemon assembleRelease` → aapt/apksigner 门禁 → 重命名 → 生成
  `.sha256` 与 `build-info.json` → 写 `GITHUB_OUTPUT`。
- 门禁必须包含：applicationId 等于期望值且不含 `.dev/.debug`；不 debuggable；versionCode 为正整数；
  签名证书等于 `release-signer.sha256`；versionCode 单调递增。
- **aapt badging 解析**：applicationId 用 `^package: name='` 锚定；versionCode / versionName 键名前加空格
  （`.* versionCode='`）。禁止 `.*name='` 贪婪模式（会匹配到 `compileSdkVersionCodename`）。
- `build-info.json` 字段（下游 GKUI-Pack 直接读取，不得改名）：
  `applicationId` `application_id` `versionCode` `version_code` `versionName` `version_name`
  `signer_sha256` `apk_sha256` `commit` `source_url`。

### Gradle Kotlin DSL
- 在 `signingConfigs.create { }` 等带接收者的 lambda 内，外层局部变量**不得**与接收者属性同名
  （`keyPassword` / `storePassword` / `keyAlias` / `storeFile`），否则被遮蔽而赋成 null。
  外层变量统一加 `Value` 后缀：`val keyPasswordValue = System.getenv("KEY_PASS")`，lambda 内 `keyPassword = keyPasswordValue`。
- 禁止在 Gradle 配置阶段执行网络请求。

### GitHub Actions
- GitHub-hosted ubuntu 运行器已预装 Android SDK（`$ANDROID_HOME`）。**不使用 `android-actions/setup-android`**
  （默认安装已下架的 `tools` 包，必然失败）；直接检查/补装 `build-tools;35.0.0` 并加入 PATH，末尾自检 `aapt version`。
- 只用 `${{ github.token }}`，不引入 PAT。`permissions: contents: write`，不多给。
- 触发：push 到默认分支（`paths-ignore: docs/**, **/*.md`）+ `workflow_dispatch`。
- 禁止 `pull_request_target` 触发器；禁止任何会把 env / secrets 打印、上传、写入产物的步骤。
- `gh release create` 在 tag 已存在时失败属预期；不要为此加 `--clobber` 或删 tag 的逻辑。

### `.github/**` 的修改权限（二选一，所有者测试后删除不适用的一段）

**版本 A（agent 可写 workflow）**
- 可以直接写入/修改 `.github/workflows/release.yml`，内容以参考实现为准，只改分支名。
- 对 `.github/**` 的每一处改动必须在 PR 描述中逐行说明理由；所有者合并前会逐行审查。
- 不得修改参考实现中与 secrets 相关的 env 映射方式。

**版本 B（agent 不可写 workflow）**
- `.github/**` 由所有者手动维护，agent 不得修改。
- workflow 内容写入 `docs/WORKFLOW.md`，注明"由所有者粘贴到 `.github/workflows/release.yml`"。

本仓库本次任务适用【版本 B】（任务明确要求不改 .github/**）；A 段保留，由所有者合并后自行删除。

## 7. API 与网络用量（避免风控红线）

- **禁止高频轮询**：严禁写死 `while` 循环高频检查 CI 状态。如需检查，请直接查看最后一次状态。
- **读文件优先克隆本地**：需要分析仓库内多个文件时，优先使用 `git clone --depth 1` / `git fetch` 克隆到沙箱使用 `cat/grep` 读取，严禁使用 `gh api` 逐个拉取远端文件。
- **合并写入操作**：禁止为一个小的改动创建多次 Commit。必须在本地沙箱完成所有修改后，分次精简提交（控制在 5 次以内）并开启/更新单个 PR。
- **失败即停止**：遇到 API 报错（尤其是 403 Rate Limit 或 429 Too Many Requests），必须立即按 `retry-after` 等待或停止执行并向人类报告，严禁立即自动重试！
- 不在循环里调用 `gh api`；确需批量查询用 GraphQL 一次取回。
- 不下载 Release 资产；签名指纹等信息等 CI 日志打印。
- 本地测试全绿后一次性推送，不要边推边轮询 CI；提交数控制在 5 次以内。
- 同一时间只有一个 agent 在本组织的仓库上工作（所有者负责串行派发；agent 发现其他活跃会话分支时在 PR 描述中提醒）。

## 8. 代码与文档规范

- Shell 脚本：bash 用 `set -Eeuo pipefail`，通过 `shellcheck`；模块侧脚本（如有）为 POSIX sh。
- 每个新增文件开头写一行用途注释与 SPDX 头（跟随仓库现有风格）。
- 文档中文为主，可附英文简述。
- README 必须包含："构建与发布"一节（secrets 配置、keystore 生成、`release-signer.sha256` 作用、versionCode 来源、
  本地构建不受影响）；顶部醒目说明"从 dev/debug 版本切换到 release 版本需卸载重装，数据会丢失"。
- 本仓库相关的真机实测结论（如权限保护级别、`<original-package>` 行为）写入 README 时须注明测试环境，
  并区分"已验证"与"待验证"。

## 9. 工作协议

1. 开始前读本文件、README、现有 CI 配置、参考实现；在 PR 描述中列出与参考实现的全部差异。
2. 若本仓库已有此前会话打开的 PR，在该 PR 分支上继续，不开新 PR；先把实现对齐到参考实现
   （重点检查 keyPassword 遮蔽、badging 解析、setup-android 三处）。
3. 除任务指令明确要求停下的项（如需改 applicationId 的调研）外，直接开始，不等计划确认。
4. 分次提交，每次一个主题：约定文件 / 构建配置 / 脚本与文档 /（如有）applicationId 对齐。
5. PR 标题 `ci: release 变体自动构建与发布`；描述必须包含：
   - 与参考实现的差异清单
   - 所有者需手动完成项（secrets、如适用的 workflow 粘贴）
   - 未验证项（至少：Gradle 构建在沙箱不可运行，由 Actions 首次运行验证；R8/minify 运行时行为需真机）
   - 如有改动 `.github/**`，逐行说明
6. 完成后回复"可以合并"。不自行合并、不打 tag、不发 Release。

## 10. 沙箱已知限制

- 无 JDK、无 Android SDK；`dl.google.com`、`repo.maven.apache.org`、`services.gradle.org`、
  `release-assets.githubusercontent.com` 不可达。Gradle 构建只能由 Actions 验证，不要反复尝试。
- 会话随 PR 合并关闭，沙箱文件随之丢失。任何需要保留的产物（调研报告、补丁）必须提交进分支的 `docs/` 或
  推到 `transfer/<topic>` 分支，不要只放在沙箱目录。
- 工作目录可能在会话间被重置；每次开始先确认分支与基线 commit。

## 11. 变更记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-10-31 | 首版：由 GKUICalculator 首次成功发布实测得出的全部约定 | CI run（keyPassword 遮蔽、badging 贪婪匹配、setup-android tools 下架三处故障） |
