# AnRDP 集成 Tailscale 设计文档

> 版本：v1.1（设计 + 实现记录）
> 状态：**已实现**（2026-11-08，改动尚未提交）
> 关系：替代 `n2n-integration-design.md` 方案。利用 Tailscale（WireGuard）的成熟生态与 tsnet 嵌入库，在 anrdp 应用内实现 P2P 组网，使 RDP 可跨网络连接虚拟网段内的主机。

## 实现状态（2026-11-08）

实际实现与本文设计的差异如下（细节以 `docs/technical-design.md` §2.3 / §5.5 / §5.6 / §7.2 为准）：

| 项 | 设计稿 | 实际实现 |
| --- | --- | --- |
| Go wrapper | `tsnet-android/`（独立模块） | 工作区根 `tsnet-android/`（Go module，**未纳入 anrdp 仓库**） |
| wrapper API | `SOCKS5Listener()`，依赖 `Server.ServeSOCKS5` | `StartSOCKS5()` / `StopSOCKS5()` / `SOCKS5Addr()`，用 `tailscale.com/net/socks5.Server` + `tsnet.Server.Dial` |
| 网络信息 | 未涉及 | Java 侧 `ConnectivityManager` → `Tsnetandroid.SetNetworkInfo` 注入接口/地址/网关（Android UID 无法直接 `net.Interfaces()`） |
| 启动异常 | 未涉及 | Go `Start()` 内 `recover` panic；tsnet 日志写入 `stateDir/tsnet.log` |
| 版本 | tsnet 1.98.x、androidapi 29 | `tailscale.com` **v1.104.0**、Go **1.27.1**、`-androidapi 21` |
| 配置页 | 配置列表 + 多套配置 | **单配置快捷编辑**（`TailscaleConfigEditActivity`，左下 FAB），无列表/删除入口 |
| 就绪等待 | 15s | **60s**（每 500ms 轮询） |
| 前台服务 | 建议实现 | **未实现**；tsnet 随应用进程存活 |
| 构建脚本 | `scripts/build-tsnet-aar.sh` | 无脚本，手工 `gomobile bind` |
| 产物体积 | AAR 15–25MB | `tsnet.aar` 15MB，`libgojni.so` 30.5MB；release APK ≈46MB |

已完成：Go wrapper、tsnet AAR、数据层（DB v4）、配置页、连接绑定与 SOCKS5 联动、错误回传。待办：真机 tailnet 端到端验证、前台服务、多配置管理页、状态展示。

---

## 1. 目标与范围

### 1.1 产品目标

为 AnRDP 增加 **Tailscale 节点**能力：将 Android 设备作为 Tailscale 网络（tailnet）中的一个节点，与目标 Windows 主机（也安装 Tailscale 客户端）建立加密 P2P 通道，再用 RDP 连接目标主机的 Tailscale 虚拟 IP。

| 编号 | 需求 | 说明 |
| --- | --- | --- |
| T1 | Tailscale 节点管理 | 配置 Headscale 服务器地址、auth key、节点名，启动/停止 tsnet，显示连接状态与虚拟 IP |
| T2 | 应用内用户态组网 | tsnet 以用户态网络栈运行，**不申请 VpnService 权限**，不与系统其他 VPN 冲突 |
| T3 | 与 RDP 联动 | 连接 RDP 前自动启动 tsnet，通过 SOCKS5 代理让 FreeRDP 流量走 Tailscale 隧道 |
| T4 | 多套 Tailscale 配置 | 可保存多套 Headscale 配置，不同 RDP 连接可绑定不同配置 |

### 1.2 本次交付范围

v1 设计稿仅规划架构、模块划分、tsnet 原生库构建、SOCKS5 代理联动、构建方案与风险对策；2026-11-08 已按本文落地应用代码与 tsnet AAR（未提交），实现差异见上方「实现状态」。

### 1.3 非目标（本期不做）

