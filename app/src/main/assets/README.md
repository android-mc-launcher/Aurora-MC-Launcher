# assets —— 真实运行时文件（含 JRE21 / JRE25）

这里**全部是真实原版文件**，共 **516 个 / 364.49 MB**。
精确来源、URL、SHA256、ELF 核验、许可证与边界见 [SOURCES.md](SOURCES.md)；
全量校验见工程根 `SHA256SUMS.txt`（516 行）。

```text
app_runtime/
├── jna/        5.13.0 … 5.19.1：官方 jar + FCL 的 Android 专用 libjnidispatch.so（4 ABI）+ version
├── lwjgl/      3.3.3 / 3.4.1：官方 jar + Android .so + GL4ES + EGL wrapper
│               sdl/sdl2/<abi>/libSDL2.so 与 sdl/sdl3/<abi>/libSDL3.so（4 ABI 真实 ELF）
│               renderer/gl4es/<abi>/libSimpleFPEWrapper.so（4 ABI 真实 SFPEW ELF）
├── java/       jre8 / jre17：完整展开的 Android OpenJDK aarch64
│               jre21 / jre25：FCL 官方分卷（universal + bin-<abi>.tar.xz，全架构）
├── caciocavallo/    真实 cacio-*.jar
└── caciocavallo17/  真实 cacio-*.jar
```

**启动链路已接通**：`RuntimeRepository` 会在首次启动时把这些文件释放到
`filesDir/runtime/`，其中 `jre21` / `jre25` 用 XZ for Java + 流式 TAR 解压器把
分卷合并成含 `bin/java`、`lib/server/libjvm.so` 的完整 JRE，再按 `javaVersion`
与设备 ABI 拼出 classpath 与 JVM 参数交给 C++ 引擎。标记文件命中时跳过重复释放。

- 本地验证分卷：`unpack-fcl-runtime.sh arm64`
- 补齐 jre8/17 其他架构：`fetch-missing-runtime.sh`
- SDL 版本、来源与替换官方最新构建的方法：工程根 [SDL.md](../../../../SDL.md)
- SFPEW 真实 ELF、LGPL 许可与 `SFPEW_EGL` 语义：工程根 [SFPEW.md](../../../../SFPEW.md)

**真机边界**：GL4ES / 定制 LWJGL 到 `ANativeWindow` 的画面渲染桥接仍需设备上验证；
详见 [SOURCES.md](SOURCES.md) 第四节与工程根 README 第 7 节。
