<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

<!-- 用途：GIF 帧延迟 / 视频转 GIF 时长修复的逐步“预期红”清单，推送前写定，之后只能删减。第 4 步清单清空后移入 PR 描述并删除本文件，不合并进主分支。 -->

# GIF 时序修复：预期失败清单

测试文件：`app/src/androidTest/java/org/lineageos/glimpse/visual/GifTimingInstrumentedTest.kt`
辅助文件：同目录的 `GifTestSupport.kt`。里面是两层独立 GIF 解析器（结构层 + 像素层）、写入器和 MediaCodec 彩色方块视频生成器，都不复用 `GifCodec`。

步骤顺序：1（补修）→ 2a LZW 码宽 → 2 延迟单位 → 3 取帧 → 4 方向 / IS_PENDING / 图库。

## 判定规则

- 下表的失败只允许出现在 candidate 截图任务（light/dark）里。baseline light/dark、lint、unit-tests 必须全绿。
- 只有**测试名 + 断言位置（文件:行）+ 消息**三者都匹配时才算预期红。消息是 `violations.joinToString("; ")` 拼出来的，每一项都必须是该条目“允许的消息项”中的一种，格式严格按下面的写法（`<…>` 是占位符，其余字符逐字匹配）。只要出现一项不在列表里，就算非预期红。
- 编译错误、NPE、超时、结构层解析失败（`IllegalArgumentException`/`IllegalStateException` 来自 `MinimalGif.parse`），或者在其他断言处失败，都算非预期红，必须先修复。
- `platform=…` 消息项（独立解析器与平台解码器不一致）在任何一步都不在允许范围内。
- `harness_parserSelfCheck`、`harness_syntheticVideosAreValid` 在两种 uiVersion 下都必须通过。
- 每一步的清单只能是上一步的子集，只能删减，不能新增条目或放宽消息。
- 某一步结束后，清单里标了“必须消失”的消息项如果还在，算非预期红。

## 修订记录

1. a213f53：androidTest 编译失败（非预期红），修复后断言下移 1 行，条目没有变化。
2. **唯一一次允许的内容修订（因 LZW 缺陷）**，提交时间晚于 f49ddf9 的 CI run 45163765655。
   - 那次 run 发现 `GifCodec.kt:526–527` 的 LZW 编码器比标准提前一个码就把码宽加 1，导致独立解析器的像素层在 baseline/candidate 上都解不出来，测试在列表外的位置抛异常（非预期红）。
   - 修订内容：
     - 解析器拆成结构层和像素层；
     - 允许消息改成精确格式；
     - 标出“因 LZW 新增”的消息项和它们必须消失的步骤；
     - 根据该 run 的实测结果删掉 #7（图库动画在 candidate light/dark 都是绿）；
     - 断言行号随代码调整。
   - **这次修订之后，清单只能删减，不能再加条目或放宽消息。**

## 第 1 步（仅测试，产品代码未改）