- 不做 Tailscale Exit Node、Subnet Router、Serve/Funnel 等高级功能
- 不做 MagicDNS（用虚拟 IP 直连，不用域名）
- 不做 ACL 管理（由 Headscale 服务端管理）
- 不做 Tailscale SaaS（仅自托管 Headscale）
- 不做 iOS

---

## 2. 方案选型

### 2.1 为什么选 Tailscale 而非 n2n

| 维度 | n2n | Tailscale (tsnet) |
| --- | --- | --- |
| 协议层级 | 二层（TAP），Android 需 tun2tap 适配 | 三层（TUN），**直接适配** |
| Android 支持 | 无官方，需手写 JNI + tun2tap | 官方 tsnet 库 + gomobile 出 AAR |
| NAT 穿透 | UDP hole punching，对称 NAT 回退 supernode 中继 | DERP 中继 + DISCO，**对称 NAT 也能中继** |
| 控制面 | supernode 仅中继，无设备管理 | Headscale 有 Web UI、ACL、节点管理 |
| 许可证 | GPLv3（传染风险） | BSD-3-Clause（无传染） |
| 集成工作量 | ~17-27 天 | **~7-10 天** |

### 2.2 用户态 SOCKS5 vs VpnService 系统隧道

Tailscale 在 Android 上有两种集成模式：

| 维度 | 模式 A：用户态 SOCKS5（推荐） | 模式 B：VpnService 系统隧道 |
| --- | --- | --- |
| VpnService 权限 | **不需要** | 需要 |
| 流量范围 | 仅 anrdp 应用内 | 整个手机系统 |
| 与其他 VPN 共存 | **可以** | 互斥 |
| RDP 连接方式 | FreeRDP 通过 `/proxy:socks5://` 走代理 | FreeRDP 直连虚拟 IP |
| 系统 VPN 图标 | 无 | 有（状态栏钥匙图标） |
| 实现复杂度 | 低（tsnet 内置 SOCKS5） | 中（需自建 VpnService + TUN 对接） |

**选择模式 A（用户态 SOCKS5）**，理由：
1. 不需要 VpnService 权限，用户体验更轻量
2. 不与系统其他 VPN 冲突（用户可能同时在用其他 VPN）
3. tsnet 内置 SOCKS5 代理服务器，无需自建
4. FreeRDP 原生支持 `/proxy:socks5://` 参数，RDP 连接逻辑改动极小

---

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层 (app)                                                  │
│   MainActivity              连接列表（左下 FAB 打开配置页）     │
│   ConnectionEditActivity    编辑页增加「绑定 Tailscale 配置」   │
│   TailscaleConfigEditActivity  单配置快捷编辑（实际实现）        │
├──────────────────────────────────────────────────────────────┤
│  应用逻辑层 (app)                                             │
│   TailscaleManager          启停 tsnet、轮询状态、SOCKS5 端口   │
│   RdpConnector              连接前检查 tsnet，注入 proxy 参数   │
├──────────────────────────────────────────────────────────────┤
│  数据层 (app)                                                 │
│   Room: TailscaleConfigEntity / TailscaleConfigDao            │
│   ConnectionEntity 增加 tailscaleConfigId 外键                 │
├──────────────────────────────────────────────────────────────┤
│  tsnet 原生层 (app/libs/tsnet.aar，由 gomobile bind 产出)      │
│   TsnetServer (Go) ─ tsnet.Server ─ WireGuard + DERP + SOCKS5 │
│   认证：Headscale pre-auth key                                │
├──────────────────────────────────────────────────────────────┤
│  RDP 引擎层 (freeRDPCore AAR，已有)                            │
│   SessionActivity ─ LibFreeRDP (JNI) ─ libfreerdp-android.so   │
│   通过 /proxy:socks5://localhost:port 连接虚拟 IP              │
└──────────────────────────────────────────────────────────────┘

外部依赖：
  Headscale 服务器（用户自备公网 VPS，端口 443/8080）
  Windows 目标主机安装 Tailscale 客户端，加入同一 tailnet
