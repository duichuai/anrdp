# AnRDP 集成 Tailscale 设计文档

> 版本：v1 设计稿
> 状态：设计方案（本次交付不含代码实现）
> 关系：替代 `n2n-integration-design.md` 方案。利用 Tailscale（WireGuard）的成熟生态与 tsnet 嵌入库，在 anrdp 应用内实现 P2P 组网，使 RDP 可跨网络连接虚拟网段内的主机。

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

**仅输出设计方案**：架构、模块划分、tsnet 原生库构建、SOCKS5 代理联动、构建方案、风险对策。**不编写应用代码、不产出 APK。**

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
│   MainActivity              连接列表（含 Tailscale 状态指示）   │
│   ConnectionEditActivity    编辑页增加「绑定 Tailscale 配置」   │
│   TailscaleConfigActivity   Tailscale 配置管理（增删改查）      │
├──────────────────────────────────────────────────────────────┤
│  应用逻辑层 (app)                                             │
│   TailscaleManager          启停 tsnet、状态订阅、SOCKS5 端口   │
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

// Start 启动 tsnet，阻塞直到就绪或出错。应在后台线程调用。
func (s *Server) Start() error {
    return s.srv.Start()
}

// Close 停止 tsnet。
func (s *Server) Close() error {
    return s.srv.Close()
}

// SOCKS5Listener 在本地启动 SOCKS5 代理，返回监听地址 host:port。
func (s *Server) SOCKS5Listener() (string, error) {
    ln, err := s.srv.Listen("tcp", "127.0.0.1:0")
    if err != nil {
        return "", err
    }
    // 启动 SOCKS5 代理服务（tsnet 提供）
    go s.srv.ServeSOCKS5(ln, "")
    return ln.Addr().String(), nil
}

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

> 注：`tsnet.Server` 的 `ServeSOCKS5` 方法在 tsnet 中提供，认证用户名固定为 `tsnet`，密码为空（无需密码）。若需要密码可设置 `Server.ProxyCred`。

### 4.3 gomobile bind 构建命令

```bash
# 1. 安装 Go 与 gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# 2. 构建 AAR（arm64-v8a，与 anrdp 一致）
cd tsnet-android
ANDROID_HOME=/path/to/android-sdk \
gomobile bind -target=android/arm64 \
  -androidapi 29 \
  -o ../anrdp/app/libs/tsnet.aar \
  .
```

产物：`tsnet.aar` + `tsnet-sources.jar`，放入 `anrdp/app/libs/`，与 `freeRDPCore-release.aar` 同级。

> **网络限制**：Go 模块下载需走 socks5 代理 `192.168.56.1:1080`（设置 `GOPROXY` 或 `HTTPS_PROXY`）。

### 4.4 版本锁定

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Tailscale / tsnet | 1.98.x（稳定版） | 随 Headscale 版本选择兼容版本 |
| Go | 1.22+ | gomobile 要求 |
| gomobile | latest | `go install golang.org/x/mobile/cmd/gomobile@latest` |
| Headscale | 0.29.x | 稳定版，API 兼容 |

---

## 5. 应用层模块设计

### 5.1 数据层

**新增实体** `TailscaleConfigEntity`（表 `tailscale_configs`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 主键，自增 |
| name | String | 配置显示名 |
| controlUrl | String | Headscale 服务器 URL，如 `https://headscale.example.com` |
| authKey | String | Headscale pre-auth key（节点注册凭证） |
| hostname | String | 本设备在 tailnet 中的节点名 |
| createdAt | Long | 创建时间戳 |

**修改** `ConnectionEntity` 增加字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| tailscaleConfigId | Long | 绑定的 Tailscale 配置 ID，`0` = 不使用 Tailscale（直连） |

**DAO** `TailscaleConfigDao`：`observeAll()`、`getById()`、`insert`、`update`、`delete`。

> 安全说明：authKey 以明文存于 Room，与 RDP 密码策略一致；authKey 本身是一次性/可过期凭证，风险可控。

### 5.2 Tailscale 配置管理页 `TailscaleConfigActivity`

- 列表展示已保存的 Tailscale 配置（名称 / controlUrl / hostname）
- 新建/编辑表单：名称、Headscale URL、auth key、节点名
- 校验：controlUrl 非空且为合法 URL、authKey 非空、hostname 非空
- 点击配置 → 启动 tsnet（测试连通性），显示虚拟 IP

### 5.3 连接编辑页改造 `ConnectionEditActivity`

增加一个"Tailscale 配置"下拉选择项：

- 选项：`不使用 Tailscale（直连）` / 已保存的各 Tailscale 配置名称
- 保存到 `ConnectionEntity.tailscaleConfigId`

### 5.4 Tailscale 管理器 `TailscaleManager`

应用层唯一入口，封装对 tsnet AAR 的调用：

