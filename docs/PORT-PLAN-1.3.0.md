# 1.3.0 视频解析移植方案（确认稿）

> 状态：**主要决策已确认，暂未动工**（剩余 2 项技术待实测，见第 9 节）
> 上游基线：`3e51a4d`（#204）　桌面基线：`dca79c3`
> 本文只记录「要做什么、怎么做、边界在哪」，确认后再按里程碑开工。

---

## 1. 移植范围

上游在上次对齐点 `cad7718`（#179）之后新增 10 个提交：

| 提交 | 标题 | 桌面影响 |
|---|---|---|
| `0a7c71e` | #183 视频解析（7 平台 + 账号 + 结果页 + 预览 + DASH/动图） | 主体工程 |
| `10a947c` | #184 设置页容器变换 + 支持页卡片几何 | 容器变换不适用；卡片几何待核 |
| `469effe` | #185 网盘图标 48→40dp + 视频卡片拆分 | 前半直接移植，后半随视频功能 |
| `df4060f` | #186 版本 1.3.0 | 桌面 `version.txt` |
| `c51ba98` | #188 哔哩哔哩登录 + DASH 高清链路 | 依赖合轨方案 |
| `fda7238` | #189 小红书去水印原图 + 回退提示 | 直接移植 |
| `94dda91` | #192 清理 mp4parser 残留 | 不适用 |
| `7c1e7d0` | #196 README 补 PC 说明 | 核对文案 |
| `1d94a75` | #202 动图（Live Photo）解析与合成 | 需改造（Bitmap） |
| `3e51a4d` | #204 恢复深链接 + 删除弹窗整行可点 + 返回键 | 仅「整行可点」可移植 |

新增代码量约 8700 行（#183）＋ 1200 行（#188/#202 等），是本轮最大的一次对齐。

---

## 2. 编解码探针（已实测）

### 2.1 方法

在现有 `Main.kt` 诊断通道临时加入 `--codec-probe`：初始化 JCEF → 打开 `data:text/html` 页面执行 `canPlayType` → 结果经 `127.0.0.1` 回环打印后退出。探针代码**已还原**，未进入仓库。

### 2.2 结果

内置 Chromium：`Chrome/132.0.0.0`（jcefmaven `cef-132.3.1`）。

| 编解码 | 结果 |
|---|---|
| H.264 Baseline（`avc1.42E01E`） | `""` 不支持 |
| H.264 High（`avc1.640028`） | `""` 不支持 |
| HEVC（`hvc1`） | `""` 不支持 |
| AAC（`mp4a.40.2`） | `""` 不支持 |
| MP3 | `probably` |
| VP8 / VP9 | `probably` |

### 2.3 结论

**不能用 JCEF 的 HTML5 `<video>` 播放各平台视频**——CEF 标准构建不含专有编解码器，而抖音/快手/微博/小红书/B站输出的都是 H.264 + AAC。JCEF 在本项目中继续只承担「登录页」职责。

---

## 3. 架构决策

### 3.1 应用内播放器：JavaFX MediaView（选定）

- 体积：javafx-base/graphics/media + Windows 原生约 **15~25 MB**（待实测），明显小于 VLCJ（40~60 MB）
- 能力：官方文档支持 **H.264/AVC + AAC**（MP4），另有 MP3；**不支持 HEVC**
- 集成：`JFXPanel` → Compose `SwingPanel`；`Platform.setImplicitExit(false)`，`MediaView` 自带播放/暂停/进度/音量，全屏与倍速由外层 Compose 控件补充
- 网络：JavaFX `Media` 不支持自定义请求头，统一经 **本地回环代理**（见 3.2）提供 `Referer` / `User-Agent` / `Cookie`

排除项：

| 方案 | 排除原因 |
|---|---|
| JCEF（HTML5 video） | 实测无 AVC/AAC |
| VLCJ | 体积 40~60 MB，与当前瘦身方向冲突 |
| Media Foundation（JNA 直调） | 零体积但 COM/D3D11 互操作代码量与维护成本过高 |
| JCodec（纯 Java 解码） | H.264 解码不完整、性能不足，不能用于正片播放 |

### 3.2 本地回环代理（共用基础设施）

`com.sun.net.httpserver` 起在 `127.0.0.1:随机端口`，职责：

