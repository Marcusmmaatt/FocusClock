/*
 * =================================================================================
 * SessionClock —— app 模块构建脚本（Sprint 1 基线）
 * ---------------------------------------------------------------------------------
 * 技术栈基线（与技术方案对齐，建项时可统一升级到同系列最新稳定 patch）：
 *   - AGP 8.7.3 / Gradle 8.11+ / JDK 17
 *   - Kotlin 2.1.20（K2 编译器）
 *   - Jetpack Compose：Compose Compiler 随 Kotlin 2.0+ 发布，
 *     需在 plugins 中应用 org.jetbrains.kotlin.plugin.compose（版本与 Kotlin 严格一致）
 *   - minSdk 28（Android 9.0，PRD 硬性要求）/ targetSdk、compileSdk 35（Android 15）
 *
 * 说明：
 *   1. 本文件为 :app 模块脚本；插件版本直接声明在 plugins{} 中，
 *      需配合根目录 settings.gradle.kts 的 pluginManagement{ google(); mavenCentral() }。
 *   2. 全项目【严禁】声明任何网络库（Retrofit/OkHttp/Coil 等）与 INTERNET 权限，
 *      遵守 PRD「Zero-Cloud / Zero-Internet」原则。
 * =================================================================================
 */

plugins {
    // 插件版本统一在根工程 build.gradle.kts 声明（apply false），此处只应用不写版本
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ 的 Compose Compiler Gradle 插件
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sessionclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sessionclock"
        // PRD 1 节：适用平台 Android 9.0 / API 28 及以上
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 调试包保留可读应用名后缀，便于与正式包共存
            applicationIdSuffix = ".debug"
        }
        release {
            // MVP 阶段先关闭混淆，待 Sprint 4 稳定后再开启 R8 全量混淆
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java 8+ API 脱糖：本项目用到 java.time 无关，但保留默认脱糖以兼容未来工具类
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // Kotlin 2.1 新 DSL：替代已废弃的 kotlinOptions { jvmTarget = "17" }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true // 启用 Jetpack Compose（Sprint 4 起使用，Sprint 1 先就位）
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    /* ------------------------------------------------------------------
     * 1. AndroidX / Kotlin 基础
     * ------------------------------------------------------------------ */
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    /* ------------------------------------------------------------------
     * 2. Jetpack Compose（版本由 BOM 统一仲裁，子依赖不再写版本号）
     * ------------------------------------------------------------------ */
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // collectAsStateWithLifecycle，生命周期安全地收集引擎 StateFlow
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // 显式声明：浮层 ComposeView 需要直接调用 ViewTree* 静态 setter，
    // 不能仅依赖上述库的传递依赖（传递依赖不保证进入编译类路径）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    /* ------------------------------------------------------------------
     * 3. 本地持久化（PRD NFR-2：Jetpack DataStore，仅存配置键值）
     *    冷静期绝对时间戳不走 DataStore，Sprint 2 使用内部存储文件同步落盘
     * ------------------------------------------------------------------ */
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    /* ------------------------------------------------------------------
     * 4. 后台兜底（Sprint 1 看门狗 / BOOT 后服务拉起失败降级使用）
     * ------------------------------------------------------------------ */
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    /* ------------------------------------------------------------------
     * 5. Java 8+ API 脱糖依赖（配合 isCoreLibraryDesugaringEnabled）
     * ------------------------------------------------------------------ */
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    /* ------------------------------------------------------------------
     * 6. 单元测试（Sprint 2 状态机 reducer / 计时器边界 179s、181s 必备）
     * ------------------------------------------------------------------ */
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")

    /* ------------------------------------------------------------------
     * 7. Android 插桩测试
     * ------------------------------------------------------------------ */
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
