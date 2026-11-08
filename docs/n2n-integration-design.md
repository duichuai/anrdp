# PeerRDP 集成 n2n 客户端 — 设计文档

> 版本：v1 设计稿
> 状态：**未采用**（保留作方案对比与历史记录）
> 关系：在 `peerrdp` 现有 RDP 客户端基础上，集成 n2n P2P VPN 能力，使手机可跨网络加入虚拟局域网后再发起 RDP 连接。
>
> **说明**：项目已改用 Tailscale（tsnet）方案，并于 2026-11-08 完成实现，见 `docs/tailscale-integration-design.md`。本文不再实施。

---

## 1. 目标与范围

### 1.1 产品目标

为 PeerRDP 增加 **n2n 边缘节点（edge）** 能力：用户无需公网 IP，通过 n2n 的 supernode 协调 + P2P 打洞，将 Android 设备与目标 Windows 主机拉入同一虚拟局域网（community），再用 RDP 连接虚拟网段内的主机地址。

| 编号 | 需求 | 说明 |
| --- | --- | --- |
| N1 | n2n edge 连接管理 | 配置 supernode、community、加密密钥、虚拟 IP，启动/停止 edge，显示连接状态 |
| N2 | VpnService 接管 TUN | 通过 Android `VpnService` 创建 TUN 接口，把 fd 交给 n2n 原生层收发数据包 |
| N3 | 与 RDP 联动 | 连接某个 RDP 主机前，若该连接绑定了 n2n 配置，则自动拉起 n2n，确认虚拟网络就绪后再发起 RDP |
| N4 | 多套 n2n 配置 | 可保存多套 supernode/community 配置，不同 RDP 连接可绑定不同 n2n 配置 |

### 1.2 本次交付范围

**仅输出设计方案**：架构、模块划分、JNI 接口约定、tun2tap 策略、构建方案、风险对策。**不编写应用代码、不产出 APK。**

### 1.3 非目标（本期不做）

- 不内置 supernode（用户自备公网 supernode 或使用 ntop 公共 supernode）
- 不做 n2n 流量的路由/防火墙规则自定义（默认只路由虚拟网段）
- 不做 IPv6 虚拟网络（仅 IPv4）
- 不做 n2n 多 community 同时在线（同一时间只运行一个 edge 实例）
- 不做 supernode 联邦、自动 IP 分配（用户手动指定虚拟 IP）

---

## 2. n2n 技术背景与 Android 集成方案调研

### 2.1 n2n 核心概念

n2n 是 ntop 开发的 **二层 P2P VPN**，核心角色：

| 角色 | 职责 |
| --- | --- |
| **Supernode** | 公网可达的中心协调节点，负责 edge 注册、发现、对称 NAT 时中继数据包 |
| **Edge 节点** | 加入虚拟网络的终端设备，尝试 P2P UDP 直连，失败时走 supernode 中继 |
| **Community** | 多个 edge 共享的虚拟局域网，用加密密钥保护，同一 supernode 可承载多个 community |

n2n 工作在 **二层（TAP）**，edge 节点之间交换以太网帧，支持 ARP、广播、组播。默认 AES 加密，supernode 无法解密 edge 间 payload。

### 2.2 Android 集成的核心障碍：TUN vs TAP

Android 的 `VpnService` 只能创建 **TUN** 接口（网络层，只收发 IP 包），而 n2n 需要 **TAP** 接口（数据链路层，收发以太网帧）。两者不匹配，需要适配层。

| 维度 | TUN（Android VpnService） | TAP（n2n 需要） |
| --- | --- | --- |
| 层级 | 三层（IP 包） | 二层（以太网帧） |
| 帧头 | 无 | 14 字节以太网头（目的 MAC + 源 MAC + 类型） |
| ARP | 不支持 | 必须支持 |
| 广播/组播 | 不支持 | 需要支持 |

### 2.3 已有 Android n2n 方案对比

调研了互联网上三个有代表性的方案：

#### 方案 A：hin2n（switch-iot/n2n_vLTS）