1. 注入上游请求头（`Referer` / `User-Agent` / `Cookie`）——B站、微博 CDN 防盗链必需
2. 透传 `Range`，支持拖动进度条
3. HLS：把播放列表里的分片地址改写为代理地址（复用现有 `HlsRequestPolicy` 的同源判断）
4. **来源校验**：只绑定 `127.0.0.1`（绝不监听 `0.0.0.0`），并校验请求里的**会话随机 token**（每次启动生成），只服务路径白名单（`/media`、`/hls/master.m3u8`、`/hls/seg`）——防止本机其他进程扫到端口后把代理当开放中转
5. 进程退出即关闭；启动失败（端口占用等）直接降级为「不支持预览」，不换端口裸奔

### 3.3 媒体形态与预览能力

| 平台 | 解析产物 | 在线预览 |
|---|---|---|
| 抖音 | MP4 / 图集 / 动图 | MP4 可播；图集走 Compose 查看器 |
| 快手 | MP4 | 可播 |
| 微博 | MP4 / 动图（`.mov`） | 可播；`.mov` 视 JavaFX 支持，必要时重封装 |
| 小红书 | 图集（去水印原图） | Compose 查看器 |
| 哔哩哔哩 | DASH（fMP4 分轨）或 durl（单文件） | **仅 durl 单文件可在线预览**；DASH 需先合轨，**不在 M6 验收范围内**，作为合轨落地后的增强项 |
| AcFun | m3u8 | **必须在线预览**（见 3.3.1）；不接受「先下后播」 |
| Pixiv | 图片 / 动图 | Compose 查看器 |

### 3.3.1 HLS 在线预览（硬需求）

AcFun 只提供 HLS。已确认：先下载再播放等价于普通下载，预览就失去意义，所以 HLS 必须能边下边播。两条路径：

1. **首选（但预期不高）：JavaFX 原生 HLS**
   `javafx.scene.media` 官方能力表列有 HLS（MP2T 容器，H.264/AAC），但该能力有平台限定；JavaFX Media 的 HLS 实现历来薄弱，**Windows 上大概率不能直接吃 `.m3u8`**。仍需实测确认（M6 前置探针，做法与本次 CEF 探针相同）：命中则零成本，否则按路径 2 排期。
2. **兜底：自研渐进式重封装代理**
   本地回环代理按顺序拉取 TS 分片，解析 TS 包抽出 H.264/AAC 基本流，边转封装为 fragmented MP4 边输出给 JavaFX 播放。工作量约 600~900 行、零第三方依赖，复杂度集中在 TS 解析与 PTS/DTS 时间轴重建。仅在路径 1 失败时启用。

明确不采用：内置 ffmpeg（约 +80 MB）、先整包下载再播放。

### 3.4 DASH 合轨：A2 纯 Java 合轨（已选定）

上游 `DashMuxer` 依赖 `android.media.MediaCodec / MediaExtractor / MediaMuxer`，桌面无等价 API，改用纯 Java 方案：

- 引入 `org.mp4parser:isoparser`（约 0.6 MB，纯 Java，无原生库）
- 解析视频/音轨两个 fMP4 流的 `moov` 与 `moof/mdat`，按时间戳对齐后写入单个 MP4（只重封装、不重编码，画质无损、CPU 开销极低）
- 产物直接落到下载目录，与现有任务共用完成回调与文件校验

风险：mp4parser 对 B站 fMP4 的兼容性需用真实样本实测；若失败，退化方案是只取 durl 单文件并在文案标注清晰度上限（不作为首选）。

### 3.5 抖音「隐藏 WebView」路线

上游用 Android 隐藏 WebView 跑完页面的 `acrawler` 挑战后取详情响应（HTTP/SSR 路线失败时的兜底，超时 40s）。

桌面等价实现：JCEF 隐藏 `CefBrowser` + `CefMessageRouter` 的 js query 取回结果（对应 Android 的 `evaluateJavascript` 回调），仅在 SSR 路线失败时启用，不阻塞主流程。

### 3.6 Pixiv 网页登录（桌面独有，**本轮实施**）

手机端不做网页登录，是因为移动 WebView 过不了 reCAPTCHA Enterprise；桌面 JCEF 是完整的桌面 Chromium（UA、指纹、`navigator.webdriver` 均正常），通过率更高。

计划：

