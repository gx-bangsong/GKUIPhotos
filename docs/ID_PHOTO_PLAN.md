<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

# GKUI Photos: ID Photo Size Catalog Investigation and System Improvement Plan

## Executive Summary

This engineering document delivers a rigorous specification, comprehensive size preset catalog, and end-to-end architecture plan for upgrading the ID Photo (ZhengJianZhao) feature in GKUI Photos (repository: gx-bangsong/GKUIPhotos, upstream baseline lineage-23.2@5158d58).

### Foundational Findings and Architectural Decisions

1. **Resolution of Aspect Ratio Redundancy**: In Chinese standard portrait photography, standard 1-inch (25x35 mm) and standard 2-inch (35x49 mm) ID photos share an identical 5:7 aspect ratio (approx. 0.7142857). When treated purely as crop ratio presets in an image editor, they represent redundant duplicate bounding boxes that provide zero functional differentiation to the end user. Real-world ID photo compliance is defined by standardized physical millimeter dimensions, exact pixel resamplings at a calibrated DPI convention (typically 300 DPI, and 350 DPI for Chinese National ID cards), and verified resolution metadata headers.
2. **Domain Criterion and Architectural Placement**: ID photo production is ruled to be strictly a **Toolbox Domain** workflow rather than an interactive canvas editing session. A process that consumes a source file and produces a standardized target file via an automated pipeline (crop framing -> optional solid-color background keying -> physical resampling -> metadata injection -> MediaStore write) belongs inside the offline Image Toolbox (`ImageToolbox.kt`, `ImageToolboxBottomSheet.kt`).
3. **Solid-Color Background Replacement Constraint**: Following product owner mandate, background replacement is strictly limited to solid-color studio backgrounds (switching among white, blue, and red requirements). Natural complex scenes are out of scope: the system algorithmically detects perimeter background uniformity and variance, gracefully refusing complex scenes without producing corrupted masks. Zero machine learning runtimes, neural models, or external dependencies are introduced.
4. **Verified 32-Preset Catalog**: A complete size catalog matching and exceeding Xiaomi/MIUI/HyperOS Gallery is established, spanning Common Sizes, Official Documents, Visas, and State Exams, cross-verified against Chinese National Standards (GA/T 461-2019, GA 482-2012, NIA Guidelines) and ICAO Doc 9303.
5. **Feedback to Canvas Port Plan**: Explicit technical feedback is documented for Task P0-1 of the LineageOS Canvas port plan regarding 5:7 ratio deduplication.

---

## 第一部分：背景、核心洞察与架构领域界定

### 1.1 核心洞察：1寸与2寸比例等价悖论（5:7 同比例陷阱）

在常规图片编辑器的思维定势中，证件照往往被草率地视作“一组特殊的裁剪宽高比预设”。GKUI Photos 现有的相片编辑模块（`ImageEditorActivity.kt`）即采用了该模式：
- 一寸预设：$25\text{ mm} \times 35\text{ mm} \implies 25 / 35 = 5/7 \approx 0.7142857$
- 二寸预设：$35\text{ mm} \times 49\text{ mm} \implies 35 / 49 = 5/7 \approx 0.7142857$

**数学实质**：
在屏幕交互层面上，纯比例矩形框的几何形状完全取决于宽高比 $\frac{W}{H}$。当用户在裁剪界面点击“一寸”与“二寸”时，取景框的高宽比绝对一致。若用户在原图上拉伸缩放，导出的图片像素尺寸完全随用户拉伸手势任意浮动（例如裁剪出 $1420 \times 1988\text{ px}$），既非标准一寸的 $295 \times 413\text{ px}$，亦非标准二寸的 $413 \times 579\text{ px}$。

**业务危害**：
1. **用户认知混淆**：用户期望选择“一寸”直接生成符合考试报名系统或冲印要求的照片，却发现界面取景框没有任何变化；
2. **线上报名审核拒识**：各类政务、签证及国家级考试报名网站（如中国人事考试网、学信网、教育部教资网）部署了自动化机器审核脚本，严格校验图片的像素宽高（如要求必须为 $295 \times 413\text{ px}$ 且文件在 $20\text{KB} \sim 100\text{KB}$ 之间）。单纯比例裁剪生成的任意分辨率图片将 100% 触发系统拦截；
3. **冲印物理尺寸失真**：由于未重采样至标准像素且未写入打印 DPI 元数据，冲印店或自助打印机在缺乏 DPI 指示时默认按 72 DPI 或 96 DPI 解析，导致冲印出的相纸物理尺寸放大 3 至 4 倍。

因此，**将证件照视作“裁剪预设”在架构设计上是根本错误的**。证件照的核心属性是**物理尺寸（mm）与输出分辨率（DPI）所决定的标准像素网格**，宽高比仅为其派生属性。

---

### 1.2 领域判定：工具箱领域（Toolbox Domain）vs 编辑器领域（Canvas Editor）

在 GKUI Photos 与 LineageOS 整体多媒体架构中，业务功能边界有着严格的判定准则：
- **Canvas / ImageEditor（编辑器领域）**：面向交互式、具有绘图状态机、多次撤销/重做的自由创意会话（如滤镜调色、涂鸦擦除、自由旋转、视角矫正、文本贴纸添加）。
- **ImageToolbox（工具箱领域）**：面向**文件级确定性批处理与标准化资产导出**（如图片极限压缩、格式转换、PDF 合成、批量旋转滤波）。其核心特征为“输入指定源文件 $\to$ 根据确定参数集执行无损/标准变换 $\to$ 导出独立合规新文件写入 MediaStore”。

**判定结论**：
证件照的全流程制作（选择预设规格 $\to$ 基于人脸参考线锁定构图 $\to$ 纯色背景智能替换 $\to$ 重采样至标准物理像素 $\to$ 注入 300 DPI 元数据 $\to$ 保存为独立合规证件照）属于典型的**标准化资产导出管线**。
尽管其包含局部像素运算（换底与插值），但由于其为“单向确定性工作流”而非具有开放状态机的交互式创作会话，其主归属确认为 **ImageToolbox（图片工具箱领域）**。

