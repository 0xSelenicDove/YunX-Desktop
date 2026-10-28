# 构建与打包

> 概览见 [README](../README.md)

## 环境要求

要求：Windows 10/11 x64。

| 依赖 | 说明 |
| --- | --- |
| JDK 17 | 缺失时 `jdk-setup.ps1` 自动下载便携版 |
| Gradle | 由 wrapper 自动拉取，无需手动安装 |
| **C++ 编译器** | 托盘菜单配色桥接层 `native/darkmode.cpp` 需编译为 `darkmode.dll`：优先 `cl.exe`（Visual Studio 2019/2022，勾选「使用 C++ 的桌面开发」工作负载），否则回退 `g++`（MinGW） |
| Inno Setup 6 | 仅打安装包需要；缺失时脚本自动下载并静默安装 |

**缺 C++ 编译器时**：`run` / `package` / `installer` 会在编译 `darkmode.dll` 这一步失败；
`run.ps1 build` 只编译 Kotlin，不受影响。

## 命令

```powershell
git clone https://github.com/tidain/YunX-Desktop.git
cd YunX-Desktop

.\run.ps1 run        # 编译并启动（开发，含 darkmode.dll）
.\run.ps1 build      # 仅编译 Kotlin（不需要 C++ 编译器）
.\run.ps1 package    # 免安装便携版 → release\YunX-Desktop\
.\run.ps1 installer  # 单文件安装程序 → release\YunX-Desktop-setup-*.exe
```

也可双击 `run.bat`（走同一入口）。中文向导语言包随仓库分发（`installer\ChineseSimplified.isl`）。

## 版本号

根目录 `version.txt` 是**唯一版本来源**：jpackage 版本、便携包 / 安装包的文件名与程序版本、
应用内「关于页 / 更新检测」全部由它派生。**发版只需改这一个文件。**

启动器 exe 的版本资源（公司 `tidain`、版权 `Copyright (C) 2026 tidain`）也在打包时由该版本号生成。

