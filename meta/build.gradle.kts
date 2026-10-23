plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.meta"
}

dependencies {
    api(project(":core"))
    api(libs.meta.audience)
}
