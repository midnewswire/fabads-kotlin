import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// JitPack builds a tag and serves every library module as com.github.midnewswire.fabads-kotlin:<module>:<tag>.
val sdkVersion = System.getenv("VERSION") ?: "1.0.0-SNAPSHOT"

subprojects {
    group = "com.github.midnewswire.fabads-kotlin"
    version = sdkVersion

    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    }

    plugins.withId("com.android.library") {
        apply(plugin = "maven-publish")
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 23
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("release") {
                    from(components["release"])
                    artifactId = project.name
                }
            }
        }
    }
}
