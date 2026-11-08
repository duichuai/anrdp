# AnRDP 技术设计文档

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.2 |
| 生成日期 | 2026-11-07 |
| 更新日期 | 2026-11-08 |
| 状态 | 已实现（基础版本对应提交 `01a51aa`；Tailscale 集成为 `02a0d91` / `f7ebaed`） |
| 关联文档 | [android-rdp-design.md](./android-rdp-design.md)（方案设计 / 决策记录）、[tailscale-integration-design.md](./tailscale-integration-design.md)（Tailscale 方案） |
| 代码位置 | `anrdp/`（Gradle 根工程，唯一模块 `:app`）；tsnet wrapper 在 `anrdp/tsnet-android/` |

---

## 1. 概述

### 1.1 目标

在 Android 手机上提供轻量的 RDP 远程桌面客户端：保存连接配置、一键发起会话、会话页复刻桌面并支持触摸/缩放。

### 1.2 本文范围

描述**当前已实现**系统的技术结构：分层、数据模型、页面职责、与 FreeRDP 引擎的集成契约、分辨率策略、构建发布方式，以及已知限制和技术债。方案选型的讨论过程见关联文档，本文只记录结论与实现。

### 1.3 术语

| 术语 | 含义 |
| --- | --- |
| 引擎 / AAR | `app/libs/freeRDPCore-release.aar`，FreeRDP 官方 Android 核心库，含 `LibFreeRDP` 与原生库 |
| tsnet / tsnet AAR | `app/libs/tsnet.aar`，Tailscale 的 Go 用户态节点库（经 gomobile 封装），含 `libgojni.so` |
| Headscale | 自托管的 Tailscale 控制面，签发 pre-auth key、管理 tailnet 节点 |
| tailnet IP | Tailscale 分配给节点的 `100.64.0.0/10` 虚拟地址，RDP 主机字段填写该地址 |
| SOCKS5 代理 | tsnet 在 `127.0.0.1:随机端口` 启动的本地代理，FreeRDP 通过 `/proxy:socks5://...` 使用 |
| 自适应分辨率 | 连接配置的一种取值（`adaptive`），表示按手机屏幕可视区域计算桌面尺寸 |
| 会话页 | 引擎提供的 `com.freerdp.freerdpcore.presentation.SessionActivity` |

---

## 2. 系统架构

### 2.1 分层

```
┌─────────────────────────────────────────────────────────┐
│ UI 层                                                    │
│  MainActivity（列表 / 连接入口 / Tailscale 配置入口）      │
│  ConnectionEditActivity（新建 / 编辑 / 绑定 Tailscale）    │
│  TailscaleConfigEditActivity（tsnet 配置）                │
│  SessionActivity（复用 AAR，不可修改）                    │
├─────────────────────────────────────────────────────────┤
│ 连接适配层（本工程与引擎的耦合点）                         │
│  RdpConnector：ConnectionEntity → freerdp:// URI         │
│  TailscaleManager：启停 tsnet、读取状态、SOCKS5 地址       │
├─────────────────────────────────────────────────────────┤
│ 数据层                                                   │
│  Room（anrdp.db, v4）/ ConnectionDao / TailscaleConfigDao │
│  ConnectionEntity / TailscaleConfigEntity                │
├─────────────────────────────────────────────────────────┤
│ 引擎层（AAR + .so）                                       │
│  LibFreeRDP（JNI）→ libfreerdp3 / libwinpr3 / libssl …   │
│  tsnetandroid.Server（JNI）→ libgojni.so（WireGuard/DERP）│
└─────────────────────────────────────────────────────────┘
```

### 2.2 关键取舍

| 决策 | 结论 | 影响 |
| --- | --- | --- |
| 协议实现 | 不自研，复用 `freeRDPCore` 预编译 AAR | 免去 NDK 编译；引擎内部不可改，只能通过 URI 参数影响行为 |
| 会话 UI | 直接复用 `SessionActivity`，不自绘 | 触摸/键盘/手势由引擎提供；样式与系统栏适配受 AAR 约束 |
| 与引擎通信 | 主路径只走 URI（`freerdp://...`），不直接调用 `LibFreeRDP` Java API | 耦合面最小，但能力受 URI 解析器支持的参数集限制；例外见 6.3（文本注入用公开 API） |
| 数据存储 | Room + Kotlin Coroutines/Flow | 列表自动刷新；需要手写迁移，不能 `fallbackToDestructiveMigration` |
| 组网方式 | Tailscale tsnet 用户态 + 本地 SOCKS5，不申请 `VpnService` | 不需要系统 VPN 权限、可与其它 VPN 共存；RDP 通过 `/proxy:socks5://` 接入，需多一步等待节点就绪 |

