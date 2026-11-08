# AnRDP 构建与开发说明

> 本文件保存项目构建进度、环境布局与"勿回退"约束，供维护者参考。
> 详细设计见 `docs/android-rdp-design.md`（§7 构建、§11 里程碑）；当前实现与技术债见 `docs/technical-design.md`；Tailscale 方案见 `docs/tailscale-integration-design.md`。
> 文中路径以**工作区根**（仓库的上一级）为基准，即 `anrdp/...`、`tools/...`、`tsnet-android/...`。
> 最近更新：2026-11-08

## 1. 项目概况

- 目标：Android RDP 客户端（AnRDP），FreeRDP 3.33.0 引擎 + Kotlin/Room 应用层
- 组网：可选 Tailscale（tsnet）用户态节点，RDP 经本地 SOCKS5 走 tailnet
- 仓库：`anrdp/`（git 根），引擎与 tsnet 均以**预编译 AAR** 消费，工程仅 `:app` 一个模块
- 设计文档：`anrdp/docs/android-rdp-design.md`、`anrdp/docs/technical-design.md`、`anrdp/docs/tailscale-integration-design.md`

## 2. 进度（对照设计文档 §11 里程碑）

| 里程碑 | 状态 |
| --- | --- |
| 1. 工程骨架 | ✅ 已提交 |
| 2. freeRDPCore AAR | ✅ 已提交（`anrdp/app/libs/freeRDPCore-release.aar`，4.9MB） |
| 3. 应用构建 assembleDebug | ✅ 2026-11-07 构建成功 |
| 4. APK 校验与产出 | ✅ 产物 `anrdp/app-debug.apk`（29MB），含 dex 与全部 arm64-v8a 原生库 |
| 5. 真机实测 | 🟡 进行中：手机直连 VirtualBox VRDE（Linux Mint），画面/鼠标/英文键盘输入正常；中文输入靠远端输入法上屏 |
| 6. Tailscale（tsnet）集成 | 🟡 已实现（2026-11-08，未提交）：tsnet AAR + 配置页 + 连接绑定 + SOCKS5 联动；待真机验证 tailnet 连接 |

> 2026-11-07 修复：远程桌面画面过小（FreeRDP 默认 1024×768）。`RdpConnector` 现按连接配置的分辨率传 `size=WxH`；新建/编辑页可选 自适应/1280×720/1600×900/1920×1080/2560×1440/1024×768，DB v2 新增 `resolution` 字段（带 1→2 迁移）。
>
> 2026-11-08 现状：数据库 v4（连接表新增 `tailscaleConfigId`，新增 `tailscale_configs` 表）；release 产物约 46MB（含 `libgojni.so` 30.5MB）。


### 2.1 版本决策（与设计文档 §7.1 的偏差，已修正）

设计文档原定 AGP 8.13.2 + Gradle 8.14.3，**实际不可用**，已升级：

| 项 | 设计文档 | 实际 | 原因 |
| --- | --- | --- | --- |
| AGP | 8.13.2 | **9.2.1** | AGP 8.13.2 硬限制 compileSdk 上限 36，不注册 `android-37` target；而 freeRDPCore AAR metadata 要求 compileSdk≥37 |
| Gradle | 8.14.3 | **9.6.1** | AGP 9.2.1 的最低要求（与上游 Studio 工程一致） |
| Kotlin 插件 | `org.jetbrains.kotlin.android` | **移除** | AGP 9.0+ 已内置 Kotlin 支持，再 apply 该插件会直接报错 |
| KSP | 2.3.12 | 2.3.12 | 不变，Room 注解处理仍需 |

> 注：设计文档 §8 提到的「AGP 9.x aapt2 库属性 not found」是 v1 源码库模块时代的问题；
> v2 用预编译 AAR，资源已预编译，aapt2 链接阶段不受影响，实测通过。

### 2.2 许可与权限（2026-11-08）