## 打包产物

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| 便携版 | `release\YunX-Desktop\` | 整个文件夹拷走即用，双击 `YunX-Desktop.exe` 运行，无需安装 |
| 安装程序 | `release\YunX-Desktop-setup-<版本>.exe` | 每用户安装到 `%LOCALAPPDATA%\Programs\YunX-Desktop`，无需管理员权限；创建开始菜单 / 桌面快捷方式「云析」，可卸载 |

打包脚本会自动结束正在运行的程序实例，并在输出目录被占用时重试删除。

便携包约 210MB（其中运行期 JRE 约 40MB、CEF 原生库压缩包约 88MB），体积主要来自 Chromium。

## 发布包体积裁剪

打包脚本只改**发布包里的文件**，不改变编译期依赖，也不改变任何功能：

| 项目 | 处理 | 效果 |
| --- | --- | --- |
| `material-icons-extended` | 扫描所有 class 的常量池，只保留实际被引用的图标类 | 0.2 MB（完整包 36 MB） |
| `sqlite-jdbc` | 只保留 `org/sqlite/native/Windows/x86_64` 原生库 | 2.37 → 0.75 MB |
| `skiko-awt-runtime-windows-x64` | 从 `app\` 载荷里去掉 jar（原生库仍解到 `app\` 供加载） | −10.3 MB |
| **CEF 原生库** | jar 内嵌 `tar.gz` 重压成 `tar.xz`（LZMA2 + BCJ），语言包只留 `zh-CN` | 131 → 88 MB |
| `runtime\lib\jvm.lib` / `runtime\bin\splashscreen.dll` | 运行时用不到（链接期文件 / jpackage 默认启动器闪屏），jlink 后删除 | −1.2 MB |

### CEF 原生库重压（tar.xz）

发布包**不再**随带 `jcef-natives-*.jar`（解包后 261MB，gzip 后 120MB），而是：

1. 打包时 `:desktop:exportJcefNatives` 单独导出原生 jar（`exportRuntimeLibs` 会排除它），
   由 `JcefNativesPacker` 重压成 `app\jcef-natives.tar.xz`；
2. 运行期首次需要内嵌浏览器时，`JcefNativesInstaller` 把它解到 `<缓存>\jcef-bundle`，
   写 `install.lock` 与就绪标记（记录压缩包大小 / 时间，换包自动重解）；
3. 随后 `CefAppBuilder.setSkipInstallation(true)`，jcefmaven 不再去找 classpath 里的原生 jar。

开发运行（`run.ps1 run`）不吃这套：原生 jar 仍在 classpath 上，也没有这个压缩包，
`ensureInstalled` 返回 false，行为与改动前一致。

> 升级 jcef 版本只需改 `desktop/build.gradle.kts` 里的依赖坐标，打包脚本与压缩器无需改动。

## 体积与本地裁剪包

`desktop/libs/` 下有三个**随仓库分发**的 jar，都是为了压缩体积或规避仓库坐标问题，改动前先读各自的说明：

| 文件 | 作用 | 重新生成 |
| --- | --- | --- |
| `material-color-utilities-1.0.0.jar` | 主题种子色（该库无公开 Maven 坐标，从 material 1.14.0 AAR 解包 `classes.jar`） | 升级 material 依赖时需重新解包 |
| `sqlite-jdbc-3.47.1.0-win.jar` | 只保留 `org/sqlite/native/Windows` 的 SQLite JDBC（官方包 13.6 MB → 2.4 MB） | 升级 sqlite-jdbc 时：解压官方 jar，删掉 `org/sqlite/native/{Mac,Linux,Linux-Musl,FreeBSD,Linux-Android}` 后重新打包 |
| `material-icons-extended-trimmed-1.7.3.jar` | 只保留项目实际引用的图标（官方包 36 MB → 219 KB） | 见下 |

### 图标裁剪包

项目**不再**依赖 `compose.materialIconsExtended`（完整包 5000+ 图标、36 MB）。裁剪包只保留源码里
`Icons.Outlined/Filled/Rounded/AutoMirrored.*` 实际引用到的图标类（约 92 个）。

新增图标时：**先在代码里直接写** `Icons.Outlined.Xxx`，如果编译报 `Unresolved reference`，说明裁剪包里没有，
按下面步骤重新生成（PowerShell，JDK 17）：

```powershell
# 1) 从源码收集引用到的图标类名
$rx = [regex]'Icons\.(AutoMirrored\.)?(Outlined|Filled|Rounded|TwoTone|Default)\.([A-Za-z0-9_]+)'
$need = New-Object 'System.Collections.Generic.HashSet[string]'
Get-ChildItem desktop\src\main\kotlin -Recurse -Filter *.kt | ForEach-Object {
  foreach ($m in $rx.Matches((Get-Content $_.FullName -Raw))) {
    $auto = if ($m.Groups[1].Value) { "automirrored/" } else { "" }
    $style = $m.Groups[2].Value.ToLower(); if ($style -eq "default") { $style = "filled" }
    $null = $need.Add("androidx/compose/material/icons/$auto$style/$($m.Groups[3].Value)Kt.class")
  }
}
# 2) 从 Gradle 缓存里的官方 jar 中挑出这些 class（外加 META-INF/MANIFEST.MF 与 *.kotlin_module）
#    写成 desktop\libs\material-icons-extended-trimmed-<版本>.jar，并同步 build.gradle.kts 的文件名
```

> 说明：`material-icons-core`（几百 KB）仍由 `compose.material3` 传递引入，核心图标（Add / Close / Search …）
> 的 Filled 版本来自它，不在裁剪包范围内。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `desktop/` | 应用本体（Kotlin + Compose Multiplatform） |
| `desktop/native/` | 原生桥接层源码（`darkmode.cpp` → `darkmode.dll`） |
| `launcher/` | 自研启动器（C# 编译为无控制台窗口的 exe；固定读取 `app\YunX-Desktop.cfg`，改 exe 名或放在中文路径下都不会失效，同时负责启动闪屏与主窗口交叉淡入） |
| `installer/` | Inno Setup 脚本与中文语言包 |
| `docs/` | 文档（见 [README](../README.md#文档)） |

## 本地 macOS 开发运行

本分支支持 macOS 原版 Compose 界面。使用 JDK 17：

```sh
./run.sh :desktop:run    # JVM 开发运行；签名校验应使用下方的 .app 构建验证
./run.sh :desktop:test --tests '*MacWindowAccessTest' --tests '*MacHelperTest' --tests '*DbKeyStoreTest' --tests '*SecureAccountDaosTest'
# 打包需要受 jpackage 支持的发行版（例如 Eclipse Temurin 17），通过 JAVA_HOME 指定：
JAVA_HOME=/path/to/temurin-17/Contents/Home ./run.sh :desktop:createDistributable
open desktop/build/compose/binaries/main/app/YunX-Desktop.app
```

本工作区可用的 Temurin 路径为 `.tmp/jdk-17.0.21+9/Contents/Home`。
macOS 使用对应架构的 SQLite、JavaFX 和 JCEF 152 原生库，不编译 Windows 的 darkmode.dll。
Windows 保持 JCEF 132。macOS 的浏览器配置已由新版 Chromium 更新，不应再用旧版打开。

macOS 修复包含：
- JCEF 文档要求的三个 AWT 模块访问参数，防止登录页 `IllegalAccessError` 导致界面冻结。
- 从固定版本 sources jar 重编译 `CefClient`，移除 `onGotFocus` 中重复请求焦点的递归调用。
- `.app` 构建后规范化 Info.plist 并重新做本地签名，通过 `codesign --verify --deep --strict`；Chromium 使用实际主应用 bundle。
- 从同一 JCEF commit 重建 helper，在音频服务加载 Chromium 前设置 macOS daemon 状态，保留 sandbox 初始化代码。
  ARM64 签名 helper 随源码放在 `desktop/libs/jcef-helper-macos-arm64.zip`；重建或在 Intel Mac 构建时运行 `desktop/macos/build-helper.sh`（需要 CMake、Xcode 命令行工具，首次下载 CEF SDK 较大）。
  helper 在运行时验证签名，不注入动态库、不过滤日志、不跳过证书校验。

注意：上游 JCEF 的 `native/context.cpp` 原本就设置 `settings.no_sandbox = true`；本补丁未新增该设置，
但不能将此构建描述为启用了 Chromium sandbox。macOS 本地签名也不是 Developer ID 公证发行签名。

macOS 的“普通”档使用 IOPlatformUUID 派生密钥；“较为严格”档通过 Security.framework 的
SecItem API 将随机凭证主密钥保存在当前用户的 Keychain，`credential.key` 只保存随机项目引用。
钥匙串锁定、拒绝授权或项目丢失时不轮换主密钥、不覆盖引用文件。历史项目保留供旧数据库备份使用。
仅复制数据库与引用文件不足以在另一台 Mac 恢复账号；换机前应导出应用内的加密账号备份。
这些档位均不会加密数据库中的文件名、URL 等元数据，SQLCipher 整库加密仍未实现。

## GitHub browser sign-in

The GitHub account card uses GitHub App Device Flow in the system browser.
The fork-owned app, `yunx-read-only-selenicdove`, requests **Contents: read-only**
and mandatory **Metadata: read-only**. Install it with **Only select repositories**,
then sign in from YunX. Existing OAuth users must sign in again to migrate.
The app registration currently permits installation only on its owner's account.
Fork maintainers should register their own GitHub App, enable Device Flow, disable
webhooks, retain expiring user tokens, and replace `CLIENT_ID` and `INSTALL_URL`
in `GitHubDeviceAuth.kt`. No client secret or private key is bundled or needed
by the desktop device flow.

Tokens use the existing encrypted store. Expired tokens require signing in again;
refresh tokens are not retained. Manual PAT entry remains under Advanced.
The signed-in home lists repositories accessible to that credential, including
selected private repositories. Private downloads use GitHub API endpoints directly,
bypassing mirrors. Download credentials are loaded at request time, never stored
in task URLs or headers; OkHttp strips Authorization on cross-origin CDN redirects.
Response and commit-date caches are isolated by credential.

The OAuth HTTP client retains standard certificate verification, rejects redirects,
and cancels polling when the dialog closes. Focused checks:

```sh
./run.sh :desktop:test --tests '*GitHub*Test'
```

Service logos are bundled for offline use in both account layouts. Their official
website sources are listed in `desktop/src/main/resources/drive_logos/SOURCES.md`.
