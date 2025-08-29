import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // For Cloudstream tools and dependencies
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.3.0")
     //   classpath("com.github.recloudstream:gradle:3.1.2")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}


   // apply(plugin = "com.android.library")
    //apply(plugin = "kotlin-android")
    //apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }

    dependencies {
        val apk by configurations
        val implementation by configurations

        apk("com.lagradost:cloudstream3:3.1.2")

        //implementation(kotlin("stdlib"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
        implementation("com.github.Blatzar:NiceHttp:0.3.2")
        implementation("org.jsoup:jsoup:1.13.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.+")
        implementation("org.mozilla:rhino:1.7.14")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
