// This module remains a pure Kotlin engine at source level.
// AGP 9 built-in Kotlin is used to match the original project's Gradle 9.6 / AGP 9.5 setup.
plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "dev.hwanaebi.core"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    testImplementation(libs.junit)
}