- **协议版本**：n2n v2s（meyerd 分支，**不兼容** ntop 官方 v2/v3）
- **tun2tap 方案**：引入 `uip` 轻量 TCP/IP 栈，在 TUN 之上模拟以太网帧头与 ARP 协议
- **JNI 层**：`edge_jni.c` + `edge.c`，通过 `EdgeCmd` 结构体传参，核心字段 `vpnFd`（VpnService 的 TUN fd）
- **构建**：Gradle 2.14.1，CMake，`minSdk 15`
- **许可**：GPLv3
- **评价**：最成熟的 Android n2n 实现，tun2tap 架构经过验证；但协议版本老旧（v2s），工具链过时，无法直接对接现代 n2n v3 supernode

#### 方案 B：NakanoMiku13/n2n-android

- **协议版本**：n2n v3（基于 ntop/n2n fork）
- **Android 适配**：TUN device integration for VpnService、Socket callback for VPN protection、Logcat
- **对外 API**：`n2n_android.h`（P/Invoke 兼容，被 .NET MAUI 的 N2nBindings 消费）
- **评价**：基于主线 n2n v3，方向正确；但社区小、文档少，Android 适配代码分散，需深入源码才能复用

#### 方案 C：n2n 官方 v3 嵌入式 API + 自适配

- **协议版本**：n2n v3（ntop/n2n dev 分支）
- **官方嵌入式 API**（`examples/example_edge_embed.c`、`example_edge_embed_quick_edge_init.c`）：
  ```c
  // 简易入口（阻塞）
  quick_edge_init(device_name, network_name, secret_key, mac, ip, supernode, &keep_running);

  // 完整流程
  edge_init_conf_defaults(&conf);
  edge_conf_add_supernode(&conf, "host:port");
  conf.community_name = "mycommunity";
  conf.encrypt_key = "mysecret";
  tuntap_open(&tuntap, "edge0", "static", ip, netmask, mac, mtu, metric);
  eee = edge_init(&conf, &rc);
  run_edge_loop(eee);   // 阻塞，需独立线程
  ```
- **tun2tap**：官方无 Android tuntap 实现，需自写 `tuntap_android.c`
- **评价**：紧跟主线、协议最新；但 tun2tap 适配工作量最大

### 2.4 方案选择

**推荐方案：以 n2n 官方 v3 源码为基础，移植 hin2n 的 tun2tap + uip 架构，用现代 CMake 构建。**

理由：

| 维度 | 选择 | 理由 |
| --- | --- | --- |
| 协议版本 | n2n v3 官方 | 当前主线，兼容最新 supernode 与社区生态 |
| tun2tap | 移植 hin2n 的 uip 方案 | 经过真机验证的成熟方案，避免重新发明 ARP/以太网帧模拟 |
| 构建 | CMake + NDK（与 freeRDPCore 一致） | peerrdp 已有 NDK/CMake 工具链，复用即可 |
| 嵌入式 API | n2n v3 官方 `edge_init` 系列 | 比 v2s 的 `edge_jni` 更清晰，便于维护 |

不选 hin2n 直接移植的原因：v2s 协议与 ntop v3 不互通，若用户的 Windows 主机跑官方 n2n v3 edge，则无法联通。

不选 NakanoMiku13/n2n-android 的原因：文档与代码组织不透明，复用成本与自行适配相当，且可控性差。

