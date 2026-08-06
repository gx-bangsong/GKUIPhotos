# GKUIPhotos 更新日志 — 相对于 LineageOS 上游

> **基线**：`LineageOS/android_packages_apps_Glimpse` `lineage-23.2` @ `160c5b6`（2026-08-01 `Automatic translation import`）  
> **本版本**：`23.2-gkui-20260806` / 分支 `arena/019fd6a8-gkuiphotos` @ `ffb7f88`（含上游 `160c5b6` 合并）  
> **对比范围**：`upstream/lineage-23.2..arena/019fd6a8-gkuiphotos` — 上游之后的所有本地变更，已在 2026-08-06 合入上游最新翻译

本日志按 `ENHANCEMENTS.md` 的 7 大离线模块组织，并补充**本次合入的上游变更**与**累计修复/国际化**。所有功能均为 **完全离线、零网络、无云端 ML、无新增原生依赖**，仅复用 `Media3/ExoPlayer、Glide、androidx.exifinterface、Material 3` 等已有库，唯一新增重逻辑为纯 Kotlin 自包含 `GifCodec`。

---

## 0. 本次上游合入（2026-08-06）

上游自你上次同步（`29694da`）以来仅有 **1 个提交**：

- **160c5b6 `Automatic translation import`** — `values-fa/strings.xml` 新增 4 条波斯语翻译（`hide_native_seek_buttons_*` / `remember_video_playback_position_*`），对应上游已有的“隐藏原生快进按钮 / 记住播放位置”设置。对本仓库无代码冲突，已通过 `Merge upstream ... (160c5b6)`（`ffb7f88`）合入。

> 结论：功能代码与上游完全同步，差异仅为本仓库的增强模块。

---

## 1. 视频播放升级

### 倍速播放 + 长按快进
- 播放器新增胶囊式 **倍速按钮**（`media_view.xml` 叠加在 `PlayerControlView` 上，仅视频时可见），点击弹出 `0.5x / 1.0x / 1.5x / 2.0x`（`LocalPlayerViewModel` + `PlaybackParameters(speed, pitch=1f)`，变速不变调，结果持久化到 `SharedPreferences.videoPlaybackSpeed`）。
- **长按快进**替代了冗余的右上角倍速角标：按住视频以可配置速度快进（松手恢复原速），手势由 `MediaGestureListener` 接管，设置中提供开关与速度档位 `1.5x / 2.0x / 2.5x / 3.0x（默认 2.0x，默认开启）`。对应 `fff1100`。
- 修复：悬浮工具箱遮挡视频进度条的问题 — 视频时隐藏胶囊，工具箱改由 Toolbar 溢出菜单进入（`46042be` / `3b2ddaa` 统一 `updateToolboxVisibility()` 同时判断 `mediaType != VIDEO / readOnly / fullscreen`）。

### 画中画（PiP）
- `AndroidManifest` 声明 `supportsPictureInPicture="true"`；`ViewActivity.onUserLeaveHint()` 在视频播放时自动进入 PiP（带比例与源矩形提示，API 31+ 启用 `setAutoEnterEnabled`/`setSeamlessResizeEnabled`），`onPause()` 在 PiP 切换期间不暂停，`onPictureInPictureModeChanged()` 切换工具栏/底片显隐。

**涉及文件**：`ViewActivity.kt`、`MediaViewerAdapter.kt`、`LocalPlayerViewModel.kt`、`MediaGestureListener.kt`、`res/layout/media_view.xml`、`AndroidManifest.xml`、`ext/SharedPreferences.kt`

---

## 2. 分享隐私 — 一键 EXIF 脱敏

- 图片点击 **分享** 不再直接调 `ACTION_SEND`，而是先弹出 `SharePrivacyBottomSheet`（`dialog_share_privacy.xml`）：
  - ☐ 去除位置信息（全部 `TAG_GPS_*`）
  - ☐ 去除设备与时间（`TAG_MAKE/MODEL/SOFTWARE/DATETIME*/镜头序列号` 等）
- `ExifStripper.strip()` 在 `Dispatchers.IO` 将原文件字节拷贝到 `cache`，用 `androidx.exifinterface` 原地置空选中标签，**原文件永不被修改**，生成的 `content://` 临时 Uri（经 `FileProvider` / `res/xml/file_paths.xml`，修复 `FileUriExposedException` — `1b2e6ee`）交给分享 Intent；非图片走原有分发。
- 工具箱布局滚动化（`ScrollView` + `RadioGroup` 垂直化）避免窄屏截断。

**涉及**：`ExifStripper.kt`、`SharePrivacyBottomSheet.kt`、`ViewActivity` 分享路由

---

## 3. 离线应用来源智能相册（虚拟聚合）