| # | 测试 | 步骤 | 断言位置 | 允许的消息项（精确格式） | 当前预计实际值 | 何时必须消失 |
|---|------|------|----------|--------------------------|----------------|--------------|
| 1 | `step2_encoderWritesCentisecondDelays` | 2 | `GifTimingInstrumentedTest.kt:254` | `in0 raw=[100, 70, 20, 125] exp=[10, 7, 2, 13]`；`in1 raw=[20, 20, 20] exp=[2, 2, 2]`；**因 LZW 新增**：`in<0\|1> lzw=frame:<n> offset:<n> width:<n>` | 两项 raw + 两项 lzw（`GifCodec.kt:398` 写入毫秒，且 coerceIn(20, 10000) 让 15 ms 变成 20） | `lzw=` 在 2a 后消失；整条在 2 后消失 |
| 2 | `step2_editorRoundTripPreservesRawDelays` | 2 | `GifTimingInstrumentedTest.kt:282` | `rt raw=[100, 70, 20] exp=[10, 7, 2]`；**因 LZW 新增**：`rt lzw=frame:<n> offset:<n> width:<n>` | raw + lzw | `lzw=` 在 2a 后消失；整条在 2 后消失 |
| 3 | `step3_videoGifFollowsUniformPlan` | 3 | `GifTimingInstrumentedTest.kt:345` | `ToolboxResult lacks syncFallbackFrames`；`<t> raw=<a>..<b> exp=<x.x>`；`<t> sum=<a>/<b>cs`；`<t> last#<a>/<b>`；**因 LZW 新增**：`<t> lzw=frame:<n> offset:<n> width:<n>`。其中 `<t>` ∈ {s30, s60, r30, d30, r60, rot, hd, odd} | raw/sum（ms 值）：s30 100..100/10.0、3000/300cs；s60 125..125/12.5、4000/400cs；r30 66..66/27.8、2376/1000cs；d30 125..125/13.9、4500/500cs；r60 200..200/50.0、7200/1800cs；rot 100..100/10.0、2000/200cs；hd 200..200/20.0、1000/100cs；每例一项 lzw。2a 之后预计还会出现 `r30 last#70/292`、`d30 last#131/146`、`r60 last#420/1050` | `lzw=` 在 2a 后消失；`raw`/`sum` 在 2 后只允许因覆盖不足（3 修）出现；整条在 3 后消失 |
| 4 | `step3_estimateShowsActualFrameRate` | 3 | `GifTimingInstrumentedTest.kt:398` | `sliderMax=15.0/18`；`reduced='~10s • 15fps • 480p • ~36 frames' exp='<missing resource>'`；`normal='~3s • 10fps • 480p • ~30 frames' exp='<missing resource>'` | 与 f49ddf9 的 run 实测一致 | 3 后消失 |
| 5 | `step4_writeFailureLeavesNoPendingRow` | 4 | `GifTimingInstrumentedTest.kt:469` | `Rows left after a failed write: land60_320x180_5s_gif_<n>.gif is_pending=1`（整条消息） | 与实测一致 | 4 后消失 |
| 6 | `step4_orientationIsUpright` | 4 | `GifTimingInstrumentedTest.kt:431` | **因 LZW 新增**：`<t> pixels undecodable`；条件项：`rot size=240x135/135x240`、`rot f<n> red=[BL]` | 每例一项 `pixels undecodable`；如果平台 `getFrameAtTime` 不按旋转元数据输出，还会出现 rot 的两项 | `pixels undecodable` 在 2a 后消失；之后如果还红，只能是 rot 方向项（第 4 步修）；4 后消失 |

已删除：原 #7 `step4_galleryAnimatesGif`。它在 run 45163765655 的 candidate light/dark 都是绿，现在预期为绿。

预期在第 1 步为绿：`harness_parserSelfCheck`、`harness_syntheticVideosAreValid`、`step4_galleryAnimatesGif`（两种 uiVersion）。baseline 上所有测试都只记录数值（ui-evidence `gif-baseline-*.txt`），只断言测试工具本身。

## 第 1 步补充：2a 前证据（仅测试，失败清单不变）

新增两个测试，放在图库测试之后，用全限定名，没有新增 import。上面所有断言行号不变。

| 测试 | 断言 | 预期 |
|------|------|------|
| `lzw_decoderEvidence` | 无断言，两种 uiVersion 只写 `gif-<ver>-lzw-decoders.txt` | 绿。任何异常（编码抛错、超时）都是非预期红 |
| `lzw_externalGifImportDecodesExactly` | candidate：`GifTimingInstrumentedTest.kt:585`；baseline 只记录 | 绿。外部 GIF 用测试自带的标准编码器写出，`GifCodec.decode` 必须逐像素一致，延迟 = 原始值 × 10 ms |

三方比较函数 `threeWayCompare`：
- 比较对象：独立解析器、平台 `ImageDecoder`、`GifCodec.decode`，三者都和源像素逐一比较。
- 返回内容：
  - 不一致项列表，每项含帧号、x、y，以及源像素和三方各自的值；
  - 解码器层面的问题列表：LZW 错误、缺少结束码、平台部分解码、异常、帧数或尺寸不符；
  - 实际比较的像素数，必须等于宽 × 高 × 帧数。