---

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层 (app)                                                  │
│   MainActivity              连接列表（含 n2n 状态指示）         │
│   ConnectionEditActivity    编辑页增加「绑定 n2n 配置」          │
│   N2nConfigActivity         n2n 配置管理（增删改查）            │
│   N2nStatusFragment         连接中状态展示/断开                 │
├──────────────────────────────────────────────────────────────┤
│  应用逻辑层 (app)                                             │
│   N2nManager                启停 n2n、状态订阅、配置绑定         │
│   RdpConnector              连接前检查 n2n，就绪后再发 RDP      │
│   N2nVpnService             VpnService 实现，持有 TUN fd        │
├──────────────────────────────────────────────────────────────┤
│  数据层 (app)                                                 │
│   Room: N2nConfigEntity / N2nConfigDao                        │
│   ConnectionEntity 增加 n2nConfigId 外键                       │
├──────────────────────────────────────────────────────────────┤
│  JNI 层 (app/src/main/cpp/n2n/)                               │
│   n2n_jni.c                 JNI 入口，N2nEdge 生命周期          │
│   tuntap_android.c          TUN fd ↔ TAP 适配（tun2tap+uip）   │
│   n2n 官方源码（v3）         edge.c / n2n.c / wire.c / crypto  │
├──────────────────────────────────────────────────────────────┤
│  RDP 引擎层 (freeRDPCore AAR，已有)                            │
│   SessionActivity ─ LibFreeRDP (JNI) ─ libfreerdp-android.so   │
└──────────────────────────────────────────────────────────────┘
```

设计原则：

1. **n2n 与 RDP 解耦**：n2n 负责"把目标主机拉进虚拟网段"，RDP 连接逻辑不变（host 填虚拟 IP 即可）。
2. **VpnService 是 n2n 的唯一载体**：TUN 接口、edge 生命周期都在 `N2nVpnService` 内，避免多进程/多服务复杂化。
3. **tun2tap 全部在 native 完成**：Java 层只负责把 TUN fd 传下去 + 控制启停，数据包处理不走 JNI 来回，保证性能。

---

## 4. 模块设计

### 4.1 数据层

**新增实体** `N2nConfigEntity`（表 `n2n_configs`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 主键，自增 |
| name | String | 配置显示名 |
| supernode | String | 主 supernode，格式 `host:port` |
| supernodeBackup | String | 备 supernode（可空） |
| community | String | community 名称（≤15 字符） |
| encryptKey | String | 加密密钥（可空，空=不加密） |
| ipAddress | String | 虚拟 IPv4 地址，如 `192.168.100.2` |
| netmask | String | 子网掩码，如 `255.255.255.0` |
| macAddress | String | 虚拟 MAC，空则自动生成 |
| mtu | Int | MTU，默认 1400 |
| localPort | Int | 本地 UDP 端口，0=随机 |
| allowRouting | Boolean | 是否允许路由转发，默认 false |
| dropMulticast | Boolean | 是否丢弃组播，默认 false |
| traceLevel | Int | 日志级别 0-4，默认 1 |
| createdAt | Long | 创建时间戳 |

**修改** `ConnectionEntity` 增加字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| n2nConfigId | Long | 绑定的 n2n 配置 ID，`0` = 不使用 n2n（直连） |

**DAO** `N2nConfigDao`：`observeAll()`、`getById()`、`insert`、`update`、`delete`。

> 安全说明：community 加密密钥以明文存于 Room，与 RDP 密码策略一致；后续可统一接入 `androidx.security.crypto`。

### 4.2 n2n 配置管理页 `N2nConfigActivity`

- 列表展示已保存的 n2n 配置（名称 / supernode / community）
- 新建/编辑表单：名称、supernode、备 supernode、community、密钥、虚拟 IP、掩码、MAC、MTU
- 校验：supernode `host:port` 格式、IP/掩码合法性、community 非空且 ≤15 字符
- 点击配置 → 直接启动 n2n（用于测试连通性）

### 4.3 连接编辑页改造 `ConnectionEditActivity`

增加一个"n2n 配置"下拉选择项：

- 选项：`不使用 n2n（直连）` / 已保存的各 n2n 配置名称
- 保存到 `ConnectionEntity.n2nConfigId`

### 4.4 n2n 运行管理 `N2nVpnService`（继承 `VpnService`）

职责：

1. **配置 TUN 接口**：`VpnService.Builder` 设置 MTU、虚拟 IP、路由（仅虚拟网段），`establish()` 得到 `ParcelFileDescriptor`
2. **传递 fd 给 native**：`detachFd()` 把 TUN fd 写入 `N2nEdgeConfig.vpnFd`，调用 `nativeStartEdge(config)`
3. **保护 n2n socket**：native 层创建 UDP socket 后，通过 JNI 回调 `VpnService.protect(fd)`，防止 n2n 自身流量被路由进 TUN 形成回环
4. **生命周期**：`onStartCommand` 启动 edge，`onRevoke` / `onDestroy` 调用 `nativeStopEdge()` 并关闭 TUN fd
5. **前台服务**：Android 8+ 必须为前台服务，显示持续通知（"n2n 已连接 · community 名"）

关键代码骨架：

```kotlin
class N2nVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = intent?.getParcelableExtra<N2nConfigEntity>(EXTRA_CONFIG) ?: return START_NOT_STICKY

        // 1. 建立 TUN 接口
        val builder = Builder()
            .setMtu(config.mtu)
            .addAddress(config.ipAddress, prefixLength(config.netmask))
            .addRoute(networkAddress(config.ipAddress, config.netmask), prefixLength(config.netmask))
            .setSession("PeerRDP n2n · ${config.community}")
        tunFd = builder.establish() ?: run { stopSelf(); return START_NOT_STICKY }

        // 2. 启动 n2n edge（native 层，独立线程）
        val edgeConfig = N2nEdgeConfig(
            vpnFd = tunFd!!.detachFd(),
            supernode = config.supernode,
            supernodeBackup = config.supernodeBackup,
            community = config.community,
            encryptKey = config.encryptKey,
            ipAddress = config.ipAddress,
            netmask = config.netmask,
            macAddress = config.macAddress.ifEmpty { randomMac() },
            mtu = config.mtu,
            localPort = config.localPort,
            allowRouting = config.allowRouting,
            dropMulticast = config.dropMulticast,
            traceLevel = config.traceLevel,
            socketProtector = { fd -> protect(fd) }  // 回调保护 socket
        )
        nativeStartEdge(edgeConfig)

        // 3. 前台通知
        startForeground(NOTIF_ID, buildNotification(config))
        return START_STICKY
    }

    private external fun nativeStartEdge(config: N2nEdgeConfig): Boolean
    private external fun nativeStopEdge()
    private external fun nativeGetEdgeStatus(): N2nEdgeStatus
}
```

### 4.5 n2n 管理器 `N2nManager`

应用层唯一入口，封装对 `N2nVpnService` 的绑定与控制：

```kotlin
object N2nManager {
    suspend fun start(context: Context, config: N2nConfigEntity): Result<Unit>
    suspend fun stop(context: Context)
    fun statusFlow(): Flow<N2nEdgeStatus>   // RUNNING / CONNECTING / STOPPED / ERROR
    fun isRunning(): Boolean
}
```

状态通过 `BroadcastReceiver` 或 `SharedFlow` 向 UI 推送（RUNNING、CONNECTING、ERROR、STOPPED）。

### 4.6 RDP 连接流程改造 `RdpConnector`

在现有 `connect()` 之前增加 n2n 检查：

```
用户点击连接
  │
  ▼
