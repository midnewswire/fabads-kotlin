// Every network in one dependency. Meant for the demo app and for trying networks out: a released app should
// depend only on the networks it really uses (see README, "Choose your ad networks").
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fabads.all"
    defaultConfig { minSdk = 24 } // Wortise needs API 24
}

dependencies {
    listOf(
        "admob", "applovin", "meta", "unity", "startio", "ironsource", "pangle",
        "mintegral", "liftoff", "inmobi", "chartboost", "yandex", "wortise",
    ).forEach { api(project(":$it")) }
}
