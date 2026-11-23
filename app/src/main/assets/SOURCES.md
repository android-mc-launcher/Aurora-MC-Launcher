# assets 真实文件来源清单

> 下载日期：**2026-11-22**。本目录**全部为上游原版字节**，逐文件可校验。
> 完整校验见工程根 [`SHA256SUMS.txt`](../../../../SHA256SUMS.txt)（**516 行**）。
> 上游许可证原文见工程根 `THIRD_PARTY_LICENSES/` 与各 JRE 目录内 `LICENSE`。

## 一、三个下载源（全部实测 200）

| 来源 | 内容 | 下载体积 |
| --- | --- | --- |
| GitHub `HMCL-dev/HMCL-PE` (main) 源码 tarball | Android OpenJDK 8/17、LWJGL3 Android natives、GL4ES、Caciocavallo | 184 MB |
| Maven Central 官方原版（腾讯云镜像） | JNA 7 个版本、LWJGL 3.3.3 / 3.4.1 各 7 模块 | 18 MB |
| GitHub `FCL-Team/FoldCraftLauncher` (main) 源码 tarball | **JRE 21 / JRE 25 全架构分卷** + **JNA Android 专用 native** | 344 MB |

精确 URL：

```text
https://codeload.github.com/HMCL-dev/HMCL-PE/tar.gz/refs/heads/main
https://mirrors.cloud.tencent.com/nexus/repository/maven-public/net/java/dev/jna/jna/{v}/jna-{v}.jar
https://mirrors.cloud.tencent.com/nexus/repository/maven-public/org/lwjgl/{module}/{v}/{module}-{v}.jar
https://codeload.github.com/FCL-Team/FoldCraftLauncher/tar.gz/refs/heads/main
```

> 官方 `PojavLauncher.apk` / `aaaapai` release 资产走 `release-assets.githubusercontent.com`，
> 当前网络网关对该域名返回 403（各种 gh 代理同样 403）。FCL / HMCL-PE 把 runtime
> **直接签入 git**（普通 blob，非 LFS、非 release asset），所以能经 codeload 匿名下载 ——
> 这也是这次能把 JRE21/25 真正补上的关键。

## 二、目录映射

| 工程目录 | 真实内容 | 文件数 | 体积 |
| --- | --- | --- | --- |
| `jna/<v>/` | 官方 `jna-<v>.jar` ×7 + `version` + 说明 | 9 | 13.09 MB |
| `jna/<v>/natives/<abi>/` | **FCL 签入的 Android 专用 `libjnidispatch.so`**，7 版 × 4 ABI | 28 | 3.62 MB |
| `lwjgl/3.3.3/`、`3.4.1/` | 官方 LWJGL jar + HMCL-PE Boat Android `.so` + GL4ES + glfw-classes | 50 | 22.26 MB |
| `java/jre8/`、`jre17/` | HMCL-PE 完整 Android OpenJDK **aarch64** JRE（展开格式，含 `bin/java`） | 371 | 169.00 MB |
| `java/jre21/` | **FCL 官方 JRE21 分卷**（5 个 tar.xz）+ `version` + 说明 | 7 | 44.13 MB |
| `java/jre25/` | **FCL 官方 JRE25 分卷**（4 个 tar.xz）+ `version` + 说明 | 6 | 46.73 MB |
| `caciocavallo/`、`caciocavallo17/` | 真实 `cacio-*.jar` + `version` | 7 | 0.27 MB |
| `lwjgl/*/sdl/` | SDL2 / SDL3 4 ABI ELF + zlib 许可 + `version` | 22 | 21.28 MB |
| `lwjgl/*/renderer/gl4es/` | SFPEW 4 ABI ELF + LGPL 许可 + `version` | 16 | 44.02 MB |

合计 **516 个文件 / 364.49 MB**。

### JRE21 / JRE25 是「FCL 官方分卷格式」

FCL（Fold Craft Launcher）把 JRE 拆成「平台无关 + 各架构二进制」分卷，运行时合并解压。
已用 `lzma` + `tarfile` 在内存中核验分卷内容，**确认是完整 Android JRE**：

| 分卷 | 条目数 | 展开体积 | 关键文件 |
| --- | --- | --- | --- |
| `jre21/universal.tar.xz` | 263 | 96.6 MB | `lib/modules`、conf、类库 |
| `jre21/bin-arm64.tar.xz` | 44 | 30.7 MB | `bin/java`、`lib/server/libjvm.so`、`release` |
| `jre25/universal.tar.xz` | 312 | 134.5 MB | `lib/modules`、conf、类库 |
| `jre25/bin-arm64.tar.xz` | 49 | 23.1 MB | `bin/java`、`lib/server/libjvm.so`、`release` |

与 `jre8` / `jre17` 的 HMCL-PE **展开格式**不同，所以 `jre21/` / `jre25/` 目录里
看不到 `bin/java` —— 文件是真的，只是以分卷形式存放。工程的两条合并路径：