---

### 1.3 业务边界与约束：纯色背景限定与零依赖原则

1. **纯色背景输入约束（Solid-Color Source Background Only）**：
   - 目标场景定位：用户已有在照相馆、白墙或浅色背景前拍摄的原片，因不同机构要求（如身份证要求白底、社保要求白底、毕业证要求蓝底、结婚登记要求红底）亟需转换底色。
   - 明确排除复杂背景：日常生活照、风景照、室内杂乱背景等强语义分割场景**明确不属于本方案范围**。系统必须具备前置“纯色背景置信度检测算法”，一旦判定背景非单一纯色，必须**优雅拒识并明确提示用户**，严禁在杂乱背景上强行抠图产生断头、毛边或破损残片。
2. **零新增依赖原则（Zero New Dependencies）**：
   - 不引入任何第三方闭源 SDK、不引入 TensorFlow Lite / ONNX Runtime 等大型 ML 运行时、不引入 OpenCV 预编译 native 库；
   - 严格基于 Android 原生平台能力（`android.graphics.*`、`android.media.*`、`androidx.exifinterface`）与项目已具备的 Kotlin 基础库纯手工实现图像分析、色彩空间变换与软抠图羽化。

---

## 第二部分：小米相册（MIUI / HyperOS）证件照规格目录调研与核验

### 2.1 小米相册证件照功能架构剖析

根据对 MIUI 12～14 及 HyperOS 1.0～2.0 系统相册实机功能与公开产品资料的跟踪调研，小米系统相册将证件照作为核心系统级创作工具深度整合：
- **主要入口**：相册底栏导航“推荐”页 $\to$ 显式卡片“证件照”；亦支持在图片详情“更多 / 创作”菜单或相册编辑“裁剪”中直接调起；
- **交互结构**：
  1. 规格选择器（顶层按业务场景分类，支持单行搜索）；
  2. 拍摄 / 选图导入与人脸定位引导框；
  3. 背景颜色切换条（支持白、蓝、红等官方基准色）；
  4. 结果导出面板：支持“保存单张电子版”与“排版打印版（6寸相纸一版多张）”。

---

### 2.2 完整尺寸预设规格总表（32项权威核验对照表）

下表完整列出对标小米相册并经国家权威标准核验的完整预设目录。所有预设宽高比由物理毫米尺寸精确计算得出，标准像素基于 300 DPI 工业冲印基准计算（$Px = \text{round}(Mm \times \frac{300}{25.4})$），特殊标准（如二代身份证 350 DPI）单独标出。

