<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

<!-- 用途：GIF 帧延迟 / 视频转 GIF 时长修复的逐步“预期红”清单，推送前写定，之后只能按步骤缩减。 -->

# GIF 时序修复：预期失败清单

测试文件：`app/src/androidTest/java/org/lineageos/glimpse/visual/GifTimingInstrumentedTest.kt`
（辅助：同目录 `GifTestSupport.kt`，独立 GIF 解析/写入器与 MediaCodec 彩色方块视频生成器，不复用 `GifCodec`）。

判定规则：

- 只有 candidate 截图任务（light/dark）允许出现下表中的失败；baseline light/dark、lint、unit-tests 必须全绿。
- 失败只有在**测试名 + 断言位置（文件:行）**都匹配时才算预期红；编译错误、NPE、超时、其它断言一律算非预期红，须先修复。
- `harness_*` 两个测试在 baseline 和 candidate 上都必须通过。
- 每一步的清单只能是上一步清单的子集；推送后不得追加。

## 第 1 步（仅测试，产品代码未改）

修订记录：a213f53 首次推送时 androidTest 编译失败（非预期红，`apply {}` 里的 `context` 解析成了
`ContentProvider.getContext()`）。修复只让第 4 步两处断言下移 1 行（426→427、456→457），
清单条目与预期值没有变化。

| # | 测试 | 所属步骤 | 断言位置 | 预期（修复后） | 当前实际（约） |
|---|------|----------|----------|----------------|----------------|
| 1 | `step2_encoderWritesCentisecondDelays` | 2 | `GifTimingInstrumentedTest.kt:224` | ms `[100,70,20,125]` → raw `[10,7,2,13]`；`[15,15,15]` → `[2,2,2]` | raw 等于毫秒值：`[100,70,20,125]`、`[15,15,15]`（10× 慢） |
| 2 | `step2_editorRoundTripPreservesRawDelays` | 2 | `GifTimingInstrumentedTest.kt:251` | raw `[10,7,2]` | raw `[100,70,20]` |
| 3 | `step3_videoGifFollowsUniformPlan` | 3 | `GifTimingInstrumentedTest.kt:308` | 每例帧数 = min(36, 秒×fps)，raw ≈ 间隔/10 ±1，总时长误差 < 1 帧间隔，末帧色块对应片段末尾，`ToolboxResult.syncFallbackFrames` 存在 | `ToolboxResult lacks syncFallbackFrames`；s30 raw 100/10、s60 125/12.5、r30 66/27.8 末帧 #70/#292、d30 125/13.9 末帧 #131/#146、r60 200/50 末帧 #420/#1050、rot 100/10、hd 200/20；总时长约为预期 10 倍（r30/d30/r60 另有覆盖不足） |
| 4 | `step3_estimateShowsActualFrameRate` | 3 | `GifTimingInstrumentedTest.kt:361` | 滑块上限 18；10 s×15 fps 显示实际 3.6 fps 与原因（`toolbox_video_to_gif_estimate_reduced`），3 s×10 fps 使用 `toolbox_video_to_gif_estimate` | `sliderMax=15.0/18`；`~10s • 15fps • 480p • ~36 frames`（硬编码，资源缺失） |
| 5 | `step4_writeFailureLeavesNoPendingRow` | 4 | `GifTimingInstrumentedTest.kt:427` | 写入失败后无残留行 | 残留 `land60_320x180_5s_gif_NNNNN.gif is_pending=1` |

条件项（证据决定，红或绿都不算非预期；若红，只允许在下列位置且消息只含所列内容）：

| # | 测试 | 所属步骤 | 断言位置 | 若红时的消息 | 说明 |
|---|------|----------|----------|--------------|------|
| 6 | `step4_orientationIsUpright` | 4 | `GifTimingInstrumentedTest.kt:389` | 仅 `rot f0/fN red=[BL]` 与 `rot size=240x135/135x240` | 平台 `MediaMetadataRetriever.getFrameAtTime` 可能已按旋转元数据输出，则为绿，第 4 步只记录证据不改代码 |
| 7 | `step4_galleryAnimatesGif` | 4 | `GifTimingInstrumentedTest.kt:457` | `Gallery GIF did not animate: ...` | 图库经 `ImageView.load` → Glide → `GlideZoomImageView`，预计已动画；若红则第 4 步定位根因 |

预期在第 1 步就为绿的：`harness_parserSelfCheck`、`harness_syntheticVideosAreValid`（两种 uiVersion）。
尺寸取整（240×135、720×405、旋转源 135/240 排序后比较）目前预计不构成第 3 项中的违规，仅作为回归保护。