- **运行时（已接通）**：`RuntimeRepository.extractFcl()` 用 XZ for Java +
  自写流式 `TarExtractor` 把 `universal.tar.xz` 与当前 ABI 的 `bin-<abi>.tar.xz`
  解压到 `filesDir/runtime/java/jre<N>/`，得到与 jre8 同构的完整 JRE；
- **本地检查**：工程根 `unpack-fcl-runtime.sh`（默认 arm64）可在 PC 上展开验证。

### 代表文件 SHA256

- `jre21/universal.tar.xz` → `57bd118b3696d572257c5f4be1762379eb6a81361a17da1c4b0b9f7131dd6d7c`
- `jre21/bin-arm64.tar.xz` → `9889de6d0526e0c9708ea18c6e7e41a7c3d5ae366a02524ae3a392274d3462b5`
- `jre25/universal.tar.xz` → `23141bf4e0ed46ff0b751c6cc32b3863d39e6a37b720f3d86a4fe9318aeb8e9f`
- `jre25/bin-arm64.tar.xz` → `16f09d1d163c008ae556be68c624057c4791c21ae6d31302334e61d75439567b`
- `jna 5.19.1 arm64 native` → `abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06`
- `jre17/bin/java` → `b1e54cd061984a94d9b308c4fee01ae51cb1b091273dead0e4703dd091263bdc`
- `jna-5.19.1.jar` → `4fb141dd8ef6b0585ffceea4bc49602fbc6312fa977e2c488794ea3e6aafecae`

## 三、交叉编译产物核验

沙盒**没有** Android NDK / clang / bionic sysroot，磁盘上限 500 MB
（NDK r30 解压需 2–3 GB），因此无法在此自行重编译；但下载到的二进制
**本身就是上游 CI 用 Android NDK 交叉编译出来的真实产物**。解析 ELF header：

```text
AArch64   : 114 个
ARM       : 13 个
x86       : 13 个
x86-64    : 13 个
总计 153 个 ELF 文件（其中 16 个是本轮新增的 SDL2 / SDL3、8 个是 SFPEW 真实库）
```

其中 JNA 的 28 个 `libjnidispatch.so` 覆盖 `arm64-v8a / armeabi-v7a / x86 / x86_64`
四种 Android ABI —— 上一版「官方 JNA jar 不含 Android native」的限制**已修复**。

## 四、启动链路已接通，剩余为真机边界

`AssetManager` 递归释放、FCL 分卷合并、按 `javaVersion` / ABI 选运行时、
classpath 拼装、`java.library.path` / `LD_LIBRARY_PATH` / `JAVA_HOME` 注入、
完整启动计划 JSON → C++ 引擎 `JLI_Launch` / `execv`，**均已实现**（见工程根
README 第 1 / 5 / 6 节）。本目录的文件不再是「躺在 assets 里」。

仍需真机验证或补齐的部分：

1. **渲染画面**：GL4ES / `libEGL_wrapper.so` 与定制 LWJGL 到 `ANativeWindow` 的
   桥接、输入映射、帧率上报，必须在 Android 设备上验证。
2. `jre8` / `jre17` 仍只有 **arm64** 一套（HMCL-PE tarball 里有其余架构，
   `fetch-missing-runtime.sh` 可补）；`jre21` / `jre25` 已含 FCL 分卷覆盖多架构。
3. 上游 virgl 的 `swrast_dri.so` 单文件 65 MB 未放入；渲染层仅含 GL4ES 与 EGL wrapper。
4. `jre25` 官方 FCL 分卷里**没有 x86(32位)** 架构（只有 arm / arm64 / x86_64），
   这是上游本身的取舍，不是本工程漏放；运行时会回退 arm64 并记录日志。
5. 沙盒无 Android SDK / NDK，未编译 APK；C++ 侧已用 host `g++ -std=c++23`
   对同一份源码做端到端启动计划解析测试（PASS）。

## 五、许可证

- OpenJDK 8/17/21/25 运行时：GPLv2 + Classpath Exception（原文在各 JRE 内 / `THIRD_PARTY_LICENSES/`）。
- LWJGL 3：BSD 3-Clause。JNA：LGPL 2.1 / ASL 2.0 双许可。
- HMCL-PE：GPLv3（原文 `THIRD_PARTY_LICENSES/HMCL-PE-repo-LICENSE`）。
- FCL（FoldCraftLauncher）：其仓库 `LICENSE` 随上游；本工程只原样分发其签入的 runtime 二进制，
  未修改内容。请遵守各上游许可与再分发条款。

### 六、SDL2 / SDL3 真实 ELF（本轮新增）

精确 URL 与包校验：