```kotlin
object TailscaleManager {
    private var server: TsnetServer? = null
    private var socks5Address: String? = null
    private val _status = MutableStateFlow<TailscaleStatus>(TailscaleStatus.Stopped)
    val status: StateFlow<TailscaleStatus> = _status

    /** 启动 tsnet，返回 SOCKS5 代理地址（host:port） */
    suspend fun start(context: Context, config: TailscaleConfigEntity): Result<String>

    /** 停止 tsnet */
    suspend fun stop()

    /** 获取当前 SOCKS5 代理地址，未启动返回 null */
    fun socks5Address(): String?
}

enum class TailscaleStatus { Stopped, Starting, Running, Error }
```

关键点：
- `TsnetServer` 是 gomobile 生成的 Java 类，对应 Go wrapper 的 `Server`
- `Start()` 是阻塞调用，必须在后台线程（Coroutine）执行
- SOCKS5 代理监听在 `127.0.0.1:随机端口`，只接受本地连接
- 状态通过 `Server.Status()` 轮询或回调更新

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
         等待状态变为 Running（超时 15s），获取 socks5Address
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

### 7.2 前台服务（可选但推荐）

tsnet 启动后是长连接，为防止系统杀后台，建议用前台 Service 承载：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".tailscale.TailscaleService"
    android:exported="false"
    android:foregroundServiceType="specialUse" />
```

> 注：当前 anrdp Manifest 中 `POST_NOTIFICATIONS` 被 `tools:node="remove"` 移除，需加回。

---

## 8. 构建方案

### 8.1 app/build.gradle 变更

```groovy
dependencies {
    // 现有：freeRDPCore AAR
    implementation files('libs/freeRDPCore-release.aar')

    // 新增：tsnet AAR（由 scripts/build-tsnet-aar.sh 产出）
    implementation files('libs/tsnet.aar')

    // ... 其他依赖不变
}
```

### 8.2 tsnet AAR 构建脚本

新增 `scripts/build-tsnet-aar.sh`：

```bash
#!/bin/bash
# 构建 tsnet-android AAR，产出到 app/libs/tsnet.aar
set -e

TSNET_VERSION=1.98.1
ANDROID_API=29
ABI=arm64

export GOPROXY=https://proxy.golang.org,direct
# 网络受限时用代理：export HTTPS_PROXY=socks5://192.168.56.1:1080

# 拉取 tsnet-android wrapper 源码
if [ ! -d tsnet-android ]; then
    git clone https://github.com/your-org/tsnet-android.git
fi
cd tsnet-android
go mod download

# 构建 AAR
gomobile bind \
    -target=android/$ABI \
    -androidapi $ANDROID_API \
    -o ../anrdp/app/libs/tsnet.aar \
    .
```

### 8.3 与 freeRDPCore 的共存

- freeRDPCore 是预编译 AAR，含 `libfreerdp-android.so`
- tsnet 是预编译 AAR，含 `libgojni.so`（gomobile 生成的 Go 运行时）
- 两者独立，无符号冲突（FreeRDP 是 C/C++，tsnet 是 Go）
- APK 内同时包含两套原生库

---

## 9. 主要风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| **gomobile bind 兼容性** | tsnet API 复杂导致 bind 失败或 AAR 体积过大 | 用薄 Go wrapper 封装，只暴露必要 API；AAR 预计 15-25MB |
| **tsnet AAR 体积** | APK 增大 15-25MB | 仅打包 arm64-v8a；可接受（freeRDPCore 已 29MB） |
| **FreeRDP SOCKS5 认证** | tsnet SOCKS5 可能需要认证 | tsnet 的 ServeSOCKS5 第二参数传空字符串表示无认证；若需认证，FreeRDP 支持 `socks5://user:pass@host:port` |
| **tsnet 后台被杀** | RDP 连接中断 | 前台 Service + START_STICKY；RDP 连接期间保持 tsnet 运行 |
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

整体许可证对 anrdp 友好，无 GPL 传染风险。需在 `THIRD_PARTY_NOTICES.md` 中补充 Tailscale、wireguard-go、gomobile 的许可证声明。

---

## 11. 里程碑（建议，不含时间估算）

1. **tsnet AAR 构建**：编写 Go wrapper，用 gomobile bind 产出 `tsnet.aar`，验证基本 API 可调用
2. **tsnet 启停验证**：在 anrdp 中调用 TsnetServer.Start()，用 pre-auth key 注册到 Headscale，获取虚拟 IP
3. **SOCKS5 代理验证**：启动 tsnet SOCKS5，用其他工具（如 curl）通过代理访问 tailnet 内主机
4. **RDP 联动**：RdpConnector 注入 `/proxy:socks5://`，端到端验证"启 tsnet → RDP 连虚拟 IP"
5. **UI 与数据层**：TailscaleConfigActivity、ConnectionEntity 增加 tailscaleConfigId、编辑页下拉选择
6. **前台服务与稳定性**：TailscaleService 前台化、异常恢复、状态展示
7. **收尾**：APK 体积评估、许可文档、权限声明

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
