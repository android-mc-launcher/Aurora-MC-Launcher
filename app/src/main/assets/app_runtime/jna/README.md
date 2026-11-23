# jna —— 官方原版 jar + Android 专用 native

| 目录 | 内容 |
| --- | --- |
| `5.13.0/` | `jna-5.13.0.jar`（Maven Central 官方原版） |
| `5.14.0/` | `jna-5.14.0.jar`（Maven Central 官方原版） |
| `5.15.0/` | `jna-5.15.0.jar`（Maven Central 官方原版） |
| `5.16.0/` | `jna-5.16.0.jar`（Maven Central 官方原版） |
| `5.17.0/` | `jna-5.17.0.jar`（Maven Central 官方原版） |
| `5.18.0/` | `jna-5.18.0.jar`（Maven Central 官方原版） |
| `5.19.1/` | `jna-5.19.1.jar`（Maven Central 官方原版） |

`version` = `5.19.1`。

**Android native 已补齐**：每个版本目录下有
`natives/{arm64-v8a,armeabi-v7a,x86,x86_64}/libjnidispatch.so`，
来自 FCL-Team/FoldCraftLauncher 仓库签入的真实 Android 交叉编译产物
（共 28 个，ELF 架构核验见 [SOURCES.md](../../SOURCES.md) 第三节）。
官方 jar 内自带的 27 个平台 native 是 glibc/darwin/win/aix 系，不能用于 Android；
Android 上应使用目录里这组专用 `.so`。

**运行时已接通**：`RuntimeRepository.prepare()` 读取 `version`（= 5.19.1）选 jar，
按 `Build.SUPPORTED_ABIS` 选 `natives/<abi>/libjnidispatch.so`，释放到
`filesDir/runtime/jna/5.19.1/`，并把路径写入 `-Djna.library.path` /
`-Djna.nosys=true` 与 `LD_LIBRARY_PATH`。