- 用例都是单帧，因为 `ImageDecoder` 只能取到第一帧；GifCodec 对每一帧都用同一个 `lzwEncode`。
- 只用 baseline 和 candidate 都存在的 API。
- 本次只写入证据。2a 提交时改为断言：candidate 两个列表都必须为空；baseline 用同一个函数处理旧代码的输出，断言列表不为空并写入证据。
- 失败清单不增加条目。

测试工具新增字段 `MinimalGif.Frame.endCode`：记录最后一个像素之后是否紧跟结束码。只记录，不改变 `lzwError`，所以已有断言的结果不变。

证据预测（来自 Python 模型，只用于和实测对照，不作为断言）。修复前规则：一帧需要 ≥ 2^m − 1 个 LZW 数据码时出错，m 为最小码长：
- `solid_codes2`、`walk254`：conformant，即 0 个不一致项、0 个解码器问题。
- `solid_codes3`、`walk255`：像素正确，但 `endCode` 不是结束码，所以判为 breaks。
- 其余用例为 breaks，会有像素不一致。`solid64` 模型预测会读到一个“看似合法”的结束码，但像素是错的。
- 所有用例都应 `ruleMatches=true`，`compared` 都应等于宽 × 高。

补充说明：`step4_galleryAnimatesGif` 播放的是测试自带编码器写的 fixture，不是 GifCodec 的输出。GifCodec 输出经 Glide 解码的情况，由 `lzw_decoderEvidence` 的 `glide=` 字段记录，同样只是证据。

## 第 2a 步（只改 LZW 码宽增长时机）

产品改动只有一处：`GifCodec.kt:526`（改后因为加了两行注释，位于 `:528`），`freeCode == (1 shl codeSize)` 改为 `freeCode > (1 shl codeSize)`。加入码表后，编码器的 freeCode 总比解码器的码表大小多 1，所以要等 freeCode 超过 2^width 才加宽，这样才和标准解码器同步。延迟和取帧都不动。

同一提交新增 `step2a_lzwOutputIsPixelIdenticalAcrossDecoders`（放在外部导入测试之后，上面的断言行号都不变）。它对 13 个单帧用例调用 `threeWayCompare`：
- 两种 uiVersion 都断言（`:618`）：实际比较的像素总数 = 各用例宽 × 高之和（独立算出，不取函数的返回值）。
- candidate 断言（`:624`）：不一致项和解码器问题合计为 0；`random256` 的最大码宽为 12，且 clear code ≥ 2（含开头那个）。
- baseline 断言（`:620`）：同一函数处理旧编码器的输出，问题列表**不为空**，并写入证据。

### 预期消息消失

| # | 消失的消息项 |
|---|--------------|
| 1 | `in0 lzw=frame:0 offset:66 width:4`、`in1 lzw=frame:0 offset:66 width:4` |
| 2 | `rt lzw=frame:0 offset:66 width:4` |
| 3 | 7 项 `<t> lzw=…`（s30/s60/r30/d30/r60/rot/hd） |
| 6 | 7 项 `<t> pixels undecodable` |

### 预期新出现（都在 #3 原有的允许格式内）

`r30 last#<约 70>/292`、`d30 last#<约 131>/146`、`r60 last#<约 420>/1050`。这是旧取帧逻辑的覆盖不足，第 3 步修复。

### 预期转绿

- #6 `step4_orientationIsUpright`（candidate light/dark）。
- 新测试 `step2a_lzwOutputIsPixelIdenticalAcrossDecoders`：四个截图 job 都为绿（candidate 列表为空；baseline 断言列表不为空并通过）。

### 预期仍红（candidate，消息逐字）

