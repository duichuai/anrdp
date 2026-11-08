# AnRDP 技术设计文档

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 生成日期 | 2026-11-07 |
| 状态 | 已实现（对应提交 `a935b04`） |
| 关联文档 | [android-rdp-design.md](./android-rdp-design.md)（方案设计 / 决策记录） |
| 代码位置 | `anrdp/`（Gradle 根工程，唯一模块 `:app`） |

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
| 自适应分辨率 | 连接配置的一种取值（`adaptive`），表示按手机屏幕可视区域计算桌面尺寸 |
| 会话页 | 引擎提供的 `com.freerdp.freerdpcore.presentation.SessionActivity` |

---

## 2. 系统架构

### 2.1 分层

```
┌─────────────────────────────────────────────────────────┐
│ UI 层                                                    │
│  MainActivity（列表 / 连接入口）                          │
│  ConnectionEditActivity（新建 / 编辑）                    │
│  SessionActivity（复用 AAR，不可修改）                    │
├─────────────────────────────────────────────────────────┤
│ 连接适配层（本工程与引擎的唯一耦合点）                     │
│  RdpConnector：ConnectionEntity → freerdp:// URI         │
├─────────────────────────────────────────────────────────┤
│ 数据层                                                   │
│  Room（anrdp.db, v2）/ ConnectionDao / ConnectionEntity   │
├─────────────────────────────────────────────────────────┤
│ 引擎层（AAR + .so）                                       │
│  LibFreeRDP（JNI）→ libfreerdp3 / libwinpr3 / libssl …   │
└─────────────────────────────────────────────────────────┘
```

### 2.2 关键取舍

| 决策 | 结论 | 影响 |
| --- | --- | --- |
| 协议实现 | 不自研，复用 `freeRDPCore` 预编译 AAR | 免去 NDK 编译；引擎内部不可改，只能通过 URI 参数影响行为 |
| 会话 UI | 直接复用 `SessionActivity`，不自绘 | 触摸/键盘/手势由引擎提供；样式与系统栏适配受 AAR 约束 |
| 与引擎通信 | 只走 URI（`freerdp://...`），不直接调用 `LibFreeRDP` Java API | 耦合面最小，但能力受 URI 解析器支持的参数集限制 |
| 数据存储 | Room + Kotlin Coroutines/Flow | 列表自动刷新；需要手写迁移，不能 `fallbackToDestructiveMigration` |

### 2.3 运行时组件关系

```
MainActivity ──observeAll()──▶ ConnectionDao ──▶ Room(anrdp.db)
     │                              ▲
     │ 点击连接                      │ insert/update/delete
     ▼                              │
RdpConnector ──startActivity──▶ SessionActivity（AAR）
     │                              │ setConnectionInfo(Uri)
     │                              ▼
     └── size/p/d 查询参数 ──▶ LibFreeRDP ──JNI──▶ libfreerdp3.so
                                   │
                                   └─ 画面回调 ──▶ SessionView（ScrollView2D + 手势缩放）
```

---

## 3. 技术栈与工程配置

| 项 | 版本 / 取值 |
| --- | --- |
| 语言 | Kotlin（AGP 9 内置 Kotlin 支持，不单独应用 kotlin-android 插件） |
| AGP / Gradle / JDK | 9.2.1 / 9.6.1 / 17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 29 |
| ABI | 仅 `arm64-v8a` |
| UI | ViewBinding + Material 3（`material:1.14.0`）、AppCompat |
| 数据 | Room 2.8.5（KSP）、coroutines 1.11.0 |
| 引擎 | `freeRDPCore-release.aar`（4.7MB，含 `libfreerdp3`、`libwinpr3`、`libssl`、`libcrypto`、`libsqlcipher` 等） |
| 构建内存约束 | `-Xmx1408m`、`workers.max=1`、`parallel=false`（构建机内存有限） |

仓库仅使用 `google()`、`mavenCentral()`、`gradlePluginPortal()` 与本地补充仓库，顺序不可调整（镜像/残缺元数据会导致资源变体解析为空）。

---

## 4. 数据层设计

