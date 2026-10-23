plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fabads.admob"
}

dependencies {
    api(project(":core"))
    api(libs.play.ads)
}