```

设计原则：

1. **tsnet 完全在应用进程内运行**，不依赖系统 VPN 服务，不申请 VpnService 权限。
2. **RDP 流量经 SOCKS5 代理进入 tsnet**，由 tsnet 的 WireGuard 加密后发往对端节点。
3. **控制面完全自托管**：Headscale 部署在用户自己的公网服务器上，anrdp 不依赖任何第三方 SaaS。

---

## 4. tsnet 原生库构建

### 4.1 为什么需要 Go wrapper

`gomobile bind` 对 Go 类型有严格限制（不支持泛型、部分 interface、channel 等），tsnet.Server 的 API 较复杂，直接 bind 会失败或生成不可用的绑定。因此需要写一个**薄 Go wrapper**，封装 tsnet.Server，暴露 Java/Kotlin 友好的简化 API。

### 4.2 Go wrapper 包设计

新建独立模块 `tsnet-android/`（Go module，不入库到 anrdp，单独构建产出 AAR）：

```go
// tsnet-android/server.go
package tsnetandroid

import (
    "context"
    "fmt"
    "net"

    "tailscale.com/net/socks5"
    "tailscale.com/tsnet"
)

// Server 是对 tsnet.Server 的简化封装，暴露 gomobile 友好的 API。
type Server struct {
    srv *tsnet.Server
}

// NewServer 创建 tsnet 服务器实例。
// hostname: 节点在 tailnet 中的名称
// controlURL: Headscale 服务器地址，如 https://headscale.example.com
// authKey: Headscale 生成的 pre-auth key
// stateDir: 状态文件存储目录（应用内部存储）
func NewServer(hostname, controlURL, authKey, stateDir string) *Server {
    srv := &tsnet.Server{
        Hostname:   hostname,
        ControlURL: controlURL,
        AuthKey:    authKey,
        Dir:        stateDir,
    }
    return &Server{srv: srv}
}

// Start 启动 tsnet（非阻塞）。调用方轮询 Status() 直到 "running"；应在后台线程调用。
// 实际实现额外包含 recover，将 panic 转为 error 返回。
func (s *Server) Start() (err error) {
    defer func() {
        if r := recover(); r != nil {
            err = fmt.Errorf("tsnet panic: %v", r)
        }
    }()
    return s.srv.Start()
}

// Close 停止 tsnet。
func (s *Server) Close() error {
    return s.srv.Close()
}

// StartSOCKS5 在本地启动 SOCKS5 代理，返回监听地址 host:port。
// 实际实现使用 tailscale.com/net/socks5.Server，Dialer 走 tsnet.Server.Dial。
func (s *Server) StartSOCKS5() (string, error) {
    ln, err := net.Listen("tcp", "127.0.0.1:0")
    if err != nil {
        return "", fmt.Errorf("socks5 listen: %w", err)
    }
    s.socksSrv = &socks5.Server{
        Dialer: func(ctx context.Context, network, addr string) (net.Conn, error) {
            return s.srv.Dial(ctx, network, addr)
        },
    }
    go s.socksSrv.Serve(ln)
    return ln.Addr().String(), nil
}

// StopSOCKS5 停止本地 SOCKS5 代理。
func (s *Server) StopSOCKS5() { /* 关闭 listener，清空地址 */ }

// SOCKS5Addr 返回当前监听地址，未启动返回空串。
func (s *Server) SOCKS5Addr() string { /* ... */ }

// Status 返回当前状态字符串："running" / "stopped" / "error"
func (s *Server) Status() string {
    // 通过 LocalClient 查询状态
    lc, err := s.srv.LocalClient()
    if err != nil {
        return "error"
    }
    st, err := lc.Status(context.Background())
    if err != nil {
        return "error"
    }
    if st.BackendState == "Running" {
        return "running"
    }
    return "stopped"
}

