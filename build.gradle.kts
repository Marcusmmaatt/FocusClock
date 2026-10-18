/*
 * SessionClock 根工程构建脚本
 * 统一声明插件版本并 apply false（不在根工程应用），:app 模块按需 apply。
 * 版本升级时只改此处与 gradle-wrapper.properties、app/build.gradle.kts 的依赖版本。
 */
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