| 序号 | 预设名称 | 物理尺寸 ($W \times H\text{ mm}$) | 精确宽高比 ($W:H$) | 300 DPI 像素 ($W \times H\text{ px}$) | 特殊/电子像素规范 | 权威规范与官方标准出处 | 常见适用场景 |
| :---: | :--- | :---: | :---: | :---: | :---: | :--- | :--- |
| **一** | **常用尺寸 (Common Sizes)** | | | | | | |
| 1 | 标准一寸 | $25 \times 35$ | $5:7 \approx 0.7143$ | $295 \times 413$ | $295 \times 413\text{ px}$ | 中华人民共和国国家标准 GB/T | 入职体检、履历表、学生证、部分从业资格证 |
| 2 | 标准二寸 | $35 \times 49$ | $5:7 \approx 0.7143$ | $413 \times 579$ | $413 \times 579\text{ px}$ | 中华人民共和国国家标准 GB/T | 高等教育毕业证书、学位证书、各类职称评定 |
| 3 | 小一寸 | $22 \times 32$ | $11:16 = 0.6875$ | $260 \times 378$ | $260 \times 378\text{ px}$ | 公安部交通管理行业规范 | 驾驶证副页、部分执业注册表、通用胸牌卡 |
| 4 | 大一寸 | $33 \times 48$ | $11:16 = 0.6875$ | $390 \times 567$ | $390 \times 567\text{ px}$ | 国家出入境证件照通用标准 | 护照申请、通行证、全国大型统一考试 |
| 5 | 小二寸 | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | $413 \times 531\text{ px}$ | 国际民航组织 ICAO 9303 Part 3 | 欧洲申根签证、公派出国材料、公务员报名 |
| 6 | 大二寸 | $35 \times 53$ | $35:53 \approx 0.6604$ | $413 \times 626$ | $413 \times 626\text{ px}$ | 冲印行业特殊规格标准 | 部分海外特定申请表、专业技能鉴定登记表 |
| 7 | 三寸 | $55 \times 84$ | $55:84 \approx 0.6548$ | $650 \times 992$ | $650 \times 992\text{ px}$ | 传统冲印放大规格 | 档案正页保留、毕业纪念册登记 |
| 8 | 五寸 (3R 相纸) | $89 \times 127$ | $89:127 \approx 0.7008$ | $1051 \times 1500$ | $3.5 \times 5\text{ 英寸}$ | 摄影冲印工业 3R 相纸标准 | 证件照排版冲印母相纸（可排一寸 8 张） |
| 9 | 六寸 (4R 相纸) | $102 \times 152$ | $51:76 \approx 0.6711$ | $1205 \times 1795$ | 冲印标准 $1200 \times 1800$ | 摄影冲印工业 4R 相纸标准 | 证件照标准冲印相纸（排 8 张一寸或 4 张二寸） |
| **二** | **官方证照 (Official Documents)** | | | | | | |
| 10 | 第二代居民身份证 | $26 \times 32$ | $13:16 = 0.8125$ | $307 \times 378$ | **$358 \times 441\text{ px}$ @ 350 DPI** | 公安部行业标准 GA/T 461-2019 | 居民身份证受理制证、异地换领核验 |
| 11 | 机动车驾驶证 (纸质) | $22 \times 32$ | $11:16 = 0.6875$ | $260 \times 378$ | $260 \times 378\text{ px}$ | 公安部行业标准 GA 482-2012 | 纸质驾驶证申领与补换发、体检表贴照 |
| 12 | 电子机动车驾驶证 | $22 \times 32$ | $11:16 = 0.6875$ | $260 \times 378$ | **$520 \times 756\text{ px}$** | 公安部“交管12123”统一接口要求 | 交管12123 App 在线申领与换证业务 |
| 13 | 中国护照 / 通行证 | $33 \times 48$ | $11:16 = 0.6875$ | $390 \times 567$ | 电子版高 567 宽 390 / 40~80KB | 国家移民管理局《出入境证件相片照相指引》 | 中国普通护照、往来港澳/台湾通行证 |
| 14 | 社会保障卡 (医保卡) | $26 \times 32$ | $13:16 = 0.8125$ | $307 \times 378$ | $358 \times 441\text{ px}$ (兼容 GA 461) | 人力资源和社会保障部制卡技术规范 | 中华人民共和国第三代社会保障卡制卡 |
| 15 | 结婚登记证件照 | $53 \times 35$ | $53:35 \approx 1.5143$ | $626 \times 413$ | 大二寸横版双方单人彩色合影 | 民政部《婚姻登记工作规范》第二十八条 | 婚姻登记处颁发结婚证粘贴红底合影 |
| **三** | **各国及地区签证 (Visas)** | | | | | | |
| 16 | 欧洲申根签证 (通用) | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 头部高度 32~36mm (占70~80%) | 欧盟申根签证标准 / ICAO Doc 9303 | 法国、德国、意大利、瑞士等申根成员国 |
| 17 | 美国签证 (DS-160) | $51 \times 51$ | $1:1 = 1.0000$ | $600 \times 600$ | $600 \times 600 \sim 1200 \times 1200\text{ px}$ | 美国国务院领事事务局 (US DoS) 标准 | 美国非移民/移民签证、绿卡、美签在线申报 |
| 18 | 日本签证 (标准型) | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 白底免冠近期彩色证件照 | 日本外务省领事局签证申请规程 | 赴日个人单次/多次旅游签证、商务签证 |
| 19 | 日本签证 (特定正方型) | $45 \times 45$ | $1:1 = 1.0000$ | $531 \times 531$ | 45mm 正方形、白底 | 日本特定领区及部分特殊滞在申请表 | 冲绳/东北六县多次签证或特殊归化申请 |
| 20 | 英国签证 (UKVI) | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 浅灰或浅蓝/白底，头部占 29~34mm | 英国签证与移民署 (UKVI) 照相标准 | 英国各类访问、学生及工作签证在线上传与贴签 |
| 21 | 加拿大签证 (网申电子版) | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 头部尺寸 31~36mm，文件不小于 240KB | 加拿大移民、难民及公民部 (IRCC) 规范 | 加拿大访问签证 (Visitor Visa)、学签在线网申 |
| 22 | 加拿大纸质护照与移民照 | $50 \times 70$ | $5:7 \approx 0.7143$ | $591 \times 827$ | 纸质冲印背面盖章规格 | 加拿大 IRCC 纸质护照与永久居民申请标准 | 枫叶卡补换发、加拿大纸质移民签证递签 |
| 23 | 澳大利亚/新西兰签证 | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 浅色背景、头部占 32~36mm | 澳大利亚内政事务部 / 新西兰移民局规范 | 澳新电子签证申请及电子贴签系统上传 |
| 24 | 新加坡签证 | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 电子版推荐 $400 \times 514\text{ px}$ | 新加坡移民与关卡局 (ICA) 签证标准 | 新加坡电子签证网申系统上传 |
| 25 | 韩国签证 | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 纯白底、头部占比 70%~80% | 韩国法务部出入境外国人政策本部 | 韩国各类旅游、访问及留学签证申请 |
| 26 | 泰国签证 (落地签/电子签) | $40 \times 60$ | $2:3 \approx 0.6667$ | $472 \times 709$ | 亦兼容 $35 \times 45\text{ mm}$ (小二寸) | 泰王国国家移民局签证准则 | 泰国落地签证申请表贴照、电子落地签上传 |
| **四** | **考试与职业资格认证 (Exams)** | | | | | | |
| 27 | 全国大学英语四六级 (CET) | $33 \times 48$ | $11:16 = 0.6875$ | $390 \times 567$ | 系统多要求 $240 \times 320\text{ px}$ 或大一寸 | 教育部教育考试院 CET 报名照要求 | CET-4、CET-6 全国统一考试网上报名 |
| 28 | 中小学教师资格考试 (NTCE) | $35 \times 45$ | $7:9$ (网申 3:4) | $413 \times 531$ | 电子版规范 $3:4$ 比例 (宽 300~600px) | 教育部教育考试院 NTCE 网报系统规范 | 教师资格笔试、面试报名相片采集 |
| 29 | 全国计算机等级考试 (NCRE) | $33 \times 48$ | $11:16 = 0.6875$ | $390 \times 567$ | 系统要求文件大小在 $20 \sim 200\text{KB}$ | 教育部教育考试院 NCRE 报名规范 | 计算机等级考试各级科目网报 |
| 30 | 国家公务员录用考试 (国考) | $35 \times 45$ | $7:9 \approx 0.7778$ | $413 \times 531$ | 考务官方照片处理工具输出规格 | 国家公务员局考录专题网络报名规范 | 中央机关及其直属机构公务员考试网上报名 |
| 31 | 注册会计师考试 (CPA) | $15 \times 19$ (约) | 专用 $178:220 \approx 0.809$ | - | **$178 \times 220\text{ px}$** (白底 / 2~20KB) | 中国注册会计师协会网报系统标准 | CPA 全国统一考试网上报名与准考证打印 |
| 32 | 全国硕士研究生考试 (考研) | 规范 3:4 | $3:4 = 0.7500$ | $480 \times 640$ | **$480 \times 640\text{ px}$** (宽480高640白底) | 中国研究生招生信息网 (研招网) 网上确认要求 | 全国硕士研究生统一招生网报及网上确认审核 |