// TailnetIP 返回当前节点的 Tailscale 虚拟 IP。
func (s *Server) TailnetIP() string {
    lc, err := s.srv.LocalClient()
    if err != nil {
        return ""
    }
    st, _ := lc.Status(context.Background())
    if st.Self != nil && len(st.Self.TailscaleIPs) > 0 {
        return st.Self.TailscaleIPs[0].String()
    }
    return ""
}
```

> 注：**实际实现未使用 `ServeSOCKS5`**，而是用 `tailscale.com/net/socks5.Server` + `tsnet.Server.Dial`，无需认证，仅监听 `127.0.0.1`。另外 `SetNetworkInfo` 会注册 `netmon.RegisterInterfaceGetter`，在 Go 侧合成 loopback + 注入的移动网络接口；这是 Android 上避免 netmon panic 的关键。
>
> 本段代码仍保留设计形态，完整实现见工作区根 `tsnet-android/server.go`。

### 4.3 gomobile bind 构建命令

```bash
# 1. 安装 Go 与 gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# 2. 构建 AAR（arm64-v8a，与 anrdp 一致；实际使用 androidapi 21）
cd tsnet-android
ANDROID_HOME=/path/to/android-sdk \
gomobile bind -target=android/arm64 \
  -androidapi 21 \
  -o ../anrdp/app/libs/tsnet.aar \
  .
```

产物：`tsnet.aar` + `tsnet-sources.jar`，放入 `anrdp/app/libs/`，与 `freeRDPCore-release.aar` 同级。

> **网络限制**：Go 模块下载需走 socks5 代理 `192.168.56.1:1080`（设置 `GOPROXY` 或 `HTTPS_PROXY`）。

### 4.4 版本锁定

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Tailscale / tsnet | **1.104.0** | 实际构建所用版本（见 `tsnet-android/go.mod`） |
| Go | **1.27.1** | 实际构建所用版本 |
| gomobile | latest | `go install golang.org/x/mobile/cmd/gomobile@latest` |
| Android API | 21 | `gomobile bind -androidapi 21`（应用 minSdk 29） |
| Headscale | 0.29.x | 稳定版，API 兼容 |

---

## 5. 应用层模块设计

### 5.1 数据层

**新增实体** `TailscaleConfigEntity`（表 `tailscale_configs`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 主键，自增 |
| name | String | 配置显示名 |
| controlURL | String | Headscale 服务器 URL，如 `https://headscale.example.com` |
| authKey | String | Headscale pre-auth key（节点注册凭证） |
| hostname | String | 本设备在 tailnet 中的节点名 |
| stateDir | String | tsnet 状态目录；空串时由管理器生成 `filesDir/tailscale-state-<id>` |
| createdAt | Long | 创建时间戳 |

**修改** `ConnectionEntity` 增加字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| tailscaleConfigId | Long | 绑定的 Tailscale 配置 ID，`0` = 不使用 Tailscale（直连） |

**DAO** `TailscaleConfigDao`：`observeAll()`、`getAll()`、`getById()`、`insert`、`update`、`delete`。

数据库版本升至 **v4**：`MIGRATION_2_3` 建表，`MIGRATION_3_4` 给 `connections` 加 `tailscaleConfigId INTEGER NOT NULL DEFAULT 0`。

> 安全说明：authKey 以明文存于 Room，与 RDP 密码策略一致；authKey 本身是一次性/可过期凭证，风险可控。

### 5.2 Tailscale 配置页 `TailscaleConfigEditActivity`（实际实现）

- 入口：主界面左下 FAB；已有配置时打开最近创建的一条并回填，否则新建。
- 表单：名称、Headscale 控制面 URL、pre-auth key、节点名；`stateDir` 自动生成。
- 校验：名称 / 控制面 URL / authKey 非空，错误显示在 `TextInputLayout.error`。
- 保存后 `finish()`；没有配置列表、删除与「测试连通性 / 显示虚拟 IP」入口（属待办）。

### 5.3 连接编辑页改造 `ConnectionEditActivity`

