import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets VERSION_CODE to the run number so each release can upgrade the last.
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "io.github.dynoz769.debloithome"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dynoz769.debloithome"
        minSdk = 23
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Public key committed to the repo so every build (local or CI) has the
        // same signature and can be installed over the previous one.
        create("release") {
            storeFile = file("../keystore/debloit.jks")
            storePassword = "debloit"
            keyAlias = "debloit"
            keyPassword = "debloit"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
