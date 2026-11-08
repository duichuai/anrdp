# AnRDP 设计文档 — Android RDP 远程桌面客户端

> 版本：v2 设计稿（设计 + 实现记录）
> 状态：**已实现**（基础版本提交 `01a51aa`；2026-11-08 增加 Tailscale 集成，提交 `02a0d91` / `f7ebaed`）
> 关系：复用 `../anrdp`（v1）已验证的成果与踩坑结论，在 `anrdp2` 重新组织工程与方案
>
> 实现差异、当前模块/数据层细节以 `docs/technical-design.md` 为准；Tailscale 方案见 `docs/tailscale-integration-design.md`。

---

## 1. 目标与范围

### 1.1 产品目标

开发一款 Android 应用，实现对 Windows 远程桌面（RDP 协议）的连接与控制：

| 编号 | 需求 | 说明 |
| --- | --- | --- |
| R1 | 输入地址/账号/密码连接 | 支持 `主机:端口`、用户名、密码、域（可选），默认端口 3389 |
| R2 | 保存连接列表 | 连接的增、删、改、查；点击即连；密码可留空（连接时询问） |
| R3 | 键盘输入 | 软键盘打字、特殊键（Esc/Tab/Ctrl/Alt/Del/方向键/F1-F12 等）、物理键盘透传 |
| R4（隐含） | 触摸控制 | 移动光标、左/右键、滚轮、缩放平移 —— 组成"控制"的完整闭环 |

### 1.2 本次交付范围

v2 设计稿规划架构、模块划分、接口约定、输入映射策略、构建方案与风险对策；后续已按本稿实现应用与 AAR 产物，实际落地情况见 `docs/technical-design.md`。

### 1.3 非目标（本期不做）

- 音频重定向、剪贴板双向同步、打印机重定向、摄像头重定向、多显示器
- 多协议（VNC/SSH）、会话录制、远程唤醒
- 仅 arm64-v8a（构建机内存受限），不产出 armeabi/x86 APK

---

## 2. 关键技术决策

### 2.1 协议层：复用 FreeRDP 官方 Android 核心库 `freeRDPCore`

| 决策 | 内容 | 理由 |
| --- | --- | --- |
| 协议实现 | **FreeRDP 3.33.0**（Apache-2.0） | 成熟、活跃、Android 官方客户端即基于它 |
| 复用粒度 | 直接复用上游模块 `client/Android/Studio/freeRDPCore` | 内含 CMake 原生构建 + 成熟 JNI 绑定 + 会话 Activity/输入管理，避免自研 RDP 栈 |
| 不引入 | 不自行实现 RDP 协议、不自行写 JNI | 自研成本极高且无收益 |

`freeRDPCore` 提供的核心能力（源码位置 `deps/freerdp-3.33.0/client/Android/Studio/freeRDPCore/`）：

- **JNI 门面** `com.freerdp.freerdpcore.services.LibFreeRDP`
- **会话 Activity** `presentation.SessionActivity`（渲染、触摸、键盘、证书弹窗全内置）
- **输入组件** `SessionInputManager`、`ExtendedKeyboardView`、`TouchPointerView`、`KeyboardMapper`
- **会话态** `application.GlobalApp` / `SessionState`

### 2.2 集成方式：方案 A（推荐）——预编译 AAR

v1 采用"把 `freeRDPCore` 作为源码库模块 include 进主工程"，在 `processDebugResources`（aapt2 link）阶段遇到**库属性全部 not found** 的阻塞（详见 §7），未解决。

v2 推荐改为**两段式构建**：

```
① 用上游自带 Studio 工程（deps/.../Studio/，其自有 settings.gradle / 根 build.gradle / rootProject.ext）
   → ./gradlew :freeRDPCore:assembleRelease  → 产出 freeRDPCore-release.aar（含 .so 与原资源）
② anrdp2 应用工程以 AAR 形式消费 → 只保留 :app 一个模块
```

优点：