- `VideoPlatform.PIXIV`：`loginUrl = "https://accounts.pixiv.net/login.php"`、`manualCookieOnly = false`、`cookieDomain = "pixiv.net"`
- 登录态仍从 `CefCookieManager` 读 `PHPSESSID`
- **保留「手动填 Cookie」入口作为兜底**：验证码被拒时提示切换到手动模式
- 若实测仍被风控，则回滚为「仅手动 Cookie」，不保留必然失败的人口

### 3.7 动效降级策略（软件渲染 / 减少动画开关，跨里程碑约束）

**背景**：`RenderApiChooser` 在老显卡上把 `skiko.renderApi` 切成 `SOFTWARE`（如 ATI Radeon 3000 这类机型）。此时每帧都是 CPU 光栅化，125% DPI 下持续交互实测只有约 10 FPS（见 `Main.kt` 注释）。这类机器往往 CPU 也老，继续播放入场位移动画、列表增删动画、模糊/大阴影，只会把 CPU 吃满、体感更卡。

**判定**：`MotionPolicy.reducedMotion = (skiko.renderApi == "SOFTWARE") || 设置里的「减少动画」开关`。前者由 `Main.kt` 启动时写入，后者来自 `SettingsRepository`；两者取「或」后全局只读，UI 层统一读取，不在各页面重复判断。

**降级规则（`reducedMotion` 生效时）**：

| 动效 | 正常渲染 | 降级后 |
|---|---|---|
| 页面切换（`AnimatedContent` 位移+淡入） | 保留 | 只保留 ≤80ms 淡入，或直接切换 |
| 列表增删动画（`animateItem`） | 保留 | 关闭 |
| 无限装饰动画（shimmer / 呼吸 / 持续旋转） | 保留 | 停止，改静态占位 |
| 模糊、大范围阴影、半透明叠层 | 保留 | 去模糊，改纯色/描边 |
| 悬停/按压缩放 | 保留 | 关闭缩放，仅保留颜色反馈 |
| 滚动视差、滚动缩放 | 保留 | 关闭 |
| 视频自动播放 | 自动 | **不自动**，需手动点播放 |
| 进度数值、加载文字 | — | 保留（属于信息反馈，不是装饰） |

**实现约束**：

- 动画时长统一走一个入口（如 `Motion.duration(base)`），软件渲染下直接返回 0
- 新代码禁止用 `rememberInfiniteTransition` 做装饰性效果；需要时先经 `MotionPolicy` 判断
- 播放器控制条的自动隐藏动画同样走该策略（`reducedMotion` 下改为常显）
- M3~M6 新增的所有 UI 从第一版就必须遵守本节，不要留到收尾再补

**「减少动画」开关（M7 落地）**：设置 → 外观 新增该开关，与软件渲染判定取「或」并入 `MotionPolicy.reducedMotion`。面向晕动症用户，以及「机器老但没触发 SOFTWARE」的情况。开关持久化到 `SettingsRepository`，切换后立即生效（Compose 可观察状态，不要求重启）。

---

## 4. 数据层变更

### 4.1 新表 `video_account`

```sql
CREATE TABLE IF NOT EXISTS video_account (
  id TEXT PRIMARY KEY,
  platform TEXT NOT NULL,
  cookie TEXT NOT NULL DEFAULT '',
  nickname TEXT NOT NULL DEFAULT '',
  updatedAt INTEGER NOT NULL,
  isPrimary INTEGER NOT NULL DEFAULT 0,
  remark TEXT NOT NULL DEFAULT ''
);
```

- 一个平台一行，凭证只有完整 Cookie 头；沿用 `MultiAccountEntity` 的主账号/备注语义
- 写入前经 `SecureAccountDaos` 同款列级加密（`yunx:v1:<iv>:<ct>`）

### 4.2 `download_task` 新增 3 列

| 列 | 用途 |
|---|---|
| `mergeAudioUrl` | DASH 音轨地址 |
| `mergeAudioHeadersJson` | 音轨请求头（加密落库） |
| `motionUrl` | 动图资源地址（Live Photo 合成用） |

迁移方式与现有 20 列一致：`PRAGMA table_info` 探测后 `ALTER TABLE`，全部读取走 `runCatching` 兜底。