### 2.3 运行时组件关系

```
MainActivity ──observeAll()──▶ ConnectionDao ──▶ Room(anrdp.db)
     │                              ▲
     │ 点击连接                      │ insert/update/delete
     ▼                              │
RdpConnector ──startActivity──▶ SessionActivity（AAR）
     │                              │ setConnectionInfo(Uri)
     │                              ▼
     ├── size/p/d 查询参数 ──▶ LibFreeRDP ──JNI──▶ libfreerdp3.so
     │                              │
     │                              └─ 画面回调 ──▶ SessionView（ScrollView2D + 手势缩放）
     │
     └─ tailscaleConfigId > 0 ──▶ TailscaleManager
                                   ├─ tsnetandroid.Server.start()（IO 线程）
                                   ├─ 轮询 status() 直到 running（≤60s）
                                   └─ startSOCKS5() → 127.0.0.1:port
                                        └─ 作为 ?proxy=socks5://host:port 交给引擎
```

---

## 3. 技术栈与工程配置

| 项 | 版本 / 取值 |
| --- | --- |
| 语言 | Kotlin（AGP 9 内置 Kotlin 支持，不单独应用 kotlin-android 插件） |
| AGP / Gradle / JDK | 9.2.1 / 9.6.1 / 17（重编引擎 AAR 需 JDK 21） |
| compileSdk / targetSdk / minSdk | 37 / 37 / 29 |
| ABI | 仅 `arm64-v8a` |
| UI | ViewBinding + Material 3（`material:1.14.0`）、AppCompat |
| 数据 | Room 2.8.5（KSP）、coroutines 1.11.0；数据库 `anrdp.db` v4 |
| 引擎 | `freeRDPCore-release.aar`（4.8MB，含 `libfreerdp3`、`libwinpr3`、`libssl`、`libcrypto`、`libsqlcipher` 等） |
| 组网 | `tsnet.aar`（15MB，`tailscale.com` v1.104.0 + Go 1.27.1，含 `libgojni.so` 30.5MB，`minSdk 21`） |
| 签名 | 固定 release keystore（PKCS12，别名 `androiddebugkey`），由 `keystore.properties` 提供，密钥与口令不入库 |
| 构建内存约束 | `-Xmx1408m`、`workers.max=1`、`parallel=false`（构建机内存有限） |

仓库仅使用 `google()`、`mavenCentral()`、`gradlePluginPortal()` 与本地补充仓库，顺序不可调整（镜像/残缺元数据会导致资源变体解析为空）。

---

## 4. 数据层设计

### 4.1 表结构（`connections`，DB 版本 4）

| 字段 | 类型 | 约束 / 默认 | 说明 |
| --- | --- | --- | --- |
| id | INTEGER | PK AUTOINCREMENT | 主键 |
| name | TEXT | NOT NULL | 显示名，为空时回退为 host |
| host | TEXT | NOT NULL | 主机名或 IP |
| port | INTEGER | NOT NULL, 默认 3389 | 1–65535 |
| username | TEXT | NOT NULL | 登录用户 |
| password | TEXT | NOT NULL, 默认 `""` | 空串 = 不保存，连接时弹框询问 |
| domain | TEXT | NOT NULL, 默认 `""` | 可选域 |
| resolution | TEXT | NOT NULL, 默认 `adaptive` | `adaptive` 或 `WxH` |
| tailscaleConfigId | INTEGER | NOT NULL, 默认 0 | 绑定的 `tailscale_configs.id`；0 = 直连 |
| createdAt | INTEGER | NOT NULL | 创建时间戳 |
| lastUsedAt | INTEGER | NOT NULL, 默认 0 | 最近使用时间戳，用于排序 |

迁移 SQL（`AppDatabase`）：

```sql
-- MIGRATION_1_2
ALTER TABLE connections ADD COLUMN resolution TEXT NOT NULL DEFAULT 'adaptive';

-- MIGRATION_3_4
ALTER TABLE connections ADD COLUMN tailscaleConfigId INTEGER NOT NULL DEFAULT 0;
```

设计要点：