- **彻底绕开库资源合并/命名空间解析**：AAR 内的资源以预编译形式参与链接，不再有"非命名空间库模块 + AGP 版本组合"导致的 aapt2 属性解析失败
- **上游模块用其自己的 `rootProject.ext`**（`compileApi`/`toolsVersion`/`ndkVersion`/`abiFilters`/`cmakeArguments`），不必在主工程重复声明而对不齐
- 应用工程干净：只有 `:app`，`settings.gradle` 无 `projectDir` 外挂

**方案 B（备选，仅当 AAR 方案受阻）**：仍以源码模块 include，但严格复刻上游 `Studio/gradle.properties` 与 AGP 版本矩阵，并接受 §7 的排查成本。

### 2.3 应用层：Kotlin + Room + ViewBinding

- Kotlin（与 v1 一致，代码可复用）
- Room 持久化连接列表
- ViewBinding（避免 Kotlin 合成/反射）
- 沿用 v1 的极简依赖集，避免传递依赖拖入高版本 AGP 要求

---

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│  UI 层 (app)                                              │
│   MainActivity            连接列表（RecyclerView + FAB）   │
│   ConnectionEditActivity  新建/编辑表单                    │
│   （会话页复用 freeRDPCore 的 SessionActivity）            │
├──────────────────────────────────────────────────────────┤
│  应用逻辑层 (app)                                          │
│   RdpConnector            连接配置 → 启动会话              │
│   PasswordPrompt          未存密码时询问                   │
├──────────────────────────────────────────────────────────┤
│  数据层 (app)                                              │
│   Room: ConnectionEntity / ConnectionDao / AppDatabase    │
├──────────────────────────────────────────────────────────┤
│  RDP 引擎层 (freeRDPCore，预编译 AAR)                      │
│   SessionActivity ─ SessionInputManager ─ SessionView     │
│   ExtendedKeyboardView / TouchPointerView / KeyboardMapper │
│   LibFreeRDP (JNI)  ──►  libfreerdp-android.so (NDK/CMake) │
└──────────────────────────────────────────────────────────┘
```

设计原则：**应用层只负责"连接配置的存取"与"把配置交给引擎"**，渲染与输入交互全部下沉到 `freeRDPCore`，避免重复造轮子与行为分叉。

---

## 4. 模块设计

### 4.1 数据层

**实体** `ConnectionEntity`（表 `connections`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 主键，自增 |
| name | String | 显示名，空则回退为 host |
| host | String | 主机地址 |
| port | Int | 默认 3389 |
| username | String | 用户名 |
| password | String | **空串 = 不保存密码，连接时询问** |
| domain | String | 域，可选 |
| createdAt | Long | 创建时间戳 |
| lastUsedAt | Long | 最近使用时间戳，列表按此倒序 |

**DAO** `ConnectionDao`：`observeAll(): Flow<List<...>>`（`ORDER BY lastUsedAt DESC, createdAt DESC`）、`getById`、`insert`、`update`、`delete`、`touch(id, ts)`。

**数据库** `AppDatabase`：单例、库名 `anrdp.db`、`exportSchema=false`、`version=1`。

> 安全说明：本期密码以明文存于 Room。若后续需要，可接入 `androidx.security.crypto` 加密或改为"仅连接时输入、不落库"。

### 4.2 连接列表页 `MainActivity`

- `RecyclerView` + `ListAdapter` + `DiffUtil` 展示连接（名称 / `host:port` / 用户名）
- `Flow` 驱动，空列表显示引导文案
- **点击条目** → `touch()` 更新最近使用 → 有密码直接连；无密码弹密码框（**不落库**）
- **长按条目** → 弹菜单：编辑 / 删除（删除前二次确认）
- **FAB** → 进入新建

### 4.3 编辑页 `ConnectionEditActivity`

- 表单：名称、主机地址、端口、用户名、密码、域名
- 校验：host 非空；端口 `1..65535`
- 通过 `EXTRA_CONNECTION_ID` 区分新建/编辑；保存后 `finish()` 回列表

### 4.4 会话页（复用而非自研）

**关键决策：不自写渲染 View，直接使用 `freeRDPCore` 的 `SessionActivity`。**

它已内置本需求全部交互能力：

| 能力 | 组件 |
| --- | --- |
| 画面渲染 | `SessionView`（`LibFreeRDP.EventListener.OnGraphicsUpdate` 回帧 → Bitmap → Surface） |
| 单指移动/左键、长按右键、双指滚轮 | `SessionInputManager`（`onSessionViewLeftTouch/MiddleTouch/RightTouch/Scroll/HScroll`） |
| 触摸指针模式、缩放平移 | `TouchPointerView`、`PinchZoomListener` |
| 特殊键工具栏（可收起） | `FloatingToolbar` + `ExtendedKeyboardView`（Esc/Tab/Ctrl/Alt/Win/方向/F1-F12/Del 等，含 `onKey`/`onKeyLock` 锁定态） |
| 软键盘 | `SessionInputManager` 经 IME → `sendUnicodeKeyEvent` / `sendKeyEvent` |
| 物理键盘 | `dispatchKeyEvent` → `KeyboardMapper`（Android keyCode → RDP VK/scancode）→ `sendKeyEvent` |
| 证书校验弹窗、断线提示 | `SessionDialogs`、`LibFreeRDP` 回调 |
| 剪贴板/多显示器 | 引擎内置（超出本期范围但可用） |

**这意味着 R3「键盘输入」在架构上是"零自研"**：只要把连接正确交给 `SessionActivity`，软键盘、特殊键工具栏、物理键盘三条路径即全部具备。

### 4.5 连接发起 `RdpConnector`（app 层唯一与引擎的接口点）

把 `ConnectionEntity` 转换为 `freerdp://` URI，交给 `SessionActivity`。URI 由 `LibFreeRDP.setConnectionInfo(Context, long, Uri)` 解析为 xfreerdp 参数：