### 4.1 表结构（`connections`，DB 版本 2）

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
| createdAt | INTEGER | NOT NULL | 创建时间戳 |
| lastUsedAt | INTEGER | NOT NULL, 默认 0 | 最近使用时间戳，用于排序 |

迁移 SQL（`AppDatabase.MIGRATION_1_2`）：

```sql
ALTER TABLE connections ADD COLUMN resolution TEXT NOT NULL DEFAULT 'adaptive';
```

设计要点：

- **版本必须连续迁移**：v1 已安装过，升级到 v2 不能清库；新加字段一律带默认值。
- **排序规则**：`ORDER BY lastUsedAt DESC, createdAt DESC`，点击连接即 `touch()` 更新 `lastUsedAt`。
- **密码默认不落库**：编辑页留空即保存空串，连接时由 `MainActivity.promptPassword()` 询问，且不写回。

### 4.2 DAO 契约

| 方法 | 类型 | 说明 |
| --- | --- | --- |
| `observeAll(): Flow<List<ConnectionEntity>>` | 查询 | 列表页收集，自动刷新 |
| `getById(id): ConnectionEntity?` | 查询 | 编辑页回填 |
| `insert` / `update` / `delete` | 挂起 | 编辑/删除 |
| `touch(id, ts)` | 挂起 | 仅更新 `lastUsedAt` |

`id = 0` 视为新增；`insert` 由 Room 回填自增 id。

### 4.3 已知风险

**密码明文存储**：`password` 字段直接写入未加密的 SQLite。当前仅限自用；若要多设备/共享使用，需引入 Keystore 加密或 SQLCipher（依赖已在，但应用库未启用）。

---

## 5. 应用层设计

### 5.1 列表页 `MainActivity`

- 数据：`lifecycleScope` 收集 `observeAll()`，`ListAdapter` + `DiffUtil` 提交（比较 `id` 与整对象相等）。
- 绑定：名称（空则 host）、`host:port`、用户名；空列表显示 `emptyView`。
- 交互：FAB 新建；点击连接；长按弹出「编辑 / 删除」（删除二次确认）。
- 连接：先后台 `touch()`，再判断密码：为空走 `promptPassword()` 对话框，否则直接用已存密码。

### 5.2 编辑页 `ConnectionEditActivity`

- 新建与编辑共用；`EXTRA_CONNECTION_ID`（默认 -1）区分，`id > 0` 时异步加载并回填。
- 校验：host 非空、port 必须为 1–65535 的整数，错误通过 `TextInputLayout.error` 展示。
- 分辨率：`MaterialAutoCompleteTextView` 下拉，显示文案与取值由 `arrays.xml` 中两个等长数组按索引对应（见 5.3）。
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

---

## 6. 与引擎的集成契约

### 6.1 URI → xfreerdp 参数映射

`RdpConnector` 构造的 URI：

```
freerdp://<user>@<host>[:<port>]/connect?size=<WxH>&p=<password>&d=<domain>
```

| URI 组成 | 引擎行为 |
| --- | --- |
| authority 中的 `user@` | `/u:<user>` |
| authority 中的 `host[:port]` | `/v:<host>:<port>` |
| 查询参数 `p` | `/p:<password>`（空则不传） |
| 查询参数 `d` | `/d:<domain>` |
| 查询参数 `size` | `/size:<WxH>` |
| 其他任意查询参数 | 原样转为 `/key:value`；无值转 `/key`；`+key` / `-key` 分别转布尔参数 |
| 固定附加 | `/gdi:sw`（软件渲染，引擎内部固定，不可关闭） |

**约束**：

- 端口为 3389 时可省略；非法端口在应用层先抛异常（编辑页校验已拦截）。
- 密码经 URI 传递，仅在进程内使用；引擎调试日志可能打印参数，现未开启。
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

### 7.2 构建发布

```
scripts/build-app.sh [debug|release]
  1) 生成 /tmp 工作副本（处理共享挂载盘的 CRLF，修复 gradlew 可执行）
  2) 安装/复用工具链（JDK 17 + Gradle 9.6.1 + SDK）
  3) :app:assembleDebug | assembleRelease
  4) 校验产物（dex + arm64-v8a 原生库齐全）并回填 anrdp/app-<variant>.apk
```