- `SourceAlbumAggregator` **单次 MediaStore 查询** + 双策略离线聚合，不复制/移动任何文件：
  - **策略 A 路径匹配**：`DATA` 列匹配已知应用目录（美团众包、蜂鸟即配、顺丰同城、达达快送、淘宝、微信等）；
  - **策略 B EXIF 兜底**：对未匹配的少量图片读取 `TAG_SOFTWARE` 签名。
- **相册页**顶部新增 **“智能相册（应用来源）”** 横向轮播（`AlbumsAdapter` + `source_albums_section.xml` / `source_album_card.xml`，`AlbumsFragment`/`AlbumsViewModel` 驱动），点击以 `clipData` 启动 `ViewActivity` 左右滑动浏览。

**涉及**：`SourceAlbumAggregator.kt`、`AlbumsViewModel.kt`、`AlbumsAdapter.kt`、`AlbumsFragment.kt`

---

## 4. 灵活的编辑保存机制 + 全局设置

- 全新 **站内图片编辑器** `ImageEditorActivity`（旋转 / 滤镜 / 裁切 / 证件照），最大边 2048px 下采样以控内存。
- 保存时遵循新全局设置 **“图片编辑保存默认行为”**（`root_preferences.xml` → Editing 分类，`ListPreference edit_save_behavior`）：
  1. **每次询问**（默认）— 弹窗 `覆盖 / 另存为新图`；
  2. **始终覆盖** — 直写 `openOutputStream(uri, "wt")`，`SecurityException` 时回退到 `MediaStore.createWriteRequest` 授权再写；
  3. **始终另存** — 在 `Pictures/Glimpse` 新建条目。
- `EditSaveBehavior` 枚举 + `SharedPreferences.editSaveBehavior` 持久化。
- 修复：`Intent.setDataAndType` 互斥导致编辑器 `intent.data==null` 闪退（`2e9487b` 改用 `setDataAndType` + extra 兜底）。

**涉及**：`ImageEditorActivity.kt`、`EditSaveBehavior.kt`、`SharedPreferences.kt`、`arrays.xml`、`activity_image_editor.xml`

---

## 5. 离线证件照裁切

- 编辑器内 **证件照** Chip 组锁定裁切框比例：一寸 `25:35` / 二寸 `35:49` / 护照签证 `35:45` / 方形头像。
- 自绘 `CropImageView` 叠加 **三分构图网格 + 虚线人脸对齐椭圆**，用户通过拖拽/双指缩放摆正头像（纯手调，无人脸检测、无云端），`crop()` 经逆矩阵映射精确裁出像素区。

**涉及**：`CropImageView.kt`、`ImageFilter.kt`（比例预设）、`ImageEditorActivity`

---

## 6. 离线图片工具箱（取代“在此照片内搜索”）

- 浏览页底部居中悬浮 **“图片工具箱”** 胶囊（`Theme.Glimpse.MediaViewer.ToolboxCapsule`），打开 `ImageToolboxBottomSheet`（`dialog_image_toolbox.xml`），三项纯本地工具均在 `Dispatchers.Default/IO` 后台执行并显示进度：
  1. **压缩** — 预设 `85% / 70% / 50%` 或 **≤500 KB** 预算（迭代质量 + 一次下采样）；
  2. **格式转换** — `JPEG ⇄ PNG ⇄ WEBP`（框架解码再编码，HEIC 输入经平台解码后转出）；
  3. **视频转 GIF**（仅视频）— `MediaMetadataRetriever.getFrameAtTime` 采样前 N 秒（默认 36 帧上限，`a83caa8` 限帧防 OOM）+ `GifCodec.encode` 打包为动图，带确定性进度回调。
- 结果写入 `Pictures/Glimpse`（MediaStore）。
- 修复：压缩/转换按钮同名 `Run` 易混淆，改为 `压缩 / 转换 / 生成 GIF` 区分文案（`f4f2961`）；GIF 编码调色板从全像素扁平化改为**有界采样 + 单像素最近色缓存**，并修正 LZW 字典初始化（仅字面色入表，避免 clear/end 码与 0/1 颜色冲突导致花屏），同时对视频抽帧限量与异常透出（`a83caa8`）。

**涉及**：`ImageToolbox.kt`、`ImageToolboxBottomSheet.kt`、`ViewActivity` 胶囊按钮

---

## 7. 完整 GIF 编辑器 + 自包含 GIF 编解码器

- 动图（`image/gif`）的 **编辑** 入口改走 `GifEditorActivity`，而非当静态图：`GifCodec.decode` 正确处理合成/处置方式后提供：
  - 逐帧预览（SeekBar scrubber）+ 自动循环播放；
  - **倍速**（×0.5 / ×2 调整帧延迟）；
  - **裁剪**（起止帧滑块截取时间线）；
  - **旋转 / 滤镜**（导出时对每帧生效）；
  - **导出** — `GifCodec.encode` 重新编码为新动图入库。