conn.n2nConfigId == 0 ?
  ├─ 是 → 直接走原 freerdp:// URI 流程
  └─ 否 → N2nManager.start(config)
            │
            ▼
         等待状态变为 RUNNING（超时 15s）
            │
            ├─ 成功 → 用 conn.host（虚拟 IP）发起 RDP
            └─ 失败/超时 → 提示用户，不发起 RDP
```

RDP 连接的 `host` 字段在绑定 n2n 时应填 **虚拟网段内的目标主机 IP**（如 `192.168.100.1`），而非公网地址。

---

## 5. JNI / 原生层设计

### 5.1 目录结构

```
app/src/main/cpp/n2n/
├── CMakeLists.txt              # n2n 原生库构建
├── include/
│   ├── n2n_jni.h               # JNI 接口声明
│   └── tuntap_android.h        # Android TUN/TAP 适配
├── n2n_jni.c                   # JNI 入口：startEdge/stopEdge/getStatus
├── tuntap_android.c            # tun2tap 实现（移植 hin2n）
├── uip/                        # uip TCP/IP 栈（移植 hin2n）
│   ├── uip.c
│   ├── uip_arp.c
│   └── uip.h
├── tun2tap/
│   └── tun2tap.c               # TUN↔TAP 帧转换
└── upstream/                   # n2n v3 官方源码（不入库，构建期拉取）
    ├── src/                    # edge.c / n2n.c / wire.c / crypto / ...
    └── include/
