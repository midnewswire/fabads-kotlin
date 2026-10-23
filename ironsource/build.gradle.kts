plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.ironsource"
}

dependencies {
    api(project(":core"))
    api(libs.levelplay)
}
