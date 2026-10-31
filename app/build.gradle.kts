plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.dsh.shell"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "2.7.3"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("dsh-shell.keystore")
            storePassword = "dshshell2024"
            keyAlias = "dshshell"
            keyPassword = "dshshell2024"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
