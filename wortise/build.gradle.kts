plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.wortise"
    // The Wortise SDK needs Android 7.0 (API 24); apps using this module must raise their minSdk to 24.
    defaultConfig { minSdk = 24 }
}

dependencies {
    api(project(":core"))
    api(libs.wortise)
}
