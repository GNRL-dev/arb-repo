/* plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}*/
version = 1

cloudstream {
    language = "ar"
    //author = "YourName"
    description = "Arabic streaming plugin for Cloudstream"
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
  //  id = "faselhd.provider"
}

  android {
    namespace = "com.faselhd"
    compileSdk = 31

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1.8
        targetCompatibility = JavaVersion.VERSION_1.8
    }
}
/*
dependencies {
    implementation("com.github.recloudstream.cloudstream:library:v4.5.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.github.Blatzar:NiceHttp:0.4.13")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.+")
    implementation("org.mozilla:rhino:1.7.14")
    apk("com.lagradost:cloudstream3:pre-release")
}*/