- **版本必须连续迁移**：v1 已安装过，升级到 v4 不能清库；新加字段一律带默认值。
- **Tailscale 绑定允许悬空**：`tailscaleConfigId` 指向的配置被删除后不阻断列表与编辑；连接时 `RdpConnector` 抛「配置不存在」错误并以 Toast 提示。
- **排序规则**：`ORDER BY lastUsedAt DESC, createdAt DESC`，点击连接即 `touch()` 更新 `lastUsedAt`。
- **密码默认不落库**：编辑页留空即保存空串，连接时由 `MainActivity.promptPassword()` 询问，且不写回。

### 4.2 表结构（`tailscale_configs`，DB 版本 4）

| 字段 | 类型 | 约束 / 默认 | 说明 |
| --- | --- | --- | --- |
| id | INTEGER | PK AUTOINCREMENT | 主键 |
| name | TEXT | NOT NULL | 配置显示名 |
| controlURL | TEXT | NOT NULL | Headscale 控制面 URL，如 `https://hs.example.com` |
| authKey | TEXT | NOT NULL | pre-auth key（节点注册凭证） |
| hostname | TEXT | NOT NULL, 默认 `anrdp` | 节点在 tailnet 中的主机名 |
| stateDir | TEXT | NOT NULL, 默认 `""` | tsnet 状态目录；为空时由 `TailscaleManager` 生成 `filesDir/tailscale-state-<id>` |
| createdAt | INTEGER | NOT NULL | 创建时间戳 |

迁移 SQL（`AppDatabase.MIGRATION_2_3`）：

```sql
CREATE TABLE IF NOT EXISTS tailscale_configs (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    controlURL TEXT NOT NULL,
    authKey TEXT NOT NULL,
    hostname TEXT NOT NULL,
    stateDir TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);
```

DAO 契约：`observeAll()`、`getAll()`、`getById(id)`、`insert`、`update`、`delete(id)`。

### 4.3 DAO 契约

| 方法 | 类型 | 说明 |
| --- | --- | --- |
| `observeAll(): Flow<List<ConnectionEntity>>` | 查询 | 列表页收集，自动刷新 |
| `getById(id): ConnectionEntity?` | 查询 | 编辑页回填 |
| `insert` / `update` / `delete` | 挂起 | 编辑/删除 |
| `touch(id, ts)` | 挂起 | 仅更新 `lastUsedAt` |

`id = 0` 视为新增；`insert` 由 Room 回填自增 id。

### 4.4 已知风险

**密码与 authKey 明文存储**：`connections.password` 与 `tailscale_configs.authKey` 均直接写入未加密的 SQLite。当前仅限自用；authKey 可设置过期/一次性以降低泄露影响，若要多设备/共享使用，需引入 Keystore 加密或 SQLCipher（依赖已在，但应用库未启用）。

---

## 5. 应用层设计

### 5.1 列表页 `MainActivity`

- 数据：`lifecycleScope` 收集 `observeAll()`，`ListAdapter` + `DiffUtil` 提交（比较 `id` 与整对象相等）。
- 绑定：名称（空则 host）、`host:port`、用户名；空列表显示 `emptyView`。
- 交互：右下 FAB 新建；左下 FAB 打开 Tailscale 配置（已有配置时回填最近创建的一条）；点击连接；长按弹出「复制 / 编辑 / 删除」（删除二次确认）。
- 连接：先后台 `touch()`，再判断密码：为空走 `promptPassword()` 对话框，否则直接连接。连接在 `lifecycleScope` 协程中执行并捕获 `Throwable`，失败以 Toast 显示（含 tsnet 日志尾部）。

### 5.2 编辑页 `ConnectionEditActivity`

- 新建与编辑共用；`EXTRA_CONNECTION_ID`（默认 -1）区分，`EXTRA_COPY_FROM_ID` 用于复制新建。
- 校验：host 非空、port 必须为 1–65535 的整数，错误通过 `TextInputLayout.error` 展示。
- 分辨率：`MaterialAutoCompleteTextView` 下拉，显示文案与取值由 `arrays.xml` 中两个等长数组按索引对应（见 5.3）。
- Tailscale：`Spinner` 下拉，选项为「不使用 Tailscale（直连）」+ 已保存配置（显示 `名称 (controlURL)`）；选中项写入 `tailscaleConfigId`。配置与连接按顺序加载，避免异步回显覆盖选中值。
- 保存：保留原 `id`、`createdAt`、`lastUsedAt`；新增时 `createdAt` 取当前时间。