**迁移前备份与保留策略**：备份文件名带目标 schema 版本与时间戳（`yunx.db.bak-v23-<yyyyMMddHHmmss>`），**只保留最近 2 份**，更早的自动删除；迁移成功后保留最新一份便于回滚，其余由「设置 → 储存目录」的手动清理入口处理。

---

## 5. 模块映射表

| 上游文件 | 桌面落点 | 处理 |
|---|---|---|
| `data/network/video/VideoPlatform.kt` | 同名 | 直译（Pixiv 改登录方式） |
| `VideoModels.kt` / `VideoHttp.kt` / `VideoParser.kt` / `VideoParsers.kt` / `VideoLinkParser.kt` | 同名 | 直译 |
| `DouyinParser.kt` / `KuaishouParser.kt` / `WeiboParser.kt` / `AcFunParser.kt` / `XhsParser.kt` / `BilibiliParser.kt` / `PixivParser.kt` | 同名 | 直译；`android.util.Log` → `Log` |
| `HeadlessWebView.kt` | `JcefHiddenWebView.kt` | 重写（JCEF + MessageRouter） |
| `data/repository/VideoParseRepository.kt` / `VideoAccountRepository.kt` | 同名 | 直译 |
| `data/db/VideoAccountEntity.kt` / `VideoAccountDao.kt` / `SecureAccountDaos` 片段 | 同名 | 改造为 JDBC + 加密装饰器 |
| `data/download/DashMuxer.kt` | 同名 | **重写**（MediaCodec → mp4parser A2） |
| `data/download/MotionPhotoWriter.kt` | 同名 | 改造：`BitmapFactory`/`Bitmap.compress` → `ImageIO`，其余字节操作直译 |
| `DownloadManager.kt`（DASH/动图分支，+217 行） | 同名 | 改造：接入现有 C1/C2/直写分支，冲突需手工合并 |
| `ui/screens/VideoAccountSection.kt` / `VideoAccountSheet.kt` | 同名 | 改造：复用 `AccountListSection` 的卡片/备注模式 |
| `ui/login/VideoLoginScreen.kt` | 同名 | 改造：JCEF 登录（复用 `JcefLoginPane`） |
| `ui/screens/VideoResultSection.kt` | 同名 | 直译（Compose） |
| `ui/items/PlatformLogos.kt` + 18 个 PNG | `resources/platform_logos/` | 直译，约 110 KB |
| `ui/preview/MediaPreviewScreen.kt` | 同名 | **重写**（ExoPlayer → JavaFX MediaView） |
| `ui/preview/PreviewLauncher.kt` / `PreviewTextLoader.kt` | 同名 | 直译 |
| `ui/viewmodel/ResolveViewModel.kt`（+390 行） | 同名 | 手工合并（桌面已有 C1/C2、多账号、任务级目录） |
| `ui/viewmodel/VideoAccountViewModel.kt` | 同名 | 直译 |
| `AndroidManifest.xml` 深链接 | — | 不适用（桌面走自定义协议注册） |
| `MainActivity` 返回键处理 | — | 不适用 |

---

## 6. 里程碑与验收

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M1 解析内核** | video 包 10 个文件 + 7 个 Parser + LinkParser + 单测 | 7 个平台各 1 条真实链接解析出标题/素材/直链；单测通过 |
| **M1.5 并行探针（只读）** | 真实 B站样本验 mp4parser 合轨；真实 `.m3u8` 验 JavaFX 播放 | 清掉 9.2 的第 1、2 条；结论回填本文档后再决定 M5/M6 的实现路径 |
| **M2 数据层** | `video_account` 表 + 3 列迁移 + 加密 DAO | 新旧库都能启动，旧库迁移后往返读写正常 |
| **M3 账号与登录** | 账号区、通用视频登录页（JCEF）、Pixiv 网页登录 + 手动 Cookie 兜底 | B站/小红书/Pixiv 能读到 Cookie 并成功解析；Pixiv 两种方式都能存 |
| **M4 解析 UI** | 解析页入口、结果页、平台 Logo、批量选择 | 粘贴链接可识别平台并落到结果页；图集可浏览 |
| **M5 下载链路** | 单文件 / HLS（AcFun）/ 动图合成 / DASH 合轨（A2） | 各形态下载后文件可正常播放；B站合轨产物音画同步；动图在系统看图器里是合法 JPEG |
| **M6 播放器** | 本地代理 + JavaFX MediaView + 全屏/倍速；按 M1.5 的 HLS 探针结果选择路径 1/2 | MP4（含 B站 durl）在线播；AcFun m3u8 必须能在线边下边播（不允许先下后播）；拖动、全屏正常。**B站 DASH 不在本里程碑验收范围** |
| **M7 细节与收尾** | #185 图标尺寸、#189 小红书原图、#204 弹窗整行可点、现有高频动画接入 3.7、外观页「减少动画」开关、文案、1.3.0 | 与上游视觉一致；软件渲染下无装饰动画残留；开关切换后动效立即变化并持久化；`version.txt` 1.3.0；`UPSTREAM-DIFF §9.14` 记录取舍 |

