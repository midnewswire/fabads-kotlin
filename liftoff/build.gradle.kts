plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.liftoff"
}

dependencies {
    api(project(":core"))
    api(libs.liftoff)
}