```
freerdp://user@ip:port/connect?p=password&d=domain
```

映射规则（来自 `LibFreeRDP` 源码）：

| URI 元素 | 生成参数 |
| --- | --- |
| `getHost()` + `getPort()` | `/v:host:port` |
| `getUserInfo()` | `/u:user` |
| `?key=` | `/key` |
| `?key=-` / `?key=+` | `-key` / `+key` |
| `?key=value` | `/key:value`（`?p=xxx` → `/p:xxx`，`?d=xxx` → `/d:xxx`） |
| 固定附加 | `/gdi:sw`（软件 GDI，当前仅支持） |

要点：

- URI 的 authority 中用户名需 `Uri.encode`；端口为 3389 时可省略；端口非 `1..65535` 直接拒绝
- 密码仅在有值时拼入 query（避免 `/p:` 空值）
- **备选路径**：若需更强控制（自定义分辨率、安全选项、多会话），可改为 app 侧直接调用
  `LibFreeRDP.newInstance` → `setConnectionInfo(ctx, inst, ManualBookmark)` → `connect(inst)`，
  再启动 `SessionActivity` 并传入 `PARAM_INSTANCE`。本期**默认走 URI 方案**（简单、已由 v1 验证）。

### 4.6 应用入口

`AnRdpApp : GlobalApp`（`GlobalApp` 位于 `freeRDPCore.application`），复用引擎的全局初始化（事件监听注册、会话表）。

---

## 5. 输入方案详解

| 输入通道 | 路径 | 归属 |
| --- | --- | --- |
| 软键盘（打字、中文 IME） | IME → `InputConnection` → `sendUnicodeKeyEvent`（字符）/ `sendKeyEvent`（控制键） | freeRDPCore |
| 特殊键（Esc/Tab/Ctrl/Alt/Win/方向/F1-F12/Del/Ctrl+Alt+Del/Home 等） | `ExtendedKeyboardView` 按键 → `KeyboardMapper` → `sendKeyEvent`；修饰键支持 ON/LOCKED/OFF 锁定态 | freeRDPCore |
| 物理键盘（蓝牙/USB） | `Activity.dispatchKeyEvent` → `KeyboardMapper` → `sendKeyEvent` | freeRDPCore |
| 触摸（光标/点击/滚轮/缩放） | `SessionInputManager` 手势识别 → `sendCursorEvent(long,x,y,flags)` | freeRDPCore |