```

### 5.2 JNI 接口

Java 侧 `N2nVpnService` 声明的 native 方法对应 JNI 实现：

| Java 方法 | JNI 函数 | 作用 |
| --- | --- | --- |
| `nativeStartEdge(N2nEdgeConfig)` | `Java_com_peerrdp_n2n_N2nVpnService_nativeStartEdge` | 配置 n2n edge 并启动主循环（新线程） |
| `nativeStopEdge()` | `..._nativeStopEdge` | 设置 `keep_running=false`，等待 edge 线程退出 |
| `nativeGetEdgeStatus()` | `..._nativeGetEdgeStatus` | 返回当前 edge 状态 |

**N2nEdgeConfig**（Java → C 结构体映射）：

```c
typedef struct {
    int         vpnFd;              // TUN 文件描述符（detachFd 所得）
    const char *supernode;          // host:port
    const char *supernodeBackup;    // 可空
    const char *community;
    const char *encryptKey;         // 可空
    const char *ipAddress;
    const char *netmask;
    const char *macAddress;
    int         mtu;
    int         localPort;
    int         allowRouting;
    int         dropMulticast;
    int         traceLevel;
    // socket 保护回调：调用 Java VpnService.protect(fd)
    jobject     socketProtector;
    jmethodID   protectMethodId;
} n2n_edge_config_android_t;
```

### 5.3 tun2tap 数据通路

这是整个集成的技术核心。基于 hin2n 的方案，在 native 层用 uip 栈完成三层↔二层转换：

```
方向 1：Android 网络栈 → n2n（出方向）
  TUN 接口读出 IP 包
    → tun2tap 封装为以太网帧（补 14 字节头：目的MAC=广播/peer MAC、源MAC=本机虚拟MAC、类型=0x0800）
    → 交给 n2n edge 处理（加密、发往 peer/supernode）

方向 2：n2n → Android 网络栈（入方向）
  n2n edge 收到对端以太网帧
    → 若是 IP 包（类型 0x0800），剥掉以太网头
    → 写入 TUN fd（Android 网络栈接管）
    → 若是 ARP 包（类型 0x0806），uip_arp 处理或直接广播给所有 peer
```

**关键实现细节**：

- n2n edge 的 `tuntap_dev` 结构体内存中持有 TUN fd，`tuntap_read`/`tuntap_write` 改为直接操作该 fd（而非 `/dev/net/tun`）
- n2n edge 内部仍按 TAP 逻辑处理以太网帧，tun2tap 对 n2n 透明
- ARP 请求：uip 栈维护虚拟 MAC↔IP 映射表；收到 ARP 请求时若目标 IP 是本机则回 ARP 响应，否则通过 n2n 广播给 community 内其他 edge
- 广播/组播：n2n 支持在 community 内广播，tun2tap 透传即可

### 5.4 Socket 保护

n2n edge 创建 UDP socket 后，必须调用 `VpnService.protect(fd)`，否则该 socket 的流量会被路由进 TUN 接口，形成死循环。

实现方式：native 层在创建 socket 后，通过 JNI 回调 Java 的 `socketProtector`（本质是调用 `VpnService.protect(int)`）。

### 5.5 线程模型

- `nativeStartEdge` 内部创建一个新线程运行 `run_edge_loop(eee)`（n2n 官方 API 阻塞）
- `keep_running` 标志位由 `nativeStopEdge` 设置为 false，edge 主循环检测到后退出
- 状态查询通过原子变量，避免锁竞争

---

## 6. 构建方案

### 6.1 原生依赖

n2n v3 依赖（来自官方 `CMakeLists.txt` / `configure`）：

| 依赖 | 用途 | Android 处理 |
| --- | --- | --- |
| OpenSSL | AES 加密（可选，n2n 自带 AES 实现也可） | 已有（freeRDPCore 构建时已处理 OpenSSL） |
| libc | 系统库 | NDK 自带 |
| pcap | 可选抓包 | 关闭 |
| natpmp / upnp | 端口映射 | 关闭（Android 不需要） |
| zstd / lzo | 压缩 | 可选，默认关闭 |

**关闭所有非必要特性**，只保留核心 edge 功能，减小体积与编译复杂度。

### 6.2 CMake 配置

在 `app/build.gradle` 的 `android` 块增加：

```groovy
android {
    // ... 现有配置
    externalNativeBuild {
        cmake {
            path 'src/main/cpp/n2n/CMakeLists.txt'
            version '4.1.2'   // 与 freeRDPCore 构建一致
        }
    }
    defaultConfig {
        externalNativeBuild {
            cmake {
                abiFilters 'arm64-v8a'
                arguments '-DCMAKE_BUILD_TYPE=Release',
                          '-DN2N_OPTION_AES=ON',
                          '-DN2N_OPTION_USE_PCAP=OFF',
                          '-DN2N_OPTION_USE_NAT_PMP=OFF',
                          '-DN2N_OPTION_USE_UPNP=OFF'
            }
        }
    }
}
```

`n2n/CMakeLists.txt` 组织：

```cmake
cmake_minimum_required(VERSION 3.18)
project(n2n_android)