### 5.3 分辨率策略（核心设计）

**取值集合**（`res/values/arrays.xml`，标签与值一一对应）：

| 显示文案 | 存储值 |
| --- | --- |
| 自适应（按手机屏幕） | `adaptive` |
| 1280 × 720 | `1280x720` |
| 1600 × 900 | `1600x900` |
| 1920 × 1080 | `1920x1080` |
| 2560 × 1440 | `2560x1440` |
| 1024 × 768 | `1024x768` |

**解析规则**（`RdpConnector.desktopSize`）：

1. 非 `adaptive` 且能解析为 `宽x高`（正整数）→ 原样传给引擎；
2. 其余情况（`adaptive`、空值、脏数据）→ 取屏幕可视区域。

**自适应计算**（`RdpConnector.visibleScreenSize`）：

- API 30+：`WindowManager.maximumWindowMetrics` 的 bounds 减去 `systemBars()` 与 `displayCutout()` 的 `getInsetsIgnoringVisibility`；
- API 29：回退 `resources.displayMetrics` 的 `widthPixels × heightPixels`；
- 结果即"用户真正能看到的区域"，避免桌面被状态栏/导航栏/挖孔遮挡。

**为什么不做 dp 换算**：RDP 桌面是逻辑像素，若传 dp 尺寸，客户端会把小位图拉伸到物理像素导致模糊；传物理像素则点对点显示，清晰度最佳。文字偏小属于服务器端显示缩放设置，不由客户端承担。

**已知行为**：

- 分辨率在**连接时**固定；会话中旋转/分屏不会重新协商（引擎 `configChanges` 已声明，不会重建 Activity）。
- 小于屏幕的分辨率会留黑边；大于屏幕的分辨率需在会话页双指拖动/缩放查看（引擎 `SessionView` 支持 0.75–3.0 缩放）。

### 5.4 会话页（复用）

引擎的 `SessionActivity` 通过 `LibFreeRDP.setConnectionInfo(Uri)` 把 URI 解析成 xfreerdp 参数并发起连接，画面回调渲染到 `SessionView`（内部为 `ScrollView2D` + 手势缩放 + 触摸指针）。本工程不修改、不继承该页面，只通过 Intent data 影响其行为。

### 5.5 Tailscale 配置页 `TailscaleConfigEditActivity`

- 入口：列表页左下 FAB；已有配置时打开最近创建的一条并回填，否则新建。当前 UI 为单配置快捷编辑，没有配置列表与删除入口（DAO 已提供 `delete`）。
- 字段：名称、Headscale 控制面 URL、pre-auth key、主机名（默认 `anrdp`）；`stateDir` 不暴露，由应用自动生成。
- 校验：名称、控制面 URL、pre-auth key 非空；通过 `TextInputLayout.error` 提示。保存后 `finish()`。
- 页面布局：`activity_tailscale_config_edit.xml`（Toolbar + ScrollView + 四个输入框 + 保存按钮）。

### 5.6 Tailscale 管理器 `TailscaleManager`

单例 `object`，封装 `tsnetandroid.Server`。所有 gomobile JNI 调用均为阻塞式，统一 `withContext(Dispatchers.IO)` + `Mutex` 串行化：

| 方法 | 说明 |
| --- | --- |
| `suspend start(context, config)` | 生成/使用 `stateDir`，注入 `ConnectivityManager` 网络信息，创建 `Server` 并 `start()`；失败时 `close()` 清理并附带 `tsnet.log` 尾部抛异常 |
| `suspend startSOCKS5(): String` | 启动本地 SOCKS5，返回 `host:port` |
| `suspend stopSOCKS5()` | 停止代理并清空地址 |
| `suspend status(): String` | `running` / `starting` / `stopped` / `error` |
| `suspend tailnetIP(): String` | 节点虚拟 IP，未就绪返回空串 |
| `suspend stop()` | 停止 SOCKS5 与 tsnet，释放资源 |
| `fun socks5Address(): String?` | 已启动的代理地址 |
| `fun isRunning(): Boolean` | 进程内是否已有 tsnet 实例 |

要点：