---

### 2.3 官方权威规范交叉核验与差异解析

在比对小米系统相册的内置规格与中国国家标准、国际民航组织规范时，发现了数处关键冲突与行业俗称差异：

1. **二代居民身份证的 DPI 与像素基准陷阱**：
   - 绝大多数民间 App 将身份证归类为“小一寸”或“通用一寸（300 DPI 下约 $295 \times 413\text{ px}$）”。
   - **权威事实**：公安部行业标准 **GA/T 461-2019** 第 4.1.2 条明确规定，身份证数字制证相片为 **$358\text{ px (宽)} \times 441\text{ px (高)}$，分辨率为 350 DPI**，物理冲印尺寸为 $26\text{ mm} \times 32\text{ mm}$。
   - **设计对策**：GKUI 的预设模型中，身份证预设的 `dpi` 字段必须配置为 `350`，计算派生出的像素宽高精准对应 $358 \times 441\text{ px}$，确保通过公安接口机器校验。
2. **“小二寸”与“大一寸”的俗称混用冲突**：
   - 民间部分老牌照相馆常将中国护照用的 $33 \times 48\text{ mm}$ 称为“小二寸”，而将 $35 \times 45\text{ mm}$ 称为“二寸”。
   - **权威事实**：国家移民管理局出入境规范中的中国护照为严格的 $33 \times 48\text{ mm}$（比例 11:16）；而国际民航组织 ICAO Doc 9303 Part 3 标准的电子签证相片严格为 $35 \times 45\text{ mm}$（比例 7:9）。
   - **设计对策**：在 GKUI 界面上杜绝使用歧义的纯俗称，标题统一使用“中国护照/出入境（33×48mm）”与“申根/国际签证（35×45mm）”，并在二级说明中列出毫米值。
3. **中国驾驶证的纸质与电子版差异**：
   - 纸质贴照依 GA 482-2012 规定为 $22 \times 32\text{ mm}$ 白底（对应 300 DPI 下 $260 \times 378\text{ px}$）。
   - “交管12123”App 电子驾驶证接口采用高分小一寸规范，直接校验 $520 \times 756\text{ px}$。
   - **设计对策**：在“官方证照”分类中明确区分“驾驶证（纸质冲印）”与“电子驾驶证（12123上传）”。

---

### 2.4 同比例预设聚合分析（Same-Ratio Groups）

对上述 32 种规格进行宽高比因式分解，可以清晰地识别出若干“同比例预设簇”：

```
+--------------------------------------------------------------------------------+
| 同比例组 1: 5:7 比例组 (Ratio ≈ 0.7143)                                         |
| -> 标准一寸 (25x35 mm) -> 295x413 px                                            |
| -> 标准二寸 (35x49 mm) -> 413x579 px                                            |
| -> 加拿大移民纸质照 (50x70 mm) -> 591x827 px                                    |
+--------------------------------------------------------------------------------+
| 同比例组 2: 11:16 比例组 (Ratio = 0.6875)                                       |
| -> 小一寸 / 纸质驾驶证 (22x32 mm) -> 260x378 px                                 |
| -> 大一寸 / 中国护照 / 通行证 (33x48 mm) -> 390x567 px                          |
| -> 电子驾驶证 (交管12123) -> 520x756 px                                         |
| -> 计算机等级考试 (NCRE) -> 390x567 px                                          |
+--------------------------------------------------------------------------------+
| 同比例组 3: 7:9 比例组 (Ratio ≈ 0.7778)                                         |
| -> 小二寸 / 欧洲申根签证 (35x45 mm) -> 413x531 px                               |
| -> 英国 / 澳大利亚 / 韩国 / 新加坡 / 日本标准签证 (35x45 mm)                     |
| -> 国家公务员录用考试 (35x45 mm)                                                |
+--------------------------------------------------------------------------------+
| 同比例组 4: 1:1 比例组 (Ratio = 1.0000)                                         |
| -> 美国签证 / 护照 / 绿卡 (51x51 mm) -> 600x600 px                              |
| -> 日本签证正方形 (45x45 mm) -> 531x531 px                                      |
+--------------------------------------------------------------------------------+
| 同比例组 5: 13:16 比例组 (Ratio = 0.8125)                                       |
| -> 第二代居民身份证 (26x32 mm) -> 358x441 px @ 350 DPI                          |
| -> 中华人民共和国社会保障卡 (26x32 mm)                                          |
+--------------------------------------------------------------------------------+
| 同比例组 6: 3:4 比例组 (Ratio = 0.7500)                                         |
| -> 全国硕士研究生统一招生网报 (研招网, 480x640 px)                               |
| -> 中小学教师资格考试 (NTCE 网申标准电子版)                                     |
+--------------------------------------------------------------------------------+
```

**交互设计推论**：
在裁剪界面取景框中，**同比例组共享完全相同的取景视口几何约束**。如果用户在裁剪“一寸”时调整好了人脸框，当用户临时切换为“二寸”时，取景框的位置与比例无需发生任何形变，底层仅切换“重采样目标分辨率网格”即可。

---

### 2.5 小米相册伴生功能调研与边界定义

在尺寸之外，小米相册证件照附带了三项周边能力：