# n2n 官方源码（作为外部源码引用，不入库，由 scripts 拉取到 deps/）
set(N2N_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/upstream/src)

add_library(n2n_android SHARED
    n2n_jni.c
    tuntap_android.c
    tun2tap/tun2tap.c
    uip/uip.c
    uip/uip_arp.c
    ${N2N_SRC_DIR}/edge.c
    ${N2N_SRC_DIR}/edge_utils.c
    ${N2N_SRC_DIR}/edge_management.c
    ${N2N_SRC_DIR}/n2n.c
    ${N2N_SRC_DIR}/wire.c
    ${N2N_SRC_DIR}/n2n_keyfile.c
    ${N2N_SRC_DIR}/minilzo.c
    ${N2N_SRC_DIR}/aes.c
    ${N2N_SRC_DIR}/transform_null.c
    ${N2N_SRC_DIR}/transform_aes.c
    ${N2N_SRC_DIR}/transform_tf.c
    ${N2N_SRC_DIR}/twofish.c
    ${N2N_SRC_DIR}/cc20.c
    ${N2N_SRC_DIR}/speck.c
    ${N2N_SRC_DIR}/random_numbers.c
    ${N2N_SRC_DIR}/auth.c
    # ... 其他 n2n 源文件
)

target_include_directories(n2n_android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/upstream/include
    ${CMAKE_CURRENT_SOURCE_DIR}/uip
)

target_link_libraries(n2n_android log)
```

### 6.3 n2n 源码获取

n2n 官方源码不入库（体积大），由脚本拉取：

```bash
# scripts/fetch-n2n.sh
N2N_VERSION=3.1.1   # 选择稳定 release
curl -L https://github.com/ntop/n2n/archive/refs/tags/${N2N_VERSION}.tar.gz \
  -o /tmp/n2n-${N2N_VERSION}.tar.gz
tar -xzf /tmp/n2n-${N2N_VERSION}.tar.gz -C app/src/main/cpp/n2n/upstream --strip-components=1
```

> 网络限制：`github.com` 直连超时，需走 socks5 代理 `192.168.56.1:1080`（与 peerrdp 其他依赖处理一致）。

### 6.4 与 freeRDPCore 构建的共存

- freeRDPCore 是预编译 AAR，其原生库已打包在 AAR 内
- n2n 原生库由 peerrdp 应用工程的 CMake 直接构建，产物 `libn2n_android.so` 进入 APK
- 两者互不干扰，APK 内同时包含 `lib/arm64-v8a/libfreerdp-android.so` 与 `lib/arm64-v8a/libn2n_android.so`
- 若 OpenSSL 符号冲突，n2n 侧使用自带 AES 实现（`N2N_OPTION_AES=ON` 但不链接 OpenSSL，用 n2n 内置 aes.c）

---

## 7. 权限与 Manifest

### 7.1 新增权限

```xml
<!-- n2n 需要 -->
<uses-permission android:name="android.permission.INTERNET" />              <!-- 已有 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />   <!-- 已有 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />     <!-- 前台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />  <!-- Android 14+ 特殊用途前台服务 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />     <!-- 前台通知（当前被 remove，需加回） -->
```

### 7.2 Service 声明

```xml
<service
    android:name=".n2n.N2nVpnService"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

### 7.3 VpnService 准备

首次启动 n2n 前，需调用 `VpnService.prepare(context)` 获取系统授权 Intent，用户确认后才能 `establish()`。`N2nManager.start()` 内处理此流程：若 `prepare()` 返回非 null Intent，先弹系统授权对话框。

---