依赖顺序：M1 → **M1.5（两条探针并行，可与 M2 同时进行）** → M2 → M3 → M4 → M5 → M6，M7 可穿插。探针结论出来后再排 M5/M6 的具体实现，避免后期返工。

---

## 7. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| JavaFX 打包/初始化 | 启动崩溃、jpackage 缺原生库 | 先做最小 Demo 验证 JFXPanel + jpackage app-image |
| JavaFX 原生 HLS 不可用 | AcFun 无法在线预览 | M6 前置探针；兜底启用 3.3.1 路径 2（TS→fMP4 渐进式重封装代理） |
| mp4parser 处理 B站 fMP4 失败 | B站高清不可用 | 用真实样本先验证；失败退回 durl 单文件并标注清晰度上限 |
| 平台风控/接口变动 | 解析失败 | 保留诊断日志埋点，与上游保持同步 |
| 防盗链 | 预览 403 | 统一走本地代理注入请求头 |
| 桌面 `DownloadManager` 已有 C1/C2/直写分支 | 合并冲突 | 上游 DASH 分支单独成函数，不改动现有分片路径 |
| 软件渲染机型仍保留装饰动画 | CPU 占满、交互掉帧 | 按 3.7 统一降级，新增 UI 一律经 `MotionPolicy` 判定 |
| DB 迁移不可回退 | 老用户库损坏 | 迁移前自动备份（带版本+时间戳，只留最近 2 份），失败保留原库 |

---

## 8. 体积影响（预估）

| 项 | 变化 |
|---|---|
| JavaFX 17（base/graphics/media + win 原生） | **+15~25 MB** |
| mp4parser（A2 合轨） | +0.6 MB |
| 平台 Logo | +0.11 MB |
| 本地回环代理（含可能的 TS→fMP4 重封装） | 0（无额外依赖） |

---

## 9. 已确认与待实测

### 9.1 已确认

| 项 | 决定 |
|---|---|
| 应用内播放器 | **JavaFX MediaView**，版本跟随 toolchain（JDK 17 → JavaFX 17） |
| JCEF 播放 | 不采用：实测无 H.264/AAC（见第 2 节） |
| DASH 合轨 | **A2**：`org.mp4parser:isoparser` 纯 Java 重封装，保留 B站高清 |
| AcFun HLS | **必须在线预览**，不接受「先下后播」 |
| Pixiv 网页登录 | **本轮实施**，同时保留手动填 Cookie 兜底 |
| 抖音隐藏 WebView | 用 JCEF 隐藏浏览器实现，仅作 SSR 失败时的兜底 |

### 9.2 待实测（动工前 / 里程碑内）

1. **JavaFX 17 在 Windows 上能否直接播 `.m3u8`** —— 预期**大概率不支持**，按路径 2（自研 TS→fMP4 代理）准备；万一支持则省掉这部分工作
2. **mp4parser 能否正确处理 B站 fMP4 样本** —— 决定 DASH 合轨是否需要换实现
3. **JavaFX Media 对微博 `.mov` 的支持** —— 不支持则在下载链路上重封装为 MP4；重封装直接复用 A2 引入的 mp4parser，不新增依赖

第 1、2 条由 **M1.5 并行探针**清掉，结论回填本文档后再排 M5/M6。

---

## 10. 附：上游提交与桌面提交对照

| 上游 | 桌面 |
|---|---|
| `cad7718`（上次对齐点） | `0d2fd9f` / `11c2f92` |
| 本轮 `3e51a4d` | 待定（M7 完成后统一记录到 `UPSTREAM-DIFF.md §9.14`） |