- 引擎 AAR 为混合许可：FreeRDP 核心与 C JNI 是 Apache-2.0，**freeRDPCore 的 Java/Kotlin（50 个文件）是 MPL-2.0**；OpenSSL 4.0.x、cJSON（MIT）、uriparser/SQLCipher（BSD-3）均宽松，无 GPL/LGPL。公开发布需补 LICENSE / THIRD_PARTY_NOTICES，并按 MPL §3.2 提供 AAR 源码获取方式（上游 tag + `scripts/build-freerdpcore-aar.sh` 的补丁清单）。
- 应用 manifest 用 `tools:node="remove"` 移除引擎合并进来的未使用权限：CAMERA、RECORD_AUDIO、READ/WRITE_EXTERNAL_STORAGE、POST_NOTIFICATIONS；保留 INTERNET、ACCESS_NETWORK_STATE、MODIFY_AUDIO_SETTINGS、ACCESS_WIFI_STATE。`build-app.sh` 构建后会校验权限清单。
- 引擎/依赖版本已参数化：`build-freerdpcore-aar.sh` 默认 `FREERDP_VERSION=3.33.0`、`OPENSSL_VERSION=4.0.3`（可用同名环境变量覆盖）；缺源码时自动下载，并把版本与镜像实际 sha256 写入 `cmake/DepVersions.cmake`。**2026-11-08 已重编：仓库内 AAR 为 FreeRDP 3.33.0 + OpenSSL 4.0.3 的产物。**

### 2.3 Tailscale（tsnet）集成（2026-11-08，未提交）

- 原生库：`app/libs/tsnet.aar`（15MB，含 `lib/arm64-v8a/libgojni.so`），由工作区根目录 `tsnet-android/`（Go module，不纳入 anrdp 仓库）用 `gomobile bind -target=android/arm64 -androidapi=21` 产出；`tsnet-sources.jar` 一并放入 `app/libs/`。
- 版本：`tailscale.com` **v1.104.0**、Go **1.27.1**、gomobile（`golang.org/x/mobile`）；AAR 的 `minSdkVersion=21`，应用 `minSdk=29`。
- `app/libs/tsnet-sources.jar` 当前是 18:08 的早期产物，尚未包含后续新增的 `setNetworkInfo`；重编 AAR 后需从 `tsnet-android` 重新复制（AAR 本体已含该方法）。
- Go wrapper：`tsnetandroid.Server` 暴露 `NewServer` / `Start` / `Close` / `StartSOCKS5` / `StopSOCKS5` / `Status` / `TailnetIP`；另有 `Tsnetandroid.SetNetworkInfo` 注入 Android 活动网络信息。
- 应用层：
  - `data/TailscaleConfigEntity` + `TailscaleConfigDao`（表 `tailscale_configs`，含 `stateDir`）；
  - `tailscale/TailscaleManager`（所有 JNI 调用切到 `Dispatchers.IO` + `Mutex`，启动前注入 `ConnectivityManager` 网络信息，失败时清理并回传 `tsnet.log` 尾部）；
  - `tailscale/TailscaleConfigEditActivity`（名称 / Headscale URL / pre-auth key / 主机名）；
  - `ConnectionEditActivity` 增加 Tailscale 配置下拉框，`ConnectionEntity.tailscaleConfigId` 绑定（0 = 直连）；
  - `RdpConnector.connect` 改为挂起函数，绑定配置时启动 tsnet、轮询 `status` 最长 60 秒，再以 `proxy=socks5://host:port` 参数交给 FreeRDP。
- 数据库升到 **v4**：`MIGRATION_2_3` 建 `tailscale_configs`，`MIGRATION_3_4` 给 `connections` 加 `tailscaleConfigId INTEGER NOT NULL DEFAULT 0`。
- 已知问题与约束见 `docs/technical-design.md` §9；方案细节见 `docs/tailscale-integration-design.md`。

## 3. 持久化布局（重启不丢）

