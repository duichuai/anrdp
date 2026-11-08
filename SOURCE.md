# 源代码获取说明（MPL-2.0 合规）

本仓库分发的 `app/libs/freeRDPCore-release.aar` 包含受 MPL-2.0 覆盖的 FreeRDP Android 客户端代码（`com.freerdp.freerdpcore`）。依据 MPL-2.0 第 3.2 条，现将对应 Source Code Form 的获取方式说明如下。

## 1. 上游源码

- 项目：FreeRDP
- 版本：3.33.0（仓库内现有 AAR 的构建基线）
- 仓库：https://github.com/FreeRDP/FreeRDP
- 源码包：https://github.com/FreeRDP/FreeRDP/archive/refs/tags/3.33.0.tar.gz

## 2. 构建期修改

AAR 由上述源码构建，构建期共应用 9 处补丁，全部为构建配置，未修改任何受 MPL-2.0 覆盖的 `.java` / `.kt` 源文件：

1. `gradle/wrapper/gradle-wrapper.properties` → Gradle 9.6.1（腾讯镜像）
2. `settings.gradle` → 仅保留 `:freeRDPCore` 模块
3. 根 `build.gradle` → 移除 `androidgitversion` 插件
4. `freeRDPCore/build.gradle` → 注入 `namespace`
5. `release.properties` → 覆盖 ABI、原生特性开关与版本号
6. `freeRDPCore/proguard-rules.txt` → 补建上游缺失文件
7. `gradle.properties` → 调整 JVM 堆与并行度
8. `client/Android/cmake/External*.cmake` → 依赖镜像改为本地路径
9. `cmake/DepVersions.cmake` → 按实际镜像文件写入依赖版本与 sha256

完整且可执行的补丁实现见仓库脚本 [`scripts/build-freerdpcore-aar.sh`](scripts/build-freerdpcore-aar.sh)。该脚本会自动下载对应版本的上游源码、应用上述补丁并编译，因此执行它即可获得与本 AAR 相同的 Source Code Form（脚本默认版本即 FreeRDP 3.33.0）。

## 3. tsnet AAR 源码

`app/libs/tsnet.aar` 由工作区根目录的 `tsnet-android/`（Go module，**未纳入 anrdp 仓库**）用 `gomobile bind` 构建；wrapper 源码仅包含对 tsnet 的封装，未修改 Tailscale 上游源码。构建命令与版本见 `BUILD.md` §2.3 与 `docs/tailscale-integration-design.md` §4。

其静态链接的组件源码地址：

| 组件 | 版本 | 源码地址 |
| --- | --- | --- |
| Tailscale / tsnet | 1.104.0 | https://github.com/tailscale/tailscale |
| wireguard-go（tailscale fork） | 随 tsnet 1.104.0 | https://github.com/tailscale/wireguard-go |
| gVisor netstack | 随 tsnet 1.104.0 | https://github.com/google/gvisor |
| Go 标准库 / golang.org/x | 1.27.1 | https://go.googlesource.com/go 、https://cs.opensource.google/go/x |
| gomobile / gobind | latest | https://github.com/golang/mobile |

> tsnet wrapper 源码可向维护者索取；若后续纳入仓库，将同步更新本节。

## 4. 其他第三方组件源码

| 组件 | 版本 | 源码地址 |
| --- | --- | --- |
| FreeRDP | 3.33.0 | https://github.com/FreeRDP/FreeRDP |
| OpenSSL | 4.0.3 | https://github.com/openssl/openssl |
| cJSON | 1.7.19 | https://github.com/DaveGamble/cJSON |
| uriparser | 1.0.2 | https://github.com/uriparser/uriparser |
| SQLCipher | 4.17.0 | https://github.com/sqlcipher/sqlcipher |

## 5. 书面要约

若无法通过上述途径获取对应源码，可通过本仓库的 Issue 联系维护者，我们将在合理时间内提供源码副本，收取的费用不超过源码分发成本。