`KeyboardMapper` 关键常量（供需要扩展映射时参考）：`KEYSTATE_ON/LOCKED/OFF`、`KEYBOARD_TYPE_FUNCTIONKEYS/NUMPAD`、Windows `VK_*` 虚拟键码表。

**结论**：需求 R3 无需在 app 层实现任何键盘代码，只需保证会话页正确拉起。若后续要自定义"额外快捷键栏"，可在 URI 之外通过 `ManualBookmark` 路径叠加。

---

## 6. 工程结构

```
anrdp/
├── app/                                     # 唯一应用模块
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/anrdp/
│       │   ├── AnRdpApp.kt                   # : GlobalApp()
│       │   ├── MainActivity.kt               # 连接列表
│       │   ├── RdpConnector.kt               # Entity → freerdp:// URI
│       │   ├── data/ConnectionEntity.kt|ConnectionDao.kt|AppDatabase.kt
│       │   ├── data/TailscaleConfigEntity.kt|TailscaleConfigDao.kt   # 2026-11-08 新增
│       │   ├── tailscale/TailscaleManager.kt|TailscaleConfigEditActivity.kt
│       │   └── edit/ConnectionEditActivity.kt
│       └── res/{layout,values,mipmap-*}
├── app/libs/freeRDPCore-release.aar         # 由 deps 上游工程预编译产出（方案 A）
├── app/libs/tsnet.aar                       # tsnet（Tailscale 用户态节点，gomobile 产出）
├── deps/
│   ├── freerdp-3.33.0/                       # 上游源码（含 Studio 工程）
│   │   ├── client/Android/Studio/            # ① 用来编 AAR
│   │   └── client/Android/cmake/*.cmake      # 已 patch 指向本地 mirror
│   └── mirror/{openssl-4.0.3,cJSON-1.7.19,uriparser-1.0.2}.tar.gz
├── docs/android-rdp-design.md                # 本文档
├── gradle/wrapper/                           # Gradle 9.6.1（官方源 + sha256 校验）
├── settings.gradle / build.gradle / gradle.properties
└── local.properties                          # sdk.dir=...（不入库）
```

---

## 7. 构建方案

### 7.1 工具链（沿用 v1 已装好的，位于 `/tmp/anrdp-tools`）

| 项 | 版本 |
| --- | --- |
| JDK | 17（v1 记录）/ 本机现有 21 |
| Android SDK platform | android-37.0 |
| build-tools | 37.0.0 |
| NDK | 29.0.13113456 |
| CMake | 4.1.2 |
| Gradle | 9.6.1（腾讯镜像 + 官方 sha256 校验） |
| AGP | 9.2.1 |
| Kotlin (KGP) | 2.4.21 |
| KSP | 2.3.12 |
| Room | 2.8.5 |

> `/tmp` 会在重启后清空，工具链与构建工作区需可重建（记录于工程文档）。

### 7.2 应用模块配置要点

- `compileSdk=37`，`minSdk=29`，`targetSdk=37`，`buildToolsVersion=37.0.0`
- `ndk.abiFilters = ['arm64-v8a']`（构建机内存有限，只出 arm64）
- AndroidManifest：`INTERNET`、`ACCESS_NETWORK_STATE` 权限；`application` 使用 `AnRdpApp`
- 密码框 `windowSoftInputMode=adjustResize`

### 7.3 原生依赖（CMake）

`freeRDPCore` 的原生构建在 `src/main/cpp/CMakeLists.txt`，依赖三个外部库，**必须走本地镜像**（GitHub 不通）：