| # | 位置 | 消息 |
|---|------|------|
| 1 | `:254` | `Encoder delay violations: in0 raw=[100, 70, 20, 125] exp=[10, 7, 2, 13]; in1 raw=[20, 20, 20] exp=[2, 2, 2]` |
| 2 | `:282` | `Editor round-trip violations: rt raw=[100, 70, 20] exp=[10, 7, 2]` |
| 3 | `:345` | `Video GIF plan violations: ToolboxResult lacks syncFallbackFrames; s30 raw=100..100 exp=10.0; s30 sum=3000/300cs; s60 raw=125..125 exp=12.5; s60 sum=4000/400cs; r30 raw=66..66 exp=27.8; r30 sum=2376/1000cs; r30 last#<a>/292; d30 raw=125..125 exp=13.9; d30 sum=4500/500cs; d30 last#<a>/146; r60 raw=200..200 exp=50.0; r60 sum=7200/1800cs; r60 last#<a>/1050; rot raw=100..100 exp=10.0; rot sum=2000/200cs; hd raw=200..200 exp=20.0; hd sum=1000/100cs` |
| 4 | `:398` | 不变 |
| 5 | `:469` | 不变 |

判定补充：
- 2a 之后，如果出现 `identicalNeighbours`、`first#`、`undecodable`、`dup`、`n=`、`size=`、`aspect=`、`platform=`，或者非 rot 用例的 `red=`，都算非预期红，必须先停下分析。
- `lzw_decoderEvidence` 在 candidate 上所有用例都会变成 `observed=conformant`。修复前规则判为 breaks 的用例会显示 `ruleMatches=false`。这是预期的：这条规则描述的是修复前的编码器，这个测试只记录证据。
- baseline、lint、unit-tests 必须全绿。

### 2a 实际结果（`cffd36b`，run 45227746844）

与上面的清单逐字一致：#6 和 step2a 在四个截图 job 上都为绿，`last#` 实际值为 70/292、131/146、420/1050。step2a 在 baseline 上检出 25628 个问题，在 candidate 上为 0；两边比较的像素都是 26377/26377。

## 第 2 步（编码器延迟单位，只改 `GifCodec.encode` 写 GCE 延迟的地方）

产品改动：
- `GifCodec.encode`（`:396–404`）原来把毫秒值直接写进单位为 1/100 s 的字段（`d100 = frame.delayMs.coerceIn(20, 10000)`），改为写 `centisecondDelays(...)` 算出的值。
- 新函数 `GifCodec.centisecondDelays`（`:448`）：C[i] = round(T[i]/10)，B[0] = 0，B[i+1] = max(C[i+1], B[i] + 2)，第 i 帧 = B[i+1] − B[i]，上限 65535。
- 公开签名不变：`GifFrame(bitmap, delayMs)` 在 baseline `772071a` 里就叫 `delayMs`，所以不需要保留旧签名的重载。
- 编辑器的 20 ms 下限（`GifEditorActivity.kt:233`）保持不动。取帧逻辑也不动（第 3 步再改）。

新单元测试 `GifCodecDelayTest`（JVM）：
- 66.67 ms 间隔连续 15 帧：总误差 ≤ 1 cs，每帧的起点误差 ≤ 1 cs；
- 连续多帧 10 ms：每帧 2 cs，之后的帧把多出的时间补回来；
- 编辑器 20 ms 下限 → 正好 2 cs；
- 往返：100/70/20 ms → 10/7/2 cs；
- 负值和超出范围的值。

unit-tests job 必须为绿。

### 预期转绿

- #1 `:254` `step2_encoderWritesCentisecondDelays`（candidate light/dark）
- #2 `:282` `step2_editorRoundTripPreservesRawDelays`（candidate light/dark）

### #3 `:345` 逐项变化

旧取帧逻辑传给编码器的延迟值：s30/rot 100 ms，s60/d30 125 ms，r30 66 ms，r60/hd 200 ms。帧数不变（30/32/36/36/36/20/5），所以 `last#` 不变。