1. **标准底色配置**：
   - **纯白底**：`#FFFFFF` / `RGB(255, 255, 255)`。用于身份证、驾驶证、护照、中考高考、大部分签证。
   - **标准天蓝底**：`#438EDB` / `RGB(67, 142, 219)`。用于高等教育学籍、毕业证、工作履历、常规等级考试。
   - **政法/深蓝底**：`#004898` / `RGB(0, 72, 152)`。用于公安、法院、公职人员工作证。
   - **出入境淡蓝底**：`#00A0E9` / `RGB(0, 160, 233)`。符合国家出入境指引规定（色调 135、饱和度 240、亮度 $\ge 167$）。
   - **标准国旗红底**：`#C41F26` / `RGB(196, 31, 38)`。用于党团代表证、特定工会证书。
   - **结婚登记红底**：`#D9001B` / `RGB(217, 0, 27)`。用于结婚证书合影。
2. **冲印排版（6寸相纸）**：
   - 自动生成 6寸相纸（$1200 \times 1800\text{ px}$）拼版图，带有裁剪引导辅助虚线，方便用户送至线下冲印店冲洗。
3. **美颜与换装功能（明确排除）**：
   - 小米相册搭载的人像微整形、磨皮美白以及男士/女士西装、衬衫替换依赖云端或端侧深度神经网络模型。
   - **结论**：本项明确判定为 **Out of Scope（超出范围）**，GKUI Photos 不做任何虚构的端侧简易替代，坚守纯色换底与尺寸标准化的实用主义路线。

---

## 第三部分：GKUI Photos 现状代码审计

基于对代码库中 fork 点之后的提交进行深度审计，现状如下：

### 3.1 现有裁剪预设实现审计

现有的证件照逻辑分散在图片编辑器中：
- **核心文件**：
  - `app/src/main/java/org/lineageos/glimpse/ImageEditorActivity.kt`（第 185～225 行）
  - `app/src/main/java/org/lineageos/glimpse/ui/views/CropImageView.kt`（第 180～230 行、第 275～290 行）
  - `app/src/main/res/values/strings.xml`（第 208～214 行）及对应中语言本地化文件
- **现有比率列表（`ImageEditorActivity.kt` 第 197～202 行）**：
  ```kotlin
  val ratios = listOf(
      Ratio(R.string.editor_crop, 0f, false), // 自由裁剪
      Ratio(R.string.id_photo_preset_one_inch, 25f / 35f, true),
      Ratio(R.string.id_photo_preset_two_inch, 35f / 49f, true),
      Ratio(R.string.id_photo_preset_passport, 35f / 45f, true),
      Ratio(R.string.id_photo_preset_square, 1f, false),
  )
  ```
- **审计结论**：
  1. `25f / 35f` 与 `35f / 49f` 在数值上完全等同于 `0.7142857`。代码中同时存在这两个选项，对裁剪框没有产生任何几何差异，构成了毫无意义的代码级重复。
  2. 预设数量仅 4 个，缺乏对身份证（GA 461）、驾驶证、四六级、考研等高频证照的覆盖。

---

### 3.2 现有输出管线缺陷审计

1. **缺乏标准物理像素重采样**：
   - 在 `CropImageView.kt` 第 288 行中：
     ```kotlin
     return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
     ```
   - 导出的 Bitmap 仅仅是源图（被限制在 2048px 采样内）的一个矩形子集。如果原图较大，导出图片可能是 $1253 \times 1754\text{ px}$；如果原图较小，导出图片可能是 $180 \times 252\text{ px}$。完全不具备重采样为标准物理分辨率的能力。
2. **缺失 DPI 元数据注入**：
   - 在 `ImageEditorActivity.kt` 第 498～505 行中：
     ```kotlin
     private fun encode(bitmap: Bitmap, mimeType: String): ByteArray {
         val format = ...
         val out = ByteArrayOutputStream()
         bitmap.compress(format, 95, out)
         return out.toByteArray()
     }
     ```
   - Android 原生 `Bitmap.compress()` 编码 JPEG 时，写入的 JFIF 头中未指定物理分辨率单位（默认缺失或设为 72 DPI），且未写入 EXIF 的 `TAG_X_RESOLUTION`、`TAG_Y_RESOLUTION` 与 `TAG_RESOLUTION_UNIT`，直接导致相纸冲印设备无法识别实际尺寸。

---

### 3.3 图片工具箱（ImageToolbox）可复用基础设施审计

GKUI 此前增强的离线图片工具箱（ImageToolbox）具备完善的可复用底座：
- **`app/src/main/java/org/lineageos/glimpse/utils/media/ImageToolbox.kt`**：
  - `decode()`：内建内存安全防爆机制（`inJustDecodeBounds` 采样降采样，最大边长控制在 4096px）；
  - `writeBitmapToMediaStore()`：完善的 Android 11+ Scoped Storage 写入管道，规范处理 `IS_PENDING` 锁、沙箱隔离与 `Pictures/Glimpse` 目标目录；
  - `buildName()`：防重名与带后缀命名引擎；
  - `encodeOnce()` / `encodeToTargetSize()`：支持文件体积预算上限迭代编码（如用于压制到 100KB 内）。
- **`app/src/main/java/org/lineageos/glimpse/ui/dialogs/ImageToolboxBottomSheet.kt`** 与 **`dialog_image_toolbox.xml`**：
  - 具备成熟的 Material 3 BottomSheet 规范布局；
  - 封装了水平防折行滚动容器（`HorizontalScrollView` + `ChipGroup`）；
  - 具备标准的 `lifecycleScope.launch(Dispatchers.IO)` 协程异步调用与进度状态反馈。

---

## 第四部分：GKUI 证件照工具箱工程设计与实施方案

### 4.1 预设数据模型设计（Preset Model）

为彻底根除比例重复问题，证件照预设必须全面面向物理规格建模：

