plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.inmobi"
}

dependencies {
    api(project(":core"))
    api(libs.inmobi)
}