增加一个"Tailscale 配置"下拉选择项：

- 选项：`不使用 Tailscale（直连）` / 已保存的各 Tailscale 配置名称
- 保存到 `ConnectionEntity.tailscaleConfigId`

### 5.4 Tailscale 管理器 `TailscaleManager`

应用层唯一入口，封装对 tsnet AAR 的调用。实际 API（`com.anrdp.tailscale.TailscaleManager`，`object` 单例）：

```kotlin
object TailscaleManager {
    suspend fun start(context: Context, config: TailscaleConfigEntity) // 注入网络信息 + NewServer + Start
    suspend fun startSOCKS5(): String          // 返回 host:port，已启动则复用
    suspend fun stopSOCKS5()
    suspend fun status(): String               // running / starting / stopped / error
    suspend fun tailnetIP(): String            // 未就绪返回 ""
    suspend fun stop()
    fun socks5Address(): String?
    fun isRunning(): Boolean
}
```

关键点：
- 所有 gomobile JNI 调用都是阻塞的，统一 `withContext(Dispatchers.IO)` + `Mutex` 串行化
- 启动前从 `ConnectivityManager` 读取活动网络的接口名 / 地址 / 默认网关，调用 `Tsnetandroid.setNetworkInfo`
- `stateDir` 为空时生成 `filesDir/tailscale-state-<id>`；tsnet 日志写到该目录 `tsnet.log`
- 单实例：同一时间只运行一个 tsnet 节点；SOCKS5 监听 `127.0.0.1:随机端口`，仅本地可用
- 启动失败时 `close()` 清理并附带 `tsnet.log` 尾部抛异常

### 5.5 RDP 连接流程改造 `RdpConnector`

在现有 `connect()` 中增加 Tailscale 代理注入：

```
用户点击连接
  │
  ▼
conn.tailscaleConfigId == 0 ?
  ├─ 是 → 直接走原 freerdp:// URI 流程（无 proxy）
  └─ 否 → TailscaleManager.start(config)
            │
            ▼
         等待状态变为 Running（实际 60s，每 500ms 轮询），获取 socks5Address
            │
            ├─ 成功 → 构建 freerdp:// URI，追加 ?proxy=socks5://localhost:port
            │         host 字段填目标主机的 Tailscale 虚拟 IP
            └─ 失败/超时 → 提示用户，不发起 RDP
```

**URI 映射**（复用现有 `RdpConnector` 的 `?key=value` → `/key:value` 规则）：

```
freerdp://user@100.64.1.5:3389/connect?p=pass&proxy=socks5://127.0.0.1:12345
```

FreeRDP 解析为：
- `/v:100.64.1.5:3389`（目标虚拟 IP）
- `/p:pass`
- `/proxy:socks5://127.0.0.1:12345`（走 tsnet SOCKS5 代理）

FreeRDP 的 SOCKS5 连接进入 tsnet，由 tsnet 的 WireGuard 加密后通过 P2P/DERP 发往目标节点。

---

## 6. Headscale 控制面

### 6.1 部署

Headscale 部署在用户自备的公网 VPS 上（与 n2n supernode 需求相同）：

```bash
# Docker 方式
docker run -d \
  --name headscale \
  -p 443:443 -p 8080:8080 \
  -v headscale-data:/var/headscale \
  headscale/headscale:0.29 serve
```

需要配置：
- `server_url`: 公网可达 URL
- TLS 证书（Let's Encrypt 或自签）
- DERP 服务器（可自建或用公共 DERP）

### 6.2 节点注册（pre-auth key）

Headscale 管理员生成 pre-auth key：

```bash
headscale preauthkeys create \
  --user anrdp-user \
  --reusable \
  --expiration 720h
```

生成的 key 填入 anrdp 的 Tailscale 配置中。tsnet 启动时用此 key 自动注册节点，无需交互式登录。

### 6.3 目标主机接入