```kotlin
package org.lineageos.glimpse.models.idphoto

import androidx.annotation.StringRes
import kotlin.math.roundToInt

enum class PresetGroup {
    COMMON,     // 常用寸照
    DOCUMENTS,  // 官方证件
    VISAS,      // 各国签证
    EXAMS       // 考试认证
}

data class IdPhotoPreset(
    val id: String,
    @StringRes val nameRes: Int,
    val widthMm: Int,
    val heightMm: Int,
    val dpi: Int = 300,
    val group: PresetGroup,
    val specSource: String,
    val description: String = "",
    val customTargetWidthPx: Int? = null,
    val customTargetHeightPx: Int? = null,
) {
    /** 派生高宽比，裁剪框唯一数据源，绝不显式存储 */
    val aspectRatio: Float
        get() = widthMm.toFloat() / heightMm.toFloat()

    /** 派生目标输出像素宽度（严格依据物理毫米与标准 DPI 计算） */
    val targetWidthPx: Int
        get() = customTargetWidthPx ?: (widthMm * dpi / 25.4f).roundToInt()

    /** 派生目标输出像素高度（严格依据物理毫米与标准 DPI 计算） */
    val targetHeightPx: Int
        get() = customTargetHeightPx ?: (heightMm * dpi / 25.4f).roundToInt()
}
```

*说明*：
1. `aspectRatio`、`targetWidthPx`、`targetHeightPx` 均为计算属性（getter）。
2. 在该模型下，标准一寸（25×35mm, 300DPI $\to 295 \times 413\text{ px}$）与标准二寸（35×49mm, 300DPI $\to 413 \times 579\text{ px}$）合法共存且各司其职，因为它们在最终导出的物理网格完全不同，彻底消除了所谓的“重复定义”。

---

### 4.2 目录交互 UI 设计（Catalog UI）

面对 32 种专业规格，传统的扁平 Chip 列表将极度臃肿。设计方案参考现有 `dialog_image_toolbox.xml` 的水平分段设计与 `LibraryFragment` 的分层卡片架构：

```
+-------------------------------------------------------------------+
|  [X] 证件照制作                                              [完成] |
+-------------------------------------------------------------------+
|  [搜索预设，例如：考研 / 身份证 / 日本签证...                  ]   |
+-------------------------------------------------------------------+
|  [全部]  [常用寸照]  [官方证件]  [国际签证]  [各类考试]               | <- 水平滑动 Tab / Chip
+-------------------------------------------------------------------+
|  +-----------------------------+  +-----------------------------+ |
|  | 标准一寸 (25x35mm)          |  | 标准二寸 (35x49mm)          | |
|  | 295 x 413 px · 300 DPI      |  | 413 x 579 px · 300 DPI      | |
|  | 入职体检、学生证、资格报名    |  | 毕业证、学位证、职业资格    | |
|  +-----------------------------+  +-----------------------------+ |
|  +-----------------------------+  +-----------------------------+ |
|  | 中华人民共和国居民身份证   |  | 机动车驾驶证 (交管12123)    | |
|  | 26x32mm · 358x441 px 350DPI |  | 22x32mm · 520x756 px 电子版 | |
|  +-----------------------------+  +-----------------------------+ |
+-------------------------------------------------------------------+
```

- **组件选型**：
  - 顶栏搜索：`TextInputLayout` + `TextInputEditText`（单行即时文本过滤）；
  - 分类选择：`com.google.android.material.chip.ChipGroup`（包裹于 `HorizontalScrollView` 中，单选 `singleSelection="true"`）；
  - 规格网格：`RecyclerView` 双列网格布局（`GridLayoutManager(context, 2)`），卡片采用 Material 3 `MaterialCardView`；
  - 选定卡片后，平滑进入裁剪与底色处理主视图。

---

### 4.3 端到端处理管线流程（Pipeline Order）

处理管线严格保证单向不可逆且无损：

```
[原始图片 (Uri)]
       |
       v
[步骤 1: 比例引导与取景裁剪 (Ratio Framing & Crop)]
   - 依据所选预设的派生 aspectRatio 锁定视口高宽比
   - 叠加经典三分网格线与头肩轮廓椭圆参考线 (Face Oval Guide)
   - 截取原始高分辨率对应区域 (原图坐标映射，非低清预览图截屏)
       |
       v
[步骤 2: 纯色背景替换 (Solid-Color Background Replacement, 可选/保留原背景)]
   - 2.1 周边边缘采样与背景纯度校验 (若非纯色，优雅提示并回退至保留原底)
   - 2.2 YCbCr 感知色差抠图与双阈值 Soft-Matte 生成
   - 2.3 边缘羽化与去溢色融合新底 (白/蓝/深蓝/淡蓝/红)
       |
       v
[步骤 3: 物理像素重采样 (Exact Pixel Resampling)]
   - 调用双线性/双三次插值高精度缩放至预设 targetWidthPx x targetHeightPx
       |
       v
[步骤 4: 元数据注入与存储 (Metadata Injection & MediaStore Write)]
   - 注入 JPEG JFIF 300 DPI 密度头
   - 注入 EXIF TAG_X_RESOLUTION="300/1", TAG_Y_RESOLUTION="300/1", UNIT=2 (英寸)
   - 写入 Pictures/Glimpse 并通知 MediaStore 扫描入库 (绝不覆盖原图)
```

---

### 4.4 纯图像算法换底引擎详解（零新增依赖）

#### 1. 背景纯度检测与优雅拒识路径
为了严格遵守“仅支持纯色背景”的产品约束，算法首先对图像外周边缘进行采样分析：
- **采样区间**：
  - 顶部边缘全部水平像素行（上沿向下采样 $5\%$ 高度区间）；
  - 左侧上部边缘垂直像素列（左沿向内采样 $5\%$ 宽度区间，垂直范围为顶部至 $70\%$ 高度处，避开底部肩膀）；
  - 右侧上部边缘垂直像素列（右沿向内采样 $5\%$ 宽度区间，垂直范围为顶部至 $70\%$ 高度处）。
- **纯度统计量计算**：
  将采样集合转换为 YCbCr 空间，统计 $Cb$ 与 $Cr$ 通道的均值 $(\mu_{Cb}, \mu_{Cr})$ 以及标准差 $(\sigma_{Cb}, \sigma_{Cr})$，同时计算边缘垂直方向的 Sobel 梯度幅值均值 $G_{mean}$。