```text
https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/main/FCL/libs/SDL-release.aar
sha256: 5527d2e59e76b7a58eef0d54bb8ae0ca153593a9dfc0ed73ac84fb829520298b
```

AAR 内 `jni/<abi>/libSDL2.so` + `jni/<abi>/libSDL3.so` 覆盖
`arm64-v8a / armeabi-v7a / x86 / x86_64`，按两个 LWJGL 版本各放一份到
`lwjgl/<3.3.3|3.4.1>/sdl/sdl2|sdl3/<abi>/`，共 16 个 `.so`（21.28 MB）。
另有 4 份 AAR 内原样提取的 zlib 许可证与 2 份 `version` 清单。

- `libSDL2.so` 版本串 `SDL-2.32.57-no-vcs`，`readelf -d` 显示 NEEDED 含
  `libSDL3.so`，即 **sdl2-compat 2.32.57**，运行时必须能找到 SDL3；
- `libSDL3.so` 来自 SDL3 3.4.12 android-project 构建（FCL 官方源码阅读文档记载），
  NEEDED 含 `libGLESv1_CM.so / libGLESv2.so / libOpenSLES.so / libandroid.so`；
- 官方最新稳定为 SDL3 3.4.20 / sdl2-compat 2.32.74；GitHub release 资产域名在当前
  网关 403、sdl2-compat 官方不发 Android 预编译，故内置的是上游启动器实际使用的
  真实 bionic ELF，版本如实标注，未谎称最新。替换方法见工程根 `SDL.md`。

代表文件 SHA256：

- `lwjgl/3.3.3/sdl/sdl2/arm64-v8a/libSDL2.so` → `e040947c1c99b6bd8e6241fb453ec3143ad17ba26fbf82d749455bd130a002cd`
- `lwjgl/3.3.3/sdl/sdl3/arm64-v8a/libSDL3.so` → `0ca50998681a75c26cdd1505b1a1562c9d7c007f9a3e916c4b031e456eb65e6c`
- `lwjgl/3.4.1/sdl/sdl2/x86_64/libSDL2.so` → `0d337afc1fb1c126a62fea934d8b6c2b29bf3271f24bc99cc60990117a39cb3e`
- `lwjgl/3.4.1/sdl/sdl3/x86_64/libSDL3.so` → `c52b47a3df819f0720bd500d2a61a4de89a78135ab230a3505ece9873414240f`

- SDL2 / SDL3：zlib（原文见各 `sdl*/LICENSE.txt`）。

### 七、SFPEW / SimpleFPEWrapper 真实 ELF（本轮新增）

```text
https://raw.githubusercontent.com/ZalithLauncher/ZalithLauncher2/main/ZalithLauncher/libs/SimpleFPEWrapper-release.aar
https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/main/FCL/libs/SimpleFPEWrapper-release.aar
sha256: 2b6e7d24e91786aeda82f219f1c9a2df45f6811eddc86cc568d1011f581454a8
```

两份 AAR 字节完全一致（8,558,630 B）。`jni/<abi>/libSimpleFPEWrapper.so` 覆盖
`arm64-v8a / armeabi-v7a / x86 / x86_64`，按两个 LWJGL 版本各放一份到
`lwjgl/<3.3.3|3.4.1>/renderer/gl4es/<abi>/`，共 8 个真实 ELF（44.02 MB）。
`readelf -d` 显示 NEEDED 仅 `liblog.so / libm.so / libdl.so / libc.so`，
是 Android bionic 库；`minSdkVersion 26`，包名 `top.mobilegl.simplefpewrapper`。
库内构建串 `Oct 31 2026 18:34:34`，上游项目 `MobileGL-Dev/SimpleFPEWrapper`。

许可证为 **LGPL-3.0-only**：每个 renderer 目录放上游原样的 `LICENSE.txt`、
`COPYING`（GPLv3 全文）与 `COPYING.LESSER`（LGPLv3 附加条款），另放 `version` 清单。

上游 `SimpleFPEWrapper/init.cpp` 确认 `SFPEW_EGL` 是 wrapper 真正读取的环境变量，
值会被 `dlopen` 当作 backend 库名 / 路径；本工程已把它改为指向真实存在的
`libEGL_wrapper.so` 绝对路径，GL 与 EGL 两个 LWJGL 库都指向 wrapper。
详见工程根 `SFPEW.md`。

代表文件 SHA256：

- `lwjgl/3.3.3/renderer/gl4es/arm64-v8a/libSimpleFPEWrapper.so` → `ecaa3aa79f126db3468df31dc90888397f32ac9931d0be363d332387d6d37258`
- `lwjgl/3.4.1/renderer/gl4es/x86_64/libSimpleFPEWrapper.so` → `890c74b8127cc7639a567dfa105afa3b554583c94f327026a4e820f8c3755d1e`

- SimpleFPEWrapper：LGPL-3.0-only（原文见各 `renderer/gl4es/` 目录）。