---

## 8. 构建与发布

### 8.1 构建类型差异

| 项 | debug | release |
| --- | --- | --- |
| 体积 | 29MB | 17MB |
| dex | 14.4MB（未压缩） | 2.5MB（R8 收缩） |
| 原生库 | 12.5MB | 12.5MB（不变） |
| 签名 | debug keystore | **沿用 debug keystore**（可直接覆盖安装，仅限自用） |
| 调试 | `jniDebuggable true` | 关闭 |
| 优化 | 无 | `minifyEnabled` + `shrinkResources` |

### 8.2 R8 keep 规则依据（`app/proguard-rules.pro`）

| 规则 | 原因 |
| --- | --- |
| `-keep class ...LibFreeRDP { *; }` | 原生层通过 JNI 按**方法名**回调 Java（`OnConnectionSuccess`、`OnGraphicsUpdate` 等），混淆即失效 |
| `-keepclasseswithmembernames ... native <methods>` | 保证 native 方法名不被改写 |
| `-keep public class * extends View { <init>(Context, AttributeSet) }` | 布局 XML 以类名实例化自定义 View（`SessionView` 等） |
| `-dontwarn com.freerdp.** / org.slf4j.**` | 引擎引用未打包的可选依赖 |

### 8.3 产物校验

构建脚本在打包后断言：

- `classes*.dex` 存在；
- `lib/arm64-v8a/` 下 10 个原生库齐全（`libfreerdp3`、`libfreerdp-client3`、`libfreerdp-android`、`libwinpr3`、`libssl`、`libcrypto`、`libsqlcipher`、`libcjson`、`liburiparser`、`legacy`）；
- 输出 SHA-256，便于真机核对。

---

## 9. 质量与风险

### 9.1 已知限制

| 编号 | 问题 | 影响 | 处理建议 |
| --- | --- | --- | --- |
| R1 | 密码明文入库 | 本地取证可读 | Keystore 加密字段或启用 SQLCipher |
| R2 | 会话页 `exported=true` | 任意应用可拉起会话并传参 | 主清单 `tools:node="remove"` 覆盖为 false（本工程只用显式 Intent） |
| R3 | 未处理系统栏/IME insets | 列表页与编辑页内容可能被栏遮挡 | 目标 SDK 37 强制 edge-to-edge，需显式 insets |
| R4 | 分辨率连接时固定 | 旋转后画面不匹配 | 暴露 `+dynamic-resolution` 或会话侧监听尺寸 |
| R5 | 连接失败无应用层反馈 | 用户只看到会话页停住 | 监听引擎会话事件，回传 Toast/Snackbar |
| R6 | 无搜索/排序切换/删除撤销 | 连接多时难用 | 搜索框 + Undo（软删除字段） |
| R7 | 无自动化测试 | 回归靠手工 | 至少补 DB 迁移与 URI 组装的单元测试 |
| R8 | `versionCode=1`、release 用 debug 签名 | 无法作为正式渠道分发 | 建正式 keystore，CI 注入版本号 |

### 9.2 技术债

- URI 作为唯一集成面，能力受限但稳定；若需剪贴板增强、音频、文件传输等高级能力，必须评估改造 AAR 或直接调用 `LibFreeRDP`。
- 分辨率选项与显示文案靠两个数组索引对齐，新增选项必须同时改两处。
- 12.5MB 原生库为体积下限；`libsqlcipher`（2.1MB）被引擎数据库使用，裁剪需重编 AAR 并回归。

---

## 10. 演进建议（按优先级）

1. **安全与显示**：密码加密（R1）、限制会话页导出（R2）、insets 适配（R3）。
2. **会话稳定性**：错误反馈（R5）、动态分辨率（R4）。
3. **日常易用**：搜索/排序/撤销删除（R6）、分辨率与画质档位、记住密码开关。
4. **工程化**：单元测试与 CI（R7）、正式签名与版本号（R8）、补 `values-en`。

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