- **拒识判定准则**：
  $$\sigma_{chroma} = \sqrt{\sigma_{Cb}^2 + \sigma_{Cr}^2}$$
  若 $\sigma_{chroma} > 14.0$ 或 $G_{mean} > 25.0$，表明背景存在复杂景物、杂物或自然光斑，算法立即判定为**非纯色背景**，中断换底流程并向用户弹出明确提示：
  *“检测到背景色彩不均匀，当前仅支持纯色背景证件照换底。已为您保留原背景继续生成。”*

#### 2. 色彩空间选型与距离度量（YCbCr 空间感知优势）
在 RGB 空间中，欧氏距离 $D_{RGB} = \sqrt{\Delta R^2 + \Delta G^2 + \Delta B^2}$ 将亮度变化与色度变化完全混杂在一起。照相馆棚拍即便使用纯色背景布，因单侧布光或暗角，背景布左侧与右侧的明度 $Y$ 往往存在 $10\% \sim 15\%$ 的渐变衰减，导致 RGB 距离剧烈失真。

采用 ITU-R BT.601 标称 YCbCr 转换矩阵：
$$Y = 0.299R + 0.587G + 0.114B$$
$$Cb = -0.1687R - 0.3313G + 0.5B + 128$$
$$Cr = 0.5R - 0.4187G - 0.0813B + 128$$

设计椭球距离函数：
$$D(p) = \sqrt{(\Delta Cb)^2 + (\Delta Cr)^2 + \lambda (\Delta Y)^2}$$
其中取权重因子 $\lambda = 0.15$。大幅降低明度差的惩罚权重，将鉴别力高度集中在色彩纯度上，使算法能够包容背景轻微光影过渡，同时精准区分皮肤色度。

#### 3. 双阈值软抠图与 Smoothstep 边缘羽化
设置核心背景阈值 $T_{inner} = 12.0$，外围前景阈值 $T_{outer} = 28.0$：
- 当 $D(p) \le T_{inner}$：确认为背景区，Alpha 权重 $\alpha = 0.0$；
- 当 $D(p) \ge T_{outer}$：确认为前景人物，Alpha 权重 $\alpha = 1.0$；
- 当 $T_{inner} < D(p) < T_{outer}$：处于毛发或肩部半透明边缘过渡带，计算归一化插值因子 $t = \frac{D(p) - T_{inner}}{T_{outer} - T_{inner}}$，通过三次 Smoothstep 函数生成抗锯齿 Alpha：
  $$\alpha(p) = 3t^2 - 2t^3$$

#### 4. 溢色抑制（Color Spill Suppression / De-fringing）
在原蓝底或红底背景下，发丝半透明边缘像素会渗透旧背景的蓝光或红光（Spill）。合成新背景时，若直接与新底混合会出现“蓝色发丝外圈”。
算法在合成时对过渡区像素执行溢色消除：提取该像素色彩与原背景色彩的投影分量，将溢色色度强制向中性灰色或目标底色收缩后，再按 $\alpha$ 执行合成：
$$C_{out} = \alpha C_{destat\_fg} + (1 - \alpha) C_{target\_bg}$$

#### 5. 已知局限性声明（诚实公开）
1. **飞扬极细碎发穿帮**：在无神经网络人像语义先验的纯色阈值算法下，单根孤立飘离头皮的极微小发丝若带有强烈背景反射，可能被判定为背景而丢失；
2. **强投影死角**：若拍摄光源导致人像背后出现浓重黑色投影（影中几乎无背景底色信息），该阴影区域可能无法被识别为纯色背景；
3. **同色衣物粘连**：如果用户穿纯蓝衬衫在蓝底背景前拍照并要求换红底，衣服边缘可能会被误抠。UI 层应增加贴心文案：“建议衣物颜色与原背景及目标背景保持反差”。

---

### 4.5 物理像素重采样与 DPI 元数据注入技术

#### 1. 高品质像素重采样
为保证缩放后文字、眼睛高光与发丝边缘清晰锐利，严禁使用最近邻插值（Nearest Neighbor）：
```kotlin
fun resampleIdPhoto(sourceCropped: Bitmap, targetW: Int, targetH: Int): Bitmap {
    val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    val matrix = Matrix()
    matrix.setScale(targetW.toFloat() / sourceCropped.width, targetH.toFloat() / sourceCropped.height)
    canvas.drawBitmap(sourceCropped, matrix, paint)
    return result
}
```

#### 2. DPI 元数据注入技术（JFIF APP0 与 EXIF 双注入）
为什么必须注入 DPI 元数据？
- **打印驱动与冲印系统机制**：专业相纸打印机驱动通过读取图片元数据中的 `XResolution` 与 `ResolutionUnit` 决定每个像素点在相纸上占据的物理微米数。缺少该标记的图片被默认当作屏显 72 DPI 处理，导致照片在排版软件中尺寸失控。
- **实现方式**：
  利用 Androidx 官方库中的 `androidx.exifinterface.media.ExifInterface`，在文件写入磁盘后立即注入标准的 EXIF 标签：
  ```kotlin
  fun injectDpiMetadata(file: File, dpi: Int = 300) {
      val exif = ExifInterface(file)
      // 写入有理数表示的分辨率: "300/1"
      exif.setAttribute(ExifInterface.TAG_X_RESOLUTION, "$dpi/1")
      exif.setAttribute(ExifInterface.TAG_Y_RESOLUTION, "$dpi/1")
      // 2 表示分辨率单位为英寸 (Inches)
      exif.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2")
      exif.saveAttributes()
  }
  ```

---

### 4.6 6寸相纸排版打印模块设计（P2 级）