## 8. 主要风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| **tun2tap 适配复杂度** | ARP、广播、MTU 处理不当导致虚拟网络不通 | 直接移植 hin2n 已验证的 uip + tun2tap 代码，不重写；先做 ping 连通性验证再接入 RDP |
| **n2n v3 与 v2s tun2tap 接口差异** | hin2n 的 tuntap_android.c 基于 v2s，移植到 v3 需适配 `tuntap_dev` 结构体变化 | 以 v3 的 `tuntap_open` 签名为准，参照 v2s 实现重写 `tuntap_android.c`；v3 结构体更清晰，适配量可控 |
| **Socket 保护遗漏** | n2n 的 UDP 流量被路由进 TUN，edge 无法连上 supernode | 在 n2n 所有 `socket()` 调用点统一注入 protect 回调；单元测试验证 supernode 可达 |
| **前台服务通知被用户划掉** | n2n edge 被系统杀死 | `START_STICKY` + 不可清除通知；onRevoke 时清理 |
| **n2n 与 RDP 同时运行的内存压力** | 构建机 3.9GB 内存，运行时手机内存也有限 | n2n 原生库体积小（预计 <500KB），运行时内存主要是 edge 状态表（<10MB），影响可忽略 |
| **NAT 类型导致 P2P 失败** | 只能走 supernode 中继，延迟高、带宽受限 | 属于 n2n 协议层面限制，非集成问题；提示用户尽量让 supernode 靠近两端 |
| **虚拟 IP 冲突** | community 内两台设备 IP 相同导致通信异常 | UI 层提示用户手动规划 IP；本期不做自动 IP 分配 |
| **OpenSSL 符号冲突** | n2n 与 freeRDPCore 都链接 OpenSSL，符号重复 | n2n 侧用内置 AES（不链接 OpenSSL）；若必须用 OpenSSL，统一使用 freeRDPCore 提供的版本 |

---

## 9. 许可合规

- **n2n**：GPLv3。peerrdp 作为整体若静态链接 n2n 源码，需遵守 GPLv3（传染）。
  - 对策：n2n 原生库以独立 `.so` 动态链接（`libn2n_android.so`），GPLv3 要求对该 `.so` 的源码提供获取渠道（在 `SOURCE.md` 中注明 n2n 源码地址与版本即可）。
  - peerrdp 应用层代码（Kotlin）仍为原有许可，不被传染（动态链接 + 进程内通信，GPLv3 的传染边界在司法实践中有争议，但提供 n2n 源码获取渠道是最低合规要求）。
- **uip**：BSD 协议，无传染风险。
- **hin2n 代码**：GPLv3，移植 tun2tap/uip 部分时保留版权声明。

---

## 10. 里程碑（建议，不含时间估算）

1. **n2n 原生库构建**：拉取 n2n v3 源码，编写 CMakeLists，在 Android NDK 下编译出 `libn2n_android.so`（先不含 tun2tap，验证 n2n 核心可编）
2. **tun2tap 移植**：移植 hin2n 的 uip + tun2tap + tuntap_android.c，适配 n2n v3 的 tuntap 接口，验证 TUN fd 可读写
3. **JNI 与 VpnService**：实现 `N2nVpnService` + JNI 接口，完成 edge 启动/停止/状态查询
4. **连通性验证**：真机上用 n2n edge 连接 supernode，与另一台设备 ping 通虚拟 IP
5. **UI 与数据层**：n2n 配置管理页、ConnectionEntity 增加 n2nConfigId、编辑页下拉选择
6. **RDP 联动**：`RdpConnector` 集成 n2n 启动等待流程，端到端验证"启 n2n → 连 RDP"
7. **收尾**：前台通知、权限声明、许可文档、APK 体积评估

---

## 11. 附录：参考资料

| 资料 | 来源 | 用途 |
| --- | --- | --- |
| n2n 官方 README | https://github.com/ntop/n2n | n2n 架构、supernode/edge/community 概念 |
| n2n 官方嵌入式示例 | `examples/example_edge_embed.c`、`example_edge_embed_quick_edge_init.c` | `edge_init` / `quick_edge_init` API 用法 |
| n2n Building.md | `doc/Building.md` | 编译选项、跨平台编译 |
| hin2n README | https://github.com/switch-iot/hin2n | VpnService + tun2tap + uip 架构 |
| hin2n N2NService.java | `An2n/app/src/main/java/.../service/N2NService.java` | VpnService.establish() + detachFd() 传参 |
| hin2n EdgeCmd.java | `.../model/EdgeCmd.java` | edge 配置参数结构 |
| hin2n CMakeLists.txt | `An2n/app/CMakeLists.txt` | n2n v2s 的 CMake 组织方式（edge_jni / n2n_v2s / uip / slog） |
| N2nBindings (NuGet) | https://www.nuget.org/packages/N2nBindings | NakanoMiku13/n2n-android 的 TUN 集成 + socket 回调方案 |
| Android VpnService 文档 | https://developer.android.com/guide/topics/connectivity/vpn | VpnService.Builder / protect / establish API |
