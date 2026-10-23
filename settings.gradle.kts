pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only needed by the networks that are not on Maven Central. Apps copy just the lines for the networks they use.
        maven("https://artifact.bytedance.com/repository/pangle") // pangle, wortise
        maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") // mintegral
        maven("https://maven.wortise.com/artifactory/public") // wortise
        maven("https://artifactory.bidmachine.io/bidmachine") // wortise
    }
}

rootProject.name = "fabads-kotlin"

include(":core")
include(
    ":admob", ":applovin", ":meta", ":unity", ":startio", ":ironsource", ":pangle",
    ":mintegral", ":liftoff", ":inmobi", ":chartboost", ":yandex", ":wortise",
)
include(":all")
include(":demo")