| 消息项（2a 后） | 第 2 步之后 |
|---|---|
| `ToolboxResult lacks syncFallbackFrames` | 保留 |
| `s30 raw=100..100 exp=10.0`、`s30 sum=3000/300cs` | 消失（10 cs × 30 = 300） |
| `s60 raw=125..125 exp=12.5`、`s60 sum=4000/400cs` | 消失（12/13 cs 交替，合计 400） |
| `r30 raw=66..66 exp=27.8` | 变为 `r30 raw=6..7 exp=27.8` |
| `r30 sum=2376/1000cs` | 变为 `r30 sum=238/1000cs` |
| `r30 last#70/292` | 保留 |
| `d30 raw=125..125 exp=13.9` | 变为 `d30 raw=12..13 exp=13.9` |
| `d30 sum=4500/500cs` | 变为 `d30 sum=450/500cs` |
| `d30 last#131/146` | 保留 |
| `r60 raw=200..200 exp=50.0` | 变为 `r60 raw=20..20 exp=50.0` |
| `r60 sum=7200/1800cs` | 变为 `r60 sum=720/1800cs` |
| `r60 last#420/1050` | 保留 |
| `rot raw=100..100 exp=10.0`、`rot sum=2000/200cs` | 消失 |
| `hd raw=200..200 exp=20.0`、`hd sum=1000/100cs` | 消失 |

### 预期仍红（candidate，消息逐字）

| # | 位置 | 消息 |
|---|------|------|
| 3 | `:345` | `Video GIF plan violations: ToolboxResult lacks syncFallbackFrames; r30 raw=6..7 exp=27.8; r30 sum=238/1000cs; r30 last#70/292; d30 raw=12..13 exp=13.9; d30 sum=450/500cs; d30 last#131/146; r60 raw=20..20 exp=50.0; r60 sum=720/1800cs; r60 last#420/1050` |
| 4 | `:398` | 不变：`Estimate violations: sliderMax=15.0/18; reduced='~10s • 15fps • 480p • ~36 frames' exp='<missing resource>'; normal='~3s • 10fps • 480p • ~30 frames' exp='<missing resource>'` |
| 5 | `:469` | 不变：`Rows left after a failed write: land60_320x180_5s_gif_<n>.gif is_pending=1` |

判定补充：
- 其他测试（包括 #6、step2a、lzw_*、harness、gallery）必须保持绿。
- #3 中只要出现上表以外的项，比如 `n=`、`fps=`、`identicalNeighbours`、`first#`、`dup`、`size=`、`aspect=`、`lzw=`，或者 `last#` 的值和上表不同，都算非预期红。
- baseline、lint、unit-tests 必须全绿。

### 第 2 步实际结果（`8f8dc1b`，run 45235843263）

与上面的清单逐字一致：#1、#2 转绿；#3 剩下的 10 项与表中一致；#4、#5 不变。新单元测试 `GifCodecDelayTest` 6 条全部通过。

## 第 3 步（取帧，方案 B）

产品改动：
- 新文件 `utils/media/VideoGifPlan.kt`（不含 Android 类型，可做 JVM 单元测试）：
  - N = min(36, ⌈D·fps⌉)，t_i = i·D/N，最多 36/2 = 18 s，所以有效帧率不低于 2 fps；
  - 每个成功取到的帧显示到下一个成功帧开始，最后一帧显示到片段末尾。时间先按毫秒累计取整，所以各帧延迟之和等于片段时长；
  - 失败帧的时长并入前一帧；
  - **报错阈值**：失败帧数 × 2 > 计划帧数时，`videoToGif` 抛出 `IllegalStateException`，不写入 MediaStore。正好一半失败（例如 36 帧里失败 18 帧）时仍然生成 GIF，多失败一帧（19 帧）就报错。计划帧数为奇数时，⌊N/2⌋ 帧失败仍可生成，多一帧就报错（例如 N=5 时失败 2 帧可生成，失败 3 帧报错）。一帧都没取到时也报错。
- `ImageToolbox.videoToGif`：
  - 原来的取帧逻辑：从 0 开始每隔 1000/fps 取一帧、最多 36 帧；固定延迟 ≥ 33 ms；连续 6 次失败后跳过一段时间；全部失败时再试 t=0。这些都删除了，换成上面的计划；
  - 先用 OPTION_CLOSEST 取帧，取不到再用 OPTION_CLOSEST_SYNC，并统计回退次数。