- **GifCodec.kt**：纯 Kotlin、无依赖的 GIF89a 解码 + 编码（LZW + median-cut 量化），同时支撑模块 6 的视频转 GIF 与模块 7 的 GIF 编辑，保持全链路离线（无需 FFmpeg/原生库）。

**涉及**：`GifEditorActivity.kt`、`GifCodec.kt`、`activity_gif_editor.xml`、`menu_gif_editor.xml`

---

## 8. 质量修复（累计）

| 提交 | 说明 |
|---|---|
| `ff8741d` | 去除 `TAG_HOST_COMPUTER`（`androidx.exifinterface` 未暴露） |
| `c52df98` / `38be2a1` | `ThumbnailAdapter` 模糊 `RenderEffect` 懒初始化 + 函数化调用，避免在 `minSdk 30` 上 `NoClassDefFoundError`（API 31+ 才解析） |
| `1b2e6ee` | 分享经 `FileProvider` 返回 `content://`，修复 API 24+ `FileUriExposedException` |
| `3b2ddaa` / `46042be` | 工具箱在视频/全屏时正确隐藏，不再遮挡进度条 |

---

## 9. 国际化

- **简中 `zh-rCN` / 繁中 `zh-rTW`**：为全部新增模块补全翻译（编辑器、证件照、工具箱、分享隐私、智能相册、GIF 编辑器、保存行为、长按快进），并修正 Video 分区中 `seek` 误译为“寻找/尋找”→ `快进/快退`（CN）/ `快轉/倒轉`（TW），`rewind` 误译“倒带”→ `快退/倒轉`（`fb5efed`）。`c89ba43` 将“边缘单击导航”精炼为“边缘点击导航”，统一“双击/点击/切换”触控术语。
- 遵循 `translate → reflect → refine` 链路，繁中采用 `影片/儲存/檔案/匯出/復原` 等地区用语。

---

## 10. 构建与权限

- **权限**：无新增运行时权限，复用现有 `READ_MEDIA_IMAGES/VIDEO`、`ACCESS_MEDIA_LOCATION`、`MANAGE_MEDIA`（`createWriteRequest` 覆盖写）；`supportsPictureInPicture` 仅需 manifest 标记。
- **依赖**：**零新增**，全部复用 `libs.versions.toml` 已有库；`5d02a0a` / `09fb7ad` 新增 GitHub Actions `build-apk.yml` / `main.yml` 一键出包。
- **线程与性能**：压缩/转换/GIF 编解码/EXIF 脱敏/来源聚合均在 `lifecycleScope/viewModelScope + Dispatchers.IO/Default`，配合进度条不卡 UI；编辑前下采样、聚合单查询 + 有界 EXIF 扫描以控内存/IO。

---

## 文件清单（相对上游新增/修改）

```
ENHANCEMENTS.md
.github/workflows/build-apk.yml, main.yml
AndroidManifest.xml, SharedPreferences.kt, ViewActivity.kt, MediaViewerAdapter.kt, LocalPlayerViewModel.kt, MediaGestureListener.kt
ImageEditorActivity.kt, GifEditorActivity.kt, CropImageView.kt
SourceAlbumAggregator.kt, AlbumsAdapter.kt, AlbumsFragment.kt, AlbumsViewModel.kt
ImageToolbox.kt, ImageToolboxBottomSheet.kt, SharePrivacyBottomSheet.kt, ExifStripper.kt, GifCodec.kt, ImageFilter.kt, EditSaveBehavior.kt
ThumbnailAdapter.kt
res/drawable/ic_* (adjust/compress/convert/id_photo/rotate/save/speed/toolbox/video_to_gif)
res/layout/activity_gif_editor.xml, activity_image_editor.xml, activity_view.xml, dialog_image_toolbox.xml, dialog_share_privacy.xml, source_album_card.xml, source_albums_section.xml
res/menu/*, res/values/* (strings/arrays/themes), res/xml/file_paths.xml, root_preferences.xml
```

---

## 升级说明

- 直接安装覆盖即可，`Pictures/Glimpse` 为新增内容落盘目录，不改动原图（分享脱敏为缓存副本）。
- 设置 → 编辑 / 视频 中可分别配置保存行为与长按快进速度。
- 如需回退，卸载重装即回到上游纯净版（无数据迁移）。

---

*生成于 2026-08-06（Asia/Taipei），基于 `git log upstream/lineage-23.2..arena/019fd6a8-gkuiphotos` 与 `ENHANCEMENTS.md` 整理。*
