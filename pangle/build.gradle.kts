plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.pangle"
}

dependencies {
    api(project(":core"))
    api(libs.pangle)
}