- `ExternalOpenSSL.cmake` → `deps/mirror/openssl-4.0.3.tar.gz`（TLS/NLA 必需）
- `ExternalCJSON.cmake` → `deps/mirror/cJSON-1.7.19.tar.gz`
- `ExternalUriparser.cmake` → `deps/mirror/uriparser-1.0.2.tar.gz`

三个 `.cmake` 已 patch 为"本地 mirror 优先"。CMake 参数（精简，关掉多媒体依赖）：

```
-DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_OPUS=OFF -DWITH_WEBP=OFF
-DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_CJSON=ON -DWITH_OPENSSL=ON
```

### 7.4 构建内存（构建机约 3.9GB）

```
org.gradle.jvmargs=-Xmx1408m -XX:MaxMetaspaceSize=512m
org.gradle.workers.max=1
org.gradle.parallel=false
kotlin.daemon.jvmargs=-Xmx1024m
```
另需 4G swap 兜底（已在 v1 配置 `swapon /swapfile`）。

### 7.5 构建命令（方案 A）

```bash
# ① 预编译 freeRDPCore AAR（用上游自有 Studio 工程，原生库同时被编出）
cd deps/freerdp-3.33.0/client/Android/Studio
ANDROID_HOME=/path/to/android-sdk ./gradlew :freeRDPCore:assembleRelease
# 产物 → freeRDPCore/build/outputs/aar/freeRDPCore-release.aar → 复制到 app/libs/

# ② 编应用
cd anrdp   # 若仓库位于共享挂载盘（不支持符号链接），先 rsync 到本地磁盘再编
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleDebug
# 产物 → app/build/outputs/apk/debug/app-debug.apk
```

> **重要**：共享挂载盘（CIFS/vboxsf 等）**不支持符号链接**，直接在上面构建会失败。建议用 `rsync -a --delete`（排除 `.gradle/build/.cxx/local.properties`）同步到 `/tmp` 工作区后再编；仓库自带的 `scripts/build-app.sh` 已内置该流程。

### 7.6 仓库配置（v1 血泪结论，勿回退）

- **只能用官方 `google()` + `mavenCentral()`**：阿里云镜像的 Gradle Module Metadata 不完整，会导致 AAR 的 `android-compiled-dependencies-resources` 变体解析为空 → aapt2 链接时库属性全报 not found
- 可选本地 Maven 仓库通过环境变量 `LOCAL_MAVEN_REPO` 指定，且**必须排在官方仓库之后**（本地仓库可能含"有 pom 无 jar"的残缺条目）
- `gradle.properties` **绝不能有** `android.uniquePackageNames=false`（上游 `freeRDPCore` 自带，必须删除）
- `androidx.core/core-ktx` 锁 `1.18.0`（1.19+ 要求 AGP 9.1）
- 需 `android.suppressUnsupportedCompileSdk=37`（保留以抑制警告；AGP 9.2.1 已支持 API 37）

---

## 8. 主要风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| **aapt2 link 库属性 not found**（v1 未解决的阻塞） | 无法出 APK | **方案 A（预编译 AAR）从根因上规避**；不用源码库模块 + 非命名空间资源的组合 |
| 上游 `freeRDPCore` 依赖 `androidx.core:1.19.0`、`sqlcipher`、`preference` | 可能要求 AGP 9.1 | 若编 AAR 时上游工程自带 wrapper 版本足够则无需干预；应用侧再约束 `androidx.core:1.18.0` |
| NDK 编译 OOM（3.9GB 内存） | 构建中断 | 仅 `arm64-v8a`、`-j1`、Gradle 单 worker、4G swap |
| `github.com` 不通 | 原生依赖拉取失败 | 已 patch 为本地 `deps/mirror/*.tar.gz` |
| 共享挂载盘不支持符号链接 | 构建异常 | 构建工作区 rsync 到 `/tmp` |
| `<100MB` 下载源（gradle 分发）不通 | wrapper 初始化失败 | 使用腾讯镜像分发 URL + 官方 sha256 校验 |
| 无真机/无 RDP 服务器 | 无法端到端验证连接 | 编译通过 + APK 内含 `lib/arm64-v8a/*.so` 为底线；连接实测由用户真机完成 |