- `start()` 只创建一次实例；已运行则忽略后续请求（同一时间只支持一个 tsnet 节点）。
- 启动前必须从 `ConnectivityManager` 读取活动网络的接口名、地址、默认网关并调用 `Tsnetandroid.setNetworkInfo`，否则 Go 侧 netmon 只有 loopback，会在 magicsock 深处 panic。
- Go 侧 `NewServer` 设置 `TS_LOGS_DIR=stateDir` 并把 tsnet 日志写入 `stateDir/tsnet.log`；否则 `logpolicy` 在 Android 上找不到可写目录会 panic。
- Go 侧 `Start()` 用 `recover` 将 panic 转为 error（Java 异常）；native SIGABRT 仍会杀进程，排查依赖 `filesDir/tailscale-state-<id>/tsnet.log`。

---

## 6. 与引擎的集成契约

### 6.1 URI → xfreerdp 参数映射

`RdpConnector` 构造的 URI：

```
freerdp://<user>@<host>[:<port>]/connect?size=<WxH>&p=<password>&d=<domain>[&proxy=socks5://<proxyHost>:<proxyPort>]
```

| URI 组成 | 引擎行为 |
| --- | --- |
| authority 中的 `user@` | `/u:<user>` |
| authority 中的 `host[:port]` | `/v:<host>:<port>` |
| 查询参数 `p` | `/p:<password>`（空则不传） |
| 查询参数 `d` | `/d:<domain>` |
| 查询参数 `size` | `/size:<WxH>` |
| 查询参数 `proxy` | `/proxy:socks5://127.0.0.1:<port>`（仅绑定 Tailscale 配置时附加） |
| 其他任意查询参数 | 原样转为 `/key:value`；无值转 `/key`；`+key` / `-key` 分别转布尔参数 |
| 固定附加 | `/gdi:sw`（软件渲染，引擎内部固定，不可关闭） |

**约束**：

- 端口为 3389 时可省略；非法端口在应用层先抛异常（编辑页校验已拦截）。
- 密码经 URI 传递，仅在进程内使用；引擎调试日志可能打印参数，现未开启。
- `host` 在 Tailscale 模式下必须是目标节点的 tailnet IP；当前不支持 MagicDNS 名称。
- `proxy` 指向应用进程内的 `127.0.0.1` SOCKS5，随应用进程存活；tsnet 停止后代理失效。
- 可用的 xfreerdp 参数受 AAR 版本支持范围限制（如 `+dynamic-resolution` 未在现用 UI 暴露）。

### 6.2 会话页清单属性（来自 AAR，不可改）

| 属性 | 值 | 影响 |
| --- | --- | --- |
| `exported` | true | 外部应用可通过 `freerdp://` 拉起会话 |
| `documentLaunchMode` | always | 每次连接新开任务，退到最近任务可见 |
| `autoRemoveFromRecents` | true | 会话结束自动从最近任务移除 |
| `taskAffinity` | `com.freerdp.session` | 会话任务独立于主任务 |
| `configChanges` | orientation/screenSize/density… | 旋转不重建，分辨率不会重算 |
| `windowSoftInputMode` | adjustResize | 软键盘弹出时压缩画面 |

### 6.3 输入与键盘契约（2026-11-08 实测 / 反编译结论）

- `SessionView.onCreateInputConnection` 返回 `BaseInputConnection(view, false)`，且 `EditorInfo.inputType = 0`。
- **英文 / 符号键**：走 `KeyEvent` → `SessionActivity.onKeyDown` → `KeyboardMapper` → 扫描码，功能正常。
- **手机侧中文输入法**：`commitText` 因 `fullEditor=false` 没有 `Editable` 而被直接丢弃，表现为"软键盘弹出但远端无输入"。当前方案是切英文键盘打拼音，由**远端输入法**（fcitx/ibus）上屏。
- 其他可用路径：`ExtendedKeyboardView`（浮动工具条中的自绘 PC 键盘）发送真实扫描码；或调用 AAR 公开 API `LibFreeRDP.sendUnicodeKeyEvent(instance, ch, down)` 逐字符注入，无需修改 AAR。
- `SessionInputManager.toggleKeyboard()` 同时做两件事：显示 `ExtendedKeyboardView` 与 `showSoftInput(sessionView, 1)`。

---

## 7. 关键流程

### 7.1 新建并连接

```
用户点 FAB → 编辑页（空表单，分辨率默认"自适应"）
   → 校验 host/port → 保存（insert，回填 id）→ 返回列表（Flow 自动刷新）
用户点条目 → touch(lastUsedAt) → 有密码？ ─是─▶ RdpConnector.connect()
                                     └否─▶ 密码对话框 → connect()
connect(): 组装 URI（含 size/p/d）→ 显式 Intent → SessionActivity
```