| 内容 | 位置 |
| --- | --- |
| Android SDK（cmdline-tools 19.0 / platform 37.0 / build-tools 37.0.0） | `tools/android-sdk/`（工作区内） |
| `platforms/android-37` 目录 | `tools/android-sdk/platforms/android-37`（由 `android-37.0` 复制并把元数据 ApiLevel 改为 37；AGP 按目录名 `android-37` 查找 target） |
| Gradle 依赖缓存 | `~/.gradle`（放本地磁盘；共享挂载盘的文件锁会影响 Gradle/SQLite 缓存） |
| 引擎源码 + 原生依赖镜像 | `anrdp/deps/`（不入 git；仅重编 AAR 时需要，脚本可自动下载） |
| tsnet Go wrapper 源码 | 工作区根 `tsnet-android/`（不入 anrdp 仓库；仅重编 `app/libs/tsnet.aar` 时需要） |
| 预编译 AAR | `anrdp/app/libs/freeRDPCore-release.aar`、`anrdp/app/libs/tsnet.aar`、`tsnet-sources.jar` |
| 可选本地 Maven 补充仓库 | 环境变量 `LOCAL_MAVEN_REPO` 指定的目录（缺省不使用） |
| 构建工作区 | `/tmp/anrdp2-app-build`、`/tmp/anrdp2-build`（tmpfs，重启即失，rsync 可重建，属正常） |

## 4. 构建命令

```bash
# 应用 APK（日常构建；需设置 ANDROID_HOME 或 local.properties 的 sdk.dir）
scripts/build-app.sh          # debug → app-debug.apk
scripts/build-app.sh release  # release（R8 + 资源压缩，固定 release 密钥签名）→ app-release.apk

# 重编引擎 AAR（仅当引擎要升级时才需要）
scripts/build-freerdpcore-aar.sh

# 重编 tsnet AAR（仅当 tailscale 版本/Go wrapper 变更时才需要；无仓库内脚本）
cd ../tsnet-android
gomobile bind -target=android/arm64 -androidapi=21 -o ../anrdp/app/libs/tsnet.aar .
```

## 5. 勿回退约束（踩坑结论）

- 仓库**只用**官方 `google()` + `mavenCentral()`；阿里云镜像 GMM 不完整 → aapt2 链接库属性全 not found
- 若通过 `LOCAL_MAVEN_REPO` 启用本地补充仓库，**必须排在官方仓库之后**（本地仓库可能含"有 pom 无 jar"的残缺条目）
- 根工程 `gradle.properties` **严禁** `android.uniquePackageNames=false`（AAR 资源包名解析会被打乱）
- `androidx.core/core-ktx` 锁 `1.18.0`（1.19+ 要求 AGP 9.1）
- 需 `android.suppressUnsupportedCompileSdk=37,37.0`（保留以抑制警告；AGP 9.2.1 已支持 API 37）
- 根工程固定 **AGP 9.2.1 + Gradle 9.6.1**（wrapper 指向腾讯镜像，并校验官方 sha256）；上游 Studio 工程（Gradle 9.6.1 + AGP 9.2.1）仅用于编 AAR，两者互不干扰
- `org.jetbrains.kotlin.android` 插件**不得 apply**（AGP 9.0+ 内置 Kotlin，apply 会报错）
- SDK 平台需同时存在 `android-37`（ApiLevel=37）与 `android-37.0` 两个目录；前者供 AGP 按 compileSdk=37 定位，后者是 sdkmanager 原始安装
- 只出 `arm64-v8a`；若仓库位于共享挂载盘（不支持符号链接），先 rsync 到 `/tmp` 再编
- gomobile/JNI 调用**不得在主线程执行**：`TailscaleManager` 已统一切到 `Dispatchers.IO` 并用 `Mutex` 串行化
- Android 应用 UID 无权调用 `net.Interfaces()`；启动 tsnet 前必须用 `ConnectivityManager` 注入接口/地址/网关（`Tsnetandroid.setNetworkInfo`），否则 netmon 会在 magicsock 深处 panic
- Go 侧 `Start()` 已 `recover` panic 并转为 Java 异常；仍可能遇到 native SIGABRT，排查时读取 `filesDir/tailscale-state-<id>/tsnet.log`
- tsnet AAR 自带 `proguard.txt`（`-keep class go.**` / `-keep class tsnetandroid.**`），release R8 依赖该规则，勿删除
- `tsnet.aar` 的 `minSdkVersion=21`，低于应用 `minSdk=29`；`libgojni.so` 仅 arm64-v8a