- `ToolboxResult` 新增 `syncFallbackFrames: Int = 0`（带默认值，不影响其他调用方）。测试只通过反射读取 `getSyncFallbackFrames`，不引用 baseline 里没有的符号。
- `ImageToolboxBottomSheet.updateEstimate`：需要降帧时，估算文字使用 `toolbox_video_to_gif_estimate_reduced`，显示实际帧率（`%.1f`）、所选帧率和 36 帧上限；不需要降帧时使用 `toolbox_video_to_gif_estimate`。en、zh-CN、zh-TW 都有。
- `dialog_image_toolbox.xml`：时长滑杆上限 `valueTo` 和隐藏的旧 SeekBar `max` 都从 15 改为 18。
- 没有改：起点 UI（产品里没有，只记录不新增）、旋转、IS_PENDING（第 4 步）。

新单元测试 `VideoGifPlanTest`（JVM，6 条）：
- 帧数上限和 2 fps 下限；
- 均匀时间点；
- 7 个用例的毫秒和厘秒总和都等于片段时长，每帧误差 ≤ 1 cs；
- 失败帧（中间、第一帧、最后一帧）并入前一帧；
- 阈值边界 `exactlyHalfFailedPassesAndOneMoreIsAnError`：36 帧失败 18 帧时通过、失败 19 帧时报错；30 帧时是 15 和 16；5 帧时是 2 和 3；1 帧时是 0 和 1；
- `exactlyHalfFailedStillCoversTheWholeClip`：正好一半失败（隔一帧失败一帧）时剩下 18 帧，总时长仍为 10 000 ms，也就是 1000 cs。

### 预期转绿

- #3 `:345` `step3_videoGifFollowsUniformPlan`（candidate light/dark）
- #4 `:398` `step3_estimateShowsActualFrameRate`（candidate light/dark）

### 预期数值（来自回传证据的 conversion 文件）

| 用例 | 帧数 N | 有效 fps | 原始延迟（cs） | 总时长（cs） | 最后一帧起点 / 源帧 |
|---|---|---|---|---|---|
| s30 | 30 | 10.00 | 10 | 300 | 2.900 s / #87 |
| s60 | 32 | 8.00 | 12/13 | 400 | 3.875 s / #232 |
| r30 | 36 | 3.60 | 27/28 | **1000** | 9.722 s / #292 |
| d30 | 36 | 7.20 | 13/14 | **500** | 4.861 s / #146 |
| r60 | 36 | **2.00** | 50 | **1800** | 17.500 s / #1050 |
| rot | 20 | 10.00 | 10 | 200 | 1.900 s / #57 |
| hd | 5 | 5.00 | 20 | 100 | 0.800 s / #24 |

“最后一帧对应片段末尾”的判定：最后一帧取自 t_{N−1} = (N−1)·D/N（`last#` 与上表的源帧误差 ±1），并且最后一帧的起点加上它的延迟等于总时长 D。

估算文字：滑杆上限为 18；10 s × 15 fps 显示 `toolbox_video_to_gif_estimate_reduced(10, "3.6", 15, 480, 36)`；3 s × 10 fps 显示 `toolbox_video_to_gif_estimate(3, 10, 480, 30)`。

### 预期仍红（candidate，消息逐字）

| # | 位置 | 消息 |
|---|------|------|
| 5 | `:469` | `Rows left after a failed write: land60_320x180_5s_gif_<n>.gif is_pending=1` |

判定补充：
- 其他测试必须全部为绿，包括 #1、#2、#6、step2a、lzw_*、harness、gallery、UiScreenshotTest；
- candidate 上只要 #5 以外有任何失败，或者 #5 的消息和上面不同，都算非预期红；
- `reportedSyncFallbacks` 应为数字（不为 null）。实际值作为证据记录，不加断言；
- baseline（浅色、深色）、lint、unit-tests 必须全绿；unit-tests 预期 94/94（原来 88 条，加上 `VideoGifPlanTest` 6 条）。