### 7.2 Tailscale 连接

```
用户点条目（tailscaleConfigId > 0）
   → RdpConnector 读取 TailscaleConfig
   → TailscaleManager.start(context, config)（IO 线程；已运行则复用）
   → 轮询 status()（500ms 间隔，最长 60s）
        ├─ running → tailnetIP() 可用
        └─ 超时/error → 抛异常 → MainActivity Toast（附 tsnet.log 尾部）
   → socks5Address() 已存在则复用，否则 startSOCKS5()
   → 组装 URI（host = 连接配置的 host，附 proxy=socks5://127.0.0.1:<port>）
   → 显式 Intent → SessionActivity
```

说明：tsnet 实例在应用进程内常驻，后续连接复用同一节点与代理；退出应用后由系统回收。无前台服务，后台存活时间受系统限制。

### 7.3 构建发布

**本地构建**（`scripts/build-app.sh`）：

```
scripts/build-app.sh [debug|release]
  1) 生成 /tmp 工作副本（处理共享挂载盘的 CRLF，修复 gradlew 可执行）
  2) 安装/复用工具链（JDK 17 + Gradle 9.6.1 + SDK）
  3) :app:assembleDebug | assembleRelease
  4) 校验产物（dex + arm64-v8a 原生库 + 权限清单）并回填 anrdp/app-<variant>.apk
  5) 版本名默认取当前提交可达的最近 v* tag（可用 APP_VERSION_NAME 覆盖），APK 版本号必须与之一致
```

**CI 发布**（GitHub Actions）：

| 工作流 | 触发 | 动作 |
| --- | --- | --- |
| `build.yml` | push 到 main / PR / 手动 | `assembleDebug`，校验权限清单与引擎原生库，上传构建产物 |
| `release.yml` | tag `v*` / 手动 | 注入签名密钥 → `assembleRelease` → 校验版本号与 tag 一致、权限、原生库 → 打包 `AnRDP-<tag>-arm64-v8a.apk` + `.sha256` → 创建 GitHub Release |

手动触发的非 tag 运行使用版本名 `0.0.0-dev.<run_number>`，且不创建 Release。

---

## 8. 构建与发布

### 8.1 构建类型差异

| 项 | debug | release |
| --- | --- | --- |
| 体积 | 29MB（v2 基线，未含 tsnet；当前未重测） | 约 46MB（含 tsnet，v1.0.1 实测） |
| dex | 14.4MB（未压缩） | 2.5MB（R8 收缩） |
| 原生库 | 12.5MB | 约 42MB（引擎 12.5MB + `libgojni.so` 30.5MB） |
| 签名 | debug keystore | **固定 release keystore**（PKCS12，别名 `androiddebugkey`，沿用旧版 debug 密钥副本）；读取顺序：仓库根 `keystore.properties` → `../anrdp-secrets/keystore.properties` → 两者都缺失时回落 debug 签名；本机与 CI 使用同一把密钥，旧包可直接覆盖升级 |
| 调试 | `jniDebuggable true` | 关闭 |
| 优化 | 无 | `minifyEnabled` + `shrinkResources` |

### 8.2 R8 keep 规则依据（`app/proguard-rules.pro`）

| 规则 | 原因 |
| --- | --- |
| `-keep class ...LibFreeRDP { *; }` | 原生层通过 JNI 按**方法名**回调 Java（`OnConnectionSuccess`、`OnGraphicsUpdate` 等），混淆即失效 |
| `-keepclasseswithmembernames ... native <methods>` | 保证 native 方法名不被改写 |
| `-keep public class * extends View { <init>(Context, AttributeSet) }` | 布局 XML 以类名实例化自定义 View（`SessionView` 等） |
| `-dontwarn com.freerdp.** / org.slf4j.**` | 引擎引用未打包的可选依赖 |
| AAR 自带 `proguard.txt`：`-keep class go.**`、`-keep class tsnetandroid.**` | gomobile 生成的 Java 类由 JNI 按名称回调，混淆即失效；该规则随 `tsnet.aar` 合并，勿删除 |

### 8.3 产物校验

产物应包含 `lib/arm64-v8a/` 下 11 个原生库：`libfreerdp3`、`libfreerdp-client3`、`libfreerdp-android`、`libwinpr3`、`libssl`、`libcrypto`、`libsqlcipher`、`libcjson`、`liburiparser`、`legacy`、`libgojni`。

