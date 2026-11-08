# AnRDP

AnRDP 是一个面向 Android 的远程桌面（RDP）客户端。引擎基于 [FreeRDP](https://github.com/FreeRDP/FreeRDP) 3.33.0，以预编译 AAR 形式集成；应用层使用 Kotlin + Room。

> 本项目为独立开发，与 FreeRDP 项目无隶属或背书关系。

## 功能

- RDP 连接配置的增删改查（名称、主机、端口、用户名、密码、分辨率）
- 分辨率：自适应 / 1280×720 / 1600×900 / 1920×1080 / 2560×1440 / 1024×768
- 触摸指针、手势操作与可展开的浮动工具条
- 英文/符号键直接发送扫描码；中文请使用远端输入法上屏
- 连接配置复制后新建

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17+ |
| Android SDK | platform 37 / build-tools 37.0.0 |
| Gradle | 由 wrapper 提供（9.6.1） |
| AGP | 9.2.1（AGP 9 已内置 Kotlin 支持） |

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk   # 或使用仓库上一级 tools/android-sdk
bash scripts/build-app.sh                  # debug  → app-debug.apk
bash scripts/build-app.sh release          # release → app-release.apk（沿用 debug 签名，仅适合自用）
```

产物会复制到仓库根目录。构建脚本会校验 APK 内容与权限清单。

更详细的构建说明、环境布局与"勿回退"约束见 [BUILD.md](BUILD.md)。

仓库已附带引擎 AAR（`app/libs/freeRDPCore-release.aar`），日常构建无需重编引擎。若需从源码重建：

```bash
bash scripts/build-freerdpcore-aar.sh
```

该脚本需要额外工具链：NDK 29.0.13113456、CMake 4.1.2、JDK 21；默认版本为 FreeRDP 3.33.0 + OpenSSL 4.0.3，可用 `FREERDP_VERSION` / `OPENSSL_VERSION` 环境变量覆盖。缺失源码时会自动下载。

## 目录结构

```
app/          应用模块（Kotlin + Room）
app/libs/     预编译引擎 AAR
scripts/      构建脚本
docs/         技术设计文档
```

## 权限

APK 仅申请下列权限。引擎 AAR 声明的相机、录音、外部存储与通知权限已在 manifest 中显式移除。

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 连接 RDP 服务器 |
| `ACCESS_NETWORK_STATE` | 网络状态判断 |
| `ACCESS_WIFI_STATE` | 网络类型判断 |
| `MODIFY_AUDIO_SETTINGS` | 远程音频播放 |

## 许可证

- 本项目自身代码：[Apache License 2.0](LICENSE)，Copyright 2026 duichuai。
- 引擎 AAR（`app/libs/freeRDPCore-release.aar`）为第三方作品：FreeRDP 核心库与 C JNI 为 Apache-2.0；**`com.freerdp.freerdpcore` 的 Java/Kotlin 客户端代码为 MPL-2.0**（另有 2 个源自 AOSP 的文件为 Apache-2.0）。本项目未修改其源码。
- 其余第三方组件（OpenSSL、cJSON、uriparser、SQLCipher、AndroidX、Kotlin 等）的许可证与声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- MPL-2.0 组件的源码获取方式见 [SOURCE.md](SOURCE.md)。

## 商标与免责

- “RDP”“Remote Desktop”等名称与商标归各自权利人所有，本项目仅在描述意义上使用。
- 本项目不以 FreeRDP 名义发布，也不代表 FreeRDP 项目或其贡献者。
- 软件按“现状”提供，不附带任何明示或默示担保；使用风险由使用者自行承担。

## 已知限制

- 仅构建 `arm64-v8a`。
- release 变体沿用 debug 签名，仅适合侧载/自用，不适合上架应用商店。
- 手机侧中文输入法直接上屏受引擎输入连接实现限制，建议使用远端输入法。
- 若远端为 VirtualBox VRDE，默认只允许单连接，需开启多连接后才能与其他客户端并存。
