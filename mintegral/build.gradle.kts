plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.mintegral"
}

dependencies {
    api(project(":core"))
    api(libs.mintegral)
}