构建脚本当前断言：`classes*.dex` 存在，且 `libfreerdp-android`、`libfreerdp3`、`libfreerdp-client3`、`libwinpr3`、`libssl`、`libcrypto`、`libsqlcipher` 共 7 个引擎库存在；输出 SHA-256 便于真机核对。

> `libgojni.so` 由 `tsnet.aar` 合并进 APK，脚本未单独断言；若 `app/libs/tsnet.aar` 缺失会直接构建失败。

### 8.4 签名与发布链路（GitHub Actions）

**仓库拆分**

| 仓库 | 托管 | 内容 |
| --- | --- | --- |
| `anrdp` | GitHub（公开） | 源码、文档、构建脚本，不得含任何密钥 |
| `anrdp-secrets` | 本地私有 Gitea | `keystore/anrdp-release.jks`、`keystore.properties`、token 等敏感信息 |

**密钥注入**：`release.yml` 从仓库 Secrets 读取 `KEYSTORE_B64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，base64 还原 keystore 并生成仓库根 `keystore.properties`；未配置 `KEYSTORE_B64` 时打印警告并回落 debug 签名（产物无法覆盖安装正式包）。

**密钥指纹**（SHA-256，用于核对发布包）：

```
D2:F9:B6:18:C2:F2:FA:04:53:B0:05:96:94:6B:93:1A:CB:
2A:43:65:CF:30:8D:DC:CE:24:54:C1:E8:5E:E2:FD
```

**版本号**

- `versionName` 来自 tag（去掉前缀 `v`），CI 校验 APK 内版本号与 tag 一致。
- `versionCode` 由 `versionName` 按 `x*10000 + y*100 + z` 推导，最小为 1；保证 tag 递增即 code 递增。

**发布产物**：`AnRDP-<tag>-arm64-v8a.apk` 与同名 `.sha256`，由 `softprops/action-gh-release` 自动附到 Release。

**已发生的问题**：`v1.0.0` 发布时工作流尚未包含密钥注入步骤，产物为临时 debug 签名，无法覆盖升级；该 Release 与 tag 已删除。`v1.0.1` 是首个使用固定密钥的发布。

---

## 9. 质量与风险

### 9.1 已知限制

| 编号 | 问题 | 影响 | 处理建议 |
| --- | --- | --- | --- |
| R1 | 密码明文入库 | 本地取证可读 | Keystore 加密字段或启用 SQLCipher |
| R2 | 会话页 `exported=true` | 任意应用可拉起会话并传参 | 主清单 `tools:node="remove"` 覆盖为 false（本工程只用显式 Intent） |
| R3 | 未处理系统栏/IME insets | 列表页与编辑页内容可能被栏遮挡 | 目标 SDK 37 强制 edge-to-edge，需显式 insets |
| R4 | 分辨率连接时固定 | 旋转后画面不匹配 | 暴露 `+dynamic-resolution` 或会话侧监听尺寸 |
| R5 | 连接失败反馈不完整 | `RdpConnector` 抛出的异常（含 tsnet 启动失败）已由 `MainActivity` Toast 展示；引擎内部握手失败仍只会停在会话页 | 监听引擎会话事件，回传 Toast/Snackbar |
| R6 | 无搜索/排序切换/删除撤销 | 连接多时难用 | 搜索框 + Undo（软删除字段） |
| R7 | 无自动化测试 | 回归靠手工 | 至少补 DB 迁移与 URI 组装的单元测试 |
| R8 | `versionCode` 由 tag 推导（x.y.z → x*10000+y*100+z） | 不能按应用商店规则分级 | 已缓解：CI Secrets 注入固定密钥、tag 触发发布（v1.0.1 起）；上架前需另定版本策略 |
| R9 | 手机侧中文输入法 `commitText` 被引擎输入连接丢弃 | 无法直接用手机输入法上屏 | 改用远端输入法；或在应用层调用 `LibFreeRDP.sendUnicodeKeyEvent` 逐字符注入 |
| R10 | tsnet 原生层可能 panic / SIGABRT | 应用进程被杀，连接中断 | 已缓解：启动前注入网络信息、Go 侧 `recover`；仍失败时读 `tsnet.log` 定位 |
| R11 | tsnet 无常驻前台服务 | 应用退到后台久了进程被回收，RDP 断开 | 后续加前台服务（需 `FOREGROUND_SERVICE` 与通知权限）或连接期间保持前台 |
| R12 | 单 tsnet 实例 / 单配置快捷编辑 | 无法同时使用多个 tailnet 节点，无法在 UI 删除配置 | DAO 已支持多配置；后续补配置列表、切换与删除 |
| R13 | pre-auth key 明文入库 | 本地取证可读 | 使用一次性/短期 key；与 R1 一并做 Keystore 加密 |
| R14 | tsnet 使 APK 增大约 30.5MB | 安装包体积偏大 | 按 ABI 分包或改为可选特性交付 |

### 9.2 技术债

- URI 作为唯一集成面，能力受限但稳定；若需剪贴板增强、音频、文件传输等高级能力，必须评估改造 AAR 或直接调用 `LibFreeRDP`。
- 分辨率选项与显示文案靠两个数组索引对齐，新增选项必须同时改两处。
- 12.5MB 原生库为体积下限；`libsqlcipher`（2.1MB）被引擎数据库使用，裁剪需重编 AAR 并回归。
- tsnet AAR 由仓库内 `tsnet-android/` Go module 构建，`scripts/build-tsnet-aar.sh` 可重建并同步 `tsnet-sources.jar`。当前仓库内 `tsnet-sources.jar` 仍是早期版本（早于 `setNetworkInfo` 改动），AAR 本体已含该方法；重跑脚本即可回填最新 sources。
- `TailscaleManager` 全部 JNI 调用串行化且只支持单实例；未来多节点需要改为按配置 id 管理实例池。
- tsnet 状态目录（`filesDir/tailscale-state-<id>/`）会持久化节点身份与 `tsnet.log`，卸载应用才清理；暂无「重置节点」入口。

---

## 10. 演进建议（按优先级）

1. **会话与输入**：错误反馈（R5）、中文文本注入（R9）、动态分辨率（R4）。
2. **安全与显示**：密码加密（R1）、限制会话页导出（R2）、insets 适配（R3）。
3. **日常易用**：搜索/排序/撤销删除（R6）、分辨率与画质档位、记住密码开关。
4. **工程化**：单元测试（R7）、补 `values-en`；正式签名与 CI 发布已完成（R8）。
5. **Tailscale 完善**：前台服务保活（R11）、多配置列表/切换/删除（R12）、节点状态页与重置、authKey 加密（R13）、按 ABI 分包（R14）。

---

## 附录 A：常用命令

```bash
# 构建
anrdp/scripts/build-app.sh            # debug  → anrdp/app-debug.apk
anrdp/scripts/build-app.sh release    # release → anrdp/app-release.apk

