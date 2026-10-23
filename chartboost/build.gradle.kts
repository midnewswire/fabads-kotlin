plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.chartboost"
}

dependencies {
    api(project(":core"))
    api(libs.chartboost)
}