Windows 目标主机安装 Tailscale 客户端，登录同一 Headscale 服务器（用同一 pre-auth key 或交互式登录），获得虚拟 IP（如 `100.64.1.5`）。anrdp 的 RDP 连接 `host` 字段填此虚拟 IP。

---

## 7. 权限与 Manifest

### 7.1 权限

**无需新增 VpnService 权限**（用户态模式）。仅需已有权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />              <!-- 已有 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />   <!-- 已有 -->
```

tsnet 运行在应用进程内，通过普通 socket 通信，不需要任何特殊权限。

### 7.2 前台服务（**当前未实现**）

tsnet 启动后是长连接。当前实现直接运行在应用进程内，**没有前台 Service**；应用退到后台较久时进程可能被系统回收，RDP 随之中断。

若后续实现，需增加：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".tailscale.TailscaleService"
    android:exported="false"
    android:foregroundServiceType="specialUse" />
```

> 注：当前 anrdp Manifest 中 `POST_NOTIFICATIONS` 被 `tools:node="remove"` 移除；启用前台服务时需加回，并同步更新 README 权限表。

---

## 8. 构建方案

### 8.1 app/build.gradle 变更

```groovy
dependencies {
    // 现有：freeRDPCore AAR
    implementation files('libs/freeRDPCore-release.aar')

    // 新增：tsnet AAR（手工 gomobile bind 产出，仓库已附带）
    implementation files('libs/tsnet.aar')

    // ... 其他依赖不变
}
```

### 8.2 tsnet AAR 构建（**无仓库内脚本**）

设计中的 `scripts/build-tsnet-aar.sh` 未实现。实际构建手工执行：

```bash
cd tsnet-android
go mod download                    # 需代理
gomobile bind -target=android/arm64 -androidapi=21 \
  -o ../anrdp/app/libs/tsnet.aar .
cp tsnet-sources.jar ../anrdp/app/libs/   # 若在同一目录生成
```

构建依赖 Go 1.27.1 + gomobile + Android SDK/NDK；`tsnet-android/` 不在 anrdp 仓库中，也没有 `TSNET_VERSION` 变量（版本由 `go.mod` 锁定为 v1.104.0）。

### 8.3 与 freeRDPCore 的共存

- freeRDPCore 是预编译 AAR，含 `libfreerdp-android.so`
- tsnet 是预编译 AAR，含 `libgojni.so`（gomobile 生成的 Go 运行时）
- 两者独立，无符号冲突（FreeRDP 是 C/C++，tsnet 是 Go）
- APK 内同时包含两套原生库

---

## 9. 主要风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| **gomobile bind 兼容性** | tsnet API 复杂导致 bind 失败或 AAR 体积过大 | 已用薄 Go wrapper 封装，只暴露必要 API；实际 AAR 15MB |
| **tsnet AAR 体积** | APK 增大 15-25MB | 实测 `libgojni.so` 30.5MB，release APK ≈46MB；仅 arm64-v8a |
| **FreeRDP SOCKS5 认证** | tsnet SOCKS5 可能需要认证 | 实际用 `socks5.Server` 无认证；若需认证，FreeRDP 支持 `socks5://user:pass@host:port` |
| **tsnet 后台被杀** | RDP 连接中断 | **尚未实现前台 Service**；RDP 连接期间尽量保持应用前台（待补 R11） |
| **Android netlink 权限 / netmon panic** | 启动时 native panic，进程被杀 | 已实现：`ConnectivityManager` 注入网络信息 + `netmon.RegisterInterfaceGetter` + Go `recover` |
| **pre-auth key 泄露** | 他人可注册节点加入 tailnet | key 可设置过期时间、单用途；存储在应用私有目录 |
| **Headscale 服务器不可达** | 无法注册节点 | 连接失败提示用户检查 controlUrl；支持配置多个 Headscale URL |
| **P2P 打洞失败** | RDP 走 DERP 中继，延迟高 | 属正常降级；DERP 可自建降低延迟 |
| **Go 工具链/网络** | gomobile 初始化、Go 模块下载失败 | 走 socks5 代理；或在 CI 环境预构建 AAR 提交到 libs/ |