# 安装
adb install -r anrdp/app-release.apk

# 校验
apksigner verify --print-certs anrdp/app-release.apk
aapt2 dump resources anrdp/app-release.apk | grep resolution

# 重编 tsnet AAR（Go 1.27.1 + gomobile；脚本内已处理 GOROOT/GOPROXY）
scripts/build-tsnet-aar.sh

# 发布（打 tag 触发 release.yml）
git tag -a v1.0.2 -m "AnRDP v1.0.2"
git push origin v1.0.2
```

## 附录 B：连接配置字段与默认值

| 字段 | 必填 | 默认 | 备注 |
| --- | --- | --- | --- |
| 名称 | 否 | 空（显示为 host） | |
| 主机 | 是 | — | 域名或 IP |
| 端口 | 是 | 3389 | 1–65535 |
| 用户名 | 否 | `""` | 为空时不传 `/u:` |
| 密码 | 否 | `""` | 为空则连接前询问，不落库 |
| 域名 | 否 | `""` | 为空时不传 `/d:` |
| 分辨率 | 是 | `adaptive` | 见 5.3 |
| Tailscale 配置 | 否 | 0（直连） | `tailscaleConfigId`；指向 `tailscale_configs.id` |

## 附录 C：Tailscale 配置字段与默认值

| 字段 | 必填 | 默认 | 备注 |
| --- | --- | --- | --- |
| 名称 | 是 | — | UI 显示名，可任意 |
| 控制面 URL | 是 | — | Headscale 地址，如 `https://hs.example.com` |
| pre-auth key | 是 | — | 建议一次性/短期 key |
| 主机名 | 是 | `anrdp` | tailnet 内节点名 |
| 状态目录 | 否 | 自动 | `filesDir/tailscale-state-<id>`，不暴露给 UI |
