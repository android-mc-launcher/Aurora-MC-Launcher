// AGP 9.x 新版 DSL：Kotlin 编译器由 AGP 内置提供，
// 不再应用 org.jetbrains.kotlin.android；Compose 编译器插件按模块应用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 精确校验：Gradle Daemon / javac / Kotlin 编译器必须跑在 JDK 25 上
// （Gradle 9.9.1 官方支持运行 JVM 17–27；AGP 9.5.0-alpha08 已修复 JDK 25 下的告警）
// 补丁版本不锁死：季度 GA 25.0.5（2026-10-20），带外 GA 25.0.5.1（2026-11-17）均可。
require(JavaVersion.current().majorVersion == "25") {
    "本工程要求 JDK 25（推荐 OpenJDK 25.0.5 / 25.0.5.1 GA），当前 Gradle JVM = ${JavaVersion.current()}。" +
        "请在 Android Studio → Gradle JDK 中切换，或在 gradle.properties 设置 org.gradle.java.home。"
}
