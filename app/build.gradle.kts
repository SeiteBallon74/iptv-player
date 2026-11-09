plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "de.drissler.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.drissler.iptv"
        minSdk = 22
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Fester Schlüssel, damit Updates sich über die alte Version installieren lassen
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.keystore")
            storePassword = "iptvplayer"
            keyAlias = "iptv"
            keyPassword = "iptvplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("io.coil-kt:coil:2.6.0")
}
