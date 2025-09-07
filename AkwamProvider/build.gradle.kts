 plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}
version = 1

cloudstream {
    language = "ar"
   // author = "Gnrl"
    description = "Arabic streaming plugin for Cloudstream"
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    //id = ("faselhd.provider")
   // apiVersion = 4
}

   android {
    namespace = "com.akwam"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
 dependencies {
    val apk by configurations

    apk("com.lagradost:cloudstream3:pre-release")

    implementation("com.github.recloudstream.cloudstream:library:v4.5.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
    implementation("com.github.Blatzar:NiceHttp:0.4.13")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
    implementation("org.mozilla:rhino:1.8.0")
}
