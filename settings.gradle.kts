/*
 * SessionClock 根工程 settings.gradle.kts
 * - 插件与依赖仓库统一在此声明，模块内不得再配 repositories（FAIL_ON_PROJECT_REPOS）
 * - 纯离线工程，不包含任何私有 Maven 仓库
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // 允许按 features 自动下载 JDK 工具链（本机缺失 JDK17 时 Gradle 可自动 provisioning）
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FocusClock"
include(":app")