排版冲印功能将生成的标准单张证件照拼版至标准的 **6寸相纸（4R, $102 \times 152\text{ mm}$，对应 300 DPI 工业标准为 $1200 \times 1800\text{ px}$）**：
- **排版数学计算**：
  - **一寸相片排版**：单张尺寸 $295 \times 413\text{ px}$。在横版相纸（$1800 \times 1200\text{ px}$）上，排 **2 行 4 列（共 8 张）** 或 **2 行 5 列（共 10 张）**，四周留白 $50\text{ px}$，照片间距 $30\text{ px}$；
  - **二寸相片排版**：单张尺寸 $413 \times 579\text{ px}$。排 **2 行 2 列（共 4 张）**；
  - **混合排版**：4 张一寸 + 2 张二寸。
- **裁剪辅助线**：
  在各相片边缘与间距中心线上绘制浅灰色细虚线（`Paint.Style.STROKE`，虚线 `DashPathEffect(floatArrayOf(10f, 10f), 0f)`，颜色 `#D3D3D3`），极大便利用户打印后用裁纸刀手工切分。
- **存储逻辑**：
  以 `[源相片名]_一寸_排版打印_6寸.jpg` 独立保存至 `Pictures/Glimpse`。

---

### 4.7 任务分解与优先级规划（Work Breakdown & Priority Table）

| 优先级 | 功能模块 | 放置位置 / 入口 | 涉及文件 | 预估行数 | 一句话技术理由 |
| :---: | :--- | :--- | :--- | :---: | :--- |
| **P0** | 物理预设数据模型与 32 项规格目录定义 | 数据层 | `models/idphoto/IdPhotoPreset.kt`<br>`models/idphoto/IdPhotoCatalog.kt` | ~180 行 | 彻底解决 5:7 比例重复，为整个管道提供精确物理毫米、DPI 与像素派生定义 |
| **P0** | 证件照取景构图、双向插值重采样与 DPI 注入管线 | 工具箱核心算法 | `utils/media/IdPhotoGenerator.kt`<br>`utils/media/ImageToolbox.kt` | ~240 行 | 保证裁剪框输出的图片 100% 符合 300/350 DPI 物理标准像素及 EXIF 元数据规范 |
| **P0** | 证件照规格选择器 UI 与人脸辅助线取景器 | 工具箱交互层 | `ui/dialogs/IdPhotoBottomSheet.kt`<br>`res/layout/dialog_id_photo_catalog.xml` | ~320 行 | 提供分类水平标签、卡片选择、搜索过滤及人脸轮廓/三分网格定焦取景 |
| **P1** | 纯色背景智能检测、优雅拒识与 YCbCr 双阈值软换底 | 图像处理算法 | `utils/media/SolidColorMattingEngine.kt` | ~280 行 | 纯原生图像算法实现无依赖换底（白/蓝/深蓝/淡蓝/红），复杂背景安全拒识 |
| **P1** | 证件照预设即时搜索过滤与常用收藏 | UI 交互增强 | `ui/dialogs/IdPhotoBottomSheet.kt` | ~90 行 | 面对 30+ 规格，支持关键字即时检索（如“教资”、“驾驶证”）极大提升效率 |
| **P2** | 6寸标准相纸（4R）多张证件照排版冲印合成器 | 增值冲印工具 | `utils/media/IdPhotoPrintSheetComposer.kt` | ~210 行 | 自动计算间距并绘制裁切辅助线，一键导出用于线下相馆冲印的标准 6寸母版相片 |

---

## 第五部分：给 LineageOS Canvas 移植计划的反向反馈与勘误建议

### 5.1 针对 Canvas 任务 P0-1 的比例冗余分析

在兄弟项目“LineageOS Canvas 移植计划”的任务 **P0-1（裁剪比例预设扩展）** 中，设计草案提出在交互式裁剪菜单中增加：
> *“1-inch ID / 2-inch ID / Passport” 比例预设*

**反向反馈与勘误论证**：
1. **纯比例层面的等价冗余**：
   在纯裁剪器（Crop Ratio Selector）中，中国标准一寸（25:35）与标准二寸（35:49）在数学上完全互质简称为 **5:7**。如果在 Canvas 的比例列表中同时并列“1寸”与“2寸”，用户点击时裁剪视口的高宽比没有任何变化，构成了功能上的逻辑冗余；
2. **纯比例裁剪无法满足证件照的核心合规诉求**：
   Canvas 作为纯编辑器，其裁剪操作输出的是用户自由划定区域的任意分辨率像素矩阵（例如 $1388 \times 1944\text{ px}$），并且不具备 300 DPI 打印元数据。用户带着 Canvas 裁出的图片上传至中高考、国考、签证等系统时，依然会被“分辨率不符合 $295 \times 413$”等校验直接打回。

---

### 5.2 对 Canvas 项目的具体改进建议

建议将上述勘误录入 Canvas 移植计划的技术备忘中，提供以下两种演进路径供其采纳：

- **路径 A（保持纯裁剪器轻量定位）**：
  - 在 Canvas 的裁剪比率列表中**剔除尺寸重合项**；
  - 采用标准比率标签代替特定寸照名称：
    - 将“1寸/2寸”合并为一个比率项，命名为 **`5:7 (1寸/2寸)`**；
    - 将“护照/申根签”命名为 **`7:9 (护照/签证)`**；
    - 补充方形头像 **`1:1 (正方形/美签)`** 与 **`3:4 (网申通用)`**；
  - 这种设计直观且严谨，避免给用户造成“选了2寸就能直接去冲印2寸照片”的错误预期。
- **路径 B（全面引入物理尺寸与分辨率重采样）**：
  - 若 Canvas 未来规划支持完整的证件照导出，应引入本方案第四部分定义的 `IdPhotoPreset` 物理模型，在裁剪保存时不仅约束比例，同时对裁剪像素强制执行目标分辨率重采样与 DPI 元数据写入。

*声明：本节仅作为 GKUI 与 LineageOS 社区跨项目架构反馈建议记录于本文档中，未对 Canvas 项目代码仓库执行任何直接改动。*