---

## 9. v1 → v2 差异对照

| 维度 | v1（`../anrdp`） | v2（`anrdp2`） |
| --- | --- | --- |
| 引擎集成 | `freeRDPCore` 作为源码库模块 include | **预编译 AAR 消费**（推荐） |
| 工程模块 | `:app` + `:freeRDPCore` | 仅 `:app` |
| 根 `rootProject.ext` | 主工程重复声明，与上游对齐成本高 | 由上游 Studio 工程自持，应用侧不掺和 |
| 应用层代码 | 已实现（列表/编辑/数据/连接器，约 340 行 Kotlin） | **直接复用**（见 §10） |
| 构建状态 | 卡在 aapt2 link，未出 APK | 目标：产出可安装 APK |
| 交付 | 无 APK | 设计方案（本次） |

---

## 10. 复用清单（来自 v1，可直接迁移）

| 文件 | 作用 | 复用度 |
| --- | --- | --- |
| `AnRdpApp.kt` | 继承 `GlobalApp` | 直接用 |
| `data/ConnectionEntity.kt` | Room 实体 | 直接用 |
| `data/ConnectionDao.kt` | DAO | 直接用 |
| `data/AppDatabase.kt` | 数据库单例 | 直接用 |
| `MainActivity.kt` | 列表 + 长按菜单 + 密码询问 | 直接用 |
| `edit/ConnectionEditActivity.kt` | 表单 + 校验 | 直接用 |
| `RdpConnector.kt` | Entity → `freerdp://` URI | 直接用（改造为 AAR 依赖后 import 不变） |
| `res/`（layouts/values/mipmap） | 界面资源 | 直接用 |
| `deps/freerdp-3.33.0` + `deps/mirror` + 三处 cmake patch | 引擎源码与原生依赖 | 直接用 |
| `gradle.properties` 中"勿回退"结论 | 仓库/版本约束 | 直接沿用 |

因 v1 应用层代码与引擎的唯一耦合点是"`freerdp://` URI + `SessionActivity` 类引用"，改为 AAR 依赖后 **应用层 Kotlin 代码零改动**。

---

## 11. 里程碑（建议，不含时间估算）

1. **工程骨架**：`anrdp2` 建立 `:app`、迁移应用层 Kotlin/资源、配置 `gradle.properties`/wrapper
2. **引擎前置**：用上游 Studio 工程产出 `freeRDPCore-release.aar`（含 `lib/arm64-v8a/*.so`），放入 `libs/`
3. **应用构建**：`:app` 消费 AAR，`assembleDebug` 通过
4. **产出与验证**：`app-debug.apk` 复制到 `anrdp2/`；检查 APK 内含 `.so` + dex
5. **（用户侧）真机实测**：连接真实 RDP 服务器，验证列表/编辑/键盘/触摸

---

## 12. 附录：`LibFreeRDP` 关键 API（供实现期参考）

```
newInstance(Context) -> long                        // 创建会话实例
setConnectionInfo(Context, long, BookmarkBase)      // 用书签配置（可精细控制）
setConnectionInfo(Context, long, Uri)               // 用 freerdp:// 配置（本期默认）
connect(long) / disconnect(long) / cancelConnection(long)
sendCursorEvent(long, x, y, flags)                  // 光标/点击/滚轮
sendKeyEvent(long, keycode, down)                   // 控制键（scancode）
sendUnicodeKeyEvent(long, keycode, down)            // 字符输入
isUnicodeInputSupported(long)
sendClipboardData(long, String) / sendClipboardImageData(...)
sendMonitorLayout(long, width, height)
setEventListener(EventListener)                     // OnGraphicsUpdate/OnDisconnect/OnVerifyCertificate...
getVersion()
```
