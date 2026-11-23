# jre25 —— FCL 官方 Android JRE（真实原版分卷）

来源：`FCL-Team/FoldCraftLauncher` 仓库 `FCL/src/main/jreAssets/app_runtime/java/jre25/`
（原版 git blob，逐文件可校验）。

| 文件 | 说明 |
| --- | --- |
| `universal.tar.xz` | 平台无关部分：`lib/modules`、conf、类库 |
| `bin-arm.tar.xz` | arm 架构二进制：`bin/java`、`lib/server/libjvm.so` 等 |
| `bin-arm64.tar.xz` | arm64 架构二进制：`bin/java`、`lib/server/libjvm.so` 等 |
| `bin-x86_64.tar.xz` | x86_64 架构二进制：`bin/java`、`lib/server/libjvm.so` 等 |
| `version` | FCL 的版本标记 |

**这是 FCL 的分卷格式**。**运行时合并已接通**：`RuntimeRepository.extractFcl()`
用 XZ for Java + 流式 `TarExtractor` 把 `universal.tar.xz` 与当前设备 ABI 对应的
`bin-<abi>.tar.xz` 解压到 `filesDir/runtime/java/jre25/`，得到与 `../jre8`、`../jre17`
同构的完整 JRE；标记文件命中时跳过重复释放。工程根 `unpack-fcl-runtime.sh`
（默认 arm64）可在 PC 上做同样的本地验证。

上游 FCL 分卷**没有 x86(32 位)**（只有 arm / arm64 / x86_64）；运行时若设备为 x86
会回退 `bin-arm64.tar.xz` 并写日志，这是上游取舍而非漏放。

已在沙盒用 `lzma`+`tarfile` 核验分卷内容，确认含 `bin/java` 与
`lib/server/libjvm.so`，是**完整可运行的 Android JRE 二进制**。

真机边界：GL4ES / 定制 LWJGL 到 `ANativeWindow` 的画面渲染桥接仍需设备验证，
见根目录 README 第 7 节。