## 6. 环境与工具链

- JDK：应用构建需 17+；重编 AAR 需 21
- 引擎工具链：NDK 29.0.13113456 + CMake 4.1.2，**须装在本地磁盘**（共享挂载盘不支持符号链接，SDK 放上去会构建失败）
- tsnet 工具链：Go 1.27.1 + gomobile；`gomobile bind` 需要 Android SDK/NDK 与 `androidapi 21`。Go 模块下载建议走代理（实测 `socks5://192.168.56.1:1080`，可设置 `GOPROXY` / `HTTPS_PROXY`）
- 2026-11-08 已重编引擎：FreeRDP **3.33.0** + OpenSSL **4.0.3**（若 openssl.org 不可达，脚本会回退到 GitHub codeload；网络受限时可自行配置代理）
- 构建机内存建议 8GB 以上（NDK 编译 + R8 压缩），内存紧张时仅出 arm64-v8a 并限制并发
- 行尾统一由 `.gitattributes` 约定（`gradlew`/`*.sh` 为 LF，`*.bat` 为 CRLF）；若在共享挂载盘上出现整树差异，先用 `git diff --ignore-cr-at-eol` 确认是否为挂载噪声
- 外部下载源：`dl.google.com` 与 Maven 官方源需可访问；GitHub 不通时可走代理或使用 `deps/mirror/` 下的本地包
- 实测环境：VirtualBox 中的 Linux 虚拟机，手机 AnRDP 直连其 VRDE 端口
  - VRDE **默认单连接**：PC 客户端占着时 AnRDP 再连会被拒（不是 app 的 bug）
  - 开多连接：`VBoxManage modifyvm "VM名" --vrde-multi-con on`（VM 需关机后执行）；多连接共享同一桌面与键盘焦点

## 7. 输入/键盘机制（2026-11-07 反编译 AAR 确认）

- `SessionView.onCreateInputConnection` 返回 `new BaseInputConnection(view, false)`，`EditorInfo.inputType = 0`
  - **英文/符号键**：走 `KeyEvent` → `SessionActivity.onKeyDown` → `KeyboardMapper` → 扫描码，**正常**
  - **手机侧中文输入法**：上屏走 `commitText`，`fullEditor=false` 的 `BaseInputConnection` 无 Editable → **文字被直接丢弃**，这就是"软键盘弹了但远端无反应"
- 可用替代路径（均已验证/可用）：
  1. 手机切英文键盘打拼音，由**远端**的输入法（fcitx/ibus）上屏中文 —— 当前采用的方案
  2. `ExtendedKeyboardView`（浮动工具条里的自绘 PC 键盘）发真实扫描码
  3. 若要直接用手机中文输入法，需 app 侧加"文本注入"：`GlobalApp.getSessions()` → `SessionState.getInstance()` → `LibFreeRDP.sendUnicodeKeyEvent(instance, ch, down)` 逐字符发送（这些 API 均为 public，AAR 无需改动）
- `SessionInputManager.toggleKeyboard()` = 显示 `ExtendedKeyboardView` + `showSoftInput(sessionView, 1)` 两件事一起做
- 浮动工具条静止时只有一个拖拽手柄（`floating_toolbar_handle`），点它才展开出触摸指针 / 键盘两个按钮