---

## 10. 许可合规

- **tsnet / Tailscale 客户端**：BSD-3-Clause，可自由嵌入，无传染风险。
- **Headscale**：BSD-3-Clause，自托管无限制。
- **WireGuard**：GPL-2.0（内核模块）/ MIT（go 实现 wireguard-go）。tsnet 用的是 **wireguard-go**（MIT），无传染。
- **gomobile 生成物**：BSD 风格许可证。

整体许可证对 anrdp 友好，无 GPL 传染风险。**2026-11-08 已在 `THIRD_PARTY_NOTICES.md` 补充 Tailscale/tsnet（BSD-3-Clause）、wireguard-go（MIT）、gVisor（Apache-2.0）、Go 运行时与 gomobile（BSD-3-Clause）的声明**；`SOURCE.md` 同时补充了组件源码地址。

---

## 11. 里程碑（建议，不含时间估算）

1. ✅ **tsnet AAR 构建**：Go wrapper + `gomobile bind` 产出 `tsnet.aar`（2026-11-08）
2. 🟡 **tsnet 启停验证**：应用内可启动并注册到 Headscale；**真机 tailnet 端到端待验证**
3. ✅ **SOCKS5 代理**：`StartSOCKS5` 已实现并由 RDP 连接使用
4. 🟡 **RDP 联动**：`/proxy:socks5://` 注入已完成；待真机验证「启 tsnet → RDP 连虚拟 IP」
5. ✅ **UI 与数据层**：`TailscaleConfigEditActivity`、`tailscaleConfigId`、编辑页下拉（单配置版）
6. ❌ **前台服务与稳定性**：未实现；异常恢复已有 recover + 日志
7. 🟡 **收尾**：APK 体积已评估（≈46MB）；许可文档与权限声明已更新；**改动尚未提交**

---

## 12. 与 n2n 方案的对比总结

| 维度 | n2n 方案 | Tailscale 方案 |
| --- | --- | --- |
| 原生库构建 | CMake + NDK 编译 n2n C 源码 | gomobile bind 出 AAR（类似 freeRDPCore） |
| tun2tap | **必须实现**（uip + ARP 模拟） | **不需要**（WireGuard 三层） |
| JNI | 手写 ~800 行 C | 无（gomobile 自动生成） |
| VpnService | 需要（TUN fd 传递） | **不需要**（用户态 SOCKS5） |
| 控制面 | supernode（仅中继） | Headscale（设备管理 + ACL） |
| RDP 接入 | host 填虚拟 IP，直连 | host 填虚拟 IP + `/proxy:socks5://` |
| 集成工作量 | ~17-27 天 | ~7-10 天 |
| 许可证风险 | GPLv3 传染 | BSD-3 无风险 |
| 打洞成功率 | 中（对称 NAT 回退中继） | 高（DERP 中继 + 更好的打洞） |

---

## 13. 附录：参考资料

| 资料 | 来源 | 用途 |
| --- | --- | --- |
| tsnet 官方文档 | https://tailscale.com/docs/features/tsnet | tsnet.Server API、用户态网络栈 |
| tsnet.Server API 参考 | https://tailscale.com/docs/reference/tsnet-server-api | Start/Close/Listen/ServeSOCKS5 等方法 |
| 用户态网络模式 | https://tailscale.com/kb/1112/userspace-networking/ | SOCKS5 代理配置 |
| Headscale 文档 | https://headscale.net/ | 部署、pre-auth key、注册 |
| FreeRDP 代理参数 | `xfreerdp /proxy:socks5://user:pass@host:port` | RDP 走 SOCKS5 代理 |
| gomobile bind | `gomobile bind -target=android` | Go 库编译为 AAR |
| TailSocks（参考） | https://github.com/.../tailsocks | Android 上用 tsnet 用户态模式的参考实现 |
