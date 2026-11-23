import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9.x 新版 DSL：只声明 application + Compose 编译器插件（Kotlin 由 AGP 内置）
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yuanbao.mcjlauncher"

    // 显式指定 SDK Build-Tools 37.0.0（用户要求，高于 AGP 9.5.1 官方默认的 36.0.0）。
    // AGP 兼容表中的 36.0.0 是「最低版本」，向上覆盖是允许的；
    // 官方 Android 17 SDK 设置页明确要求展开「Build-Tools 37」选最新 37.x.x。
    // 版本取自版本目录（type-safe accessors，新版 DSL）。
    buildToolsVersion = libs.versions.buildTools.get()

    // 新版 CompileSdkSpec DSL：Android 17 (API 37) + QPR1 次级 API 37.1
    // Compose BOM 2026.11.01 / Material3 1.5.0 要求 compileSdk 37.1
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yuanbao.mcjlauncher"
        minSdk = 24          // Android 7.0 Nougat
        targetSdk = 37       // Android 17 Cinnamon Bun（次级 SDK 不允许用于 targetSdk）
        versionCode = 2
        versionName = "1.0.0-logic"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments.addAll(
                    listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_PLATFORM=android-24",
                        "-DMCJL_CPP_STANDARD=23",
                    ),
                )
            }
        }
    }

    // NDK r30 正式稳定版（LTS），精确版本号
    ndkVersion = libs.versions.ndk.get()

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"   // Android SDK cmake/4.1.2
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // 构建 JVM / javac / Kotlin 编译器 = JDK 25；Android 产出字节码目标 = JVM 21。
        // 取舍原因：D8/R8 对 Java 25 class-file major 69 的完整支持未在 AGP 9.5.1
        // 官方发布说明中确认；保持 21 可确保 minSdk 24 的 desugaring 链路无误。
        // 若强制要 Java 25 字节码：把两行改为 JavaVersion.toVersion(25)，
        // 并把下方 jvmTarget 改为 JvmTarget.JVM_25（需自行做完整 D8/R8 验证）。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 新 DSL：顶层 kotlin {} 取代旧的 android { kotlinOptions {} }
kotlin {
    compilerOptions {
        // Kotlin 2.4.21 官方 DSL 中 jvmTarget 可选值为 "1.8".."26"（含 25），
        // 这里与 compileOptions 对齐保持 21；JDK 25 仍作为编译器运行 JVM。
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // FCL 的 JRE21/25 分卷是 .tar.xz，运行时用纯 Java 的 XZ for Java 合并解压
    implementation(libs.xz)

    implementation(libs.bundles.compose)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
}
