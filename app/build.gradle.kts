plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dingleinc.texttoolspro"
    compileSdk = 35

    val keystorePath = System.getenv("KEYSTORE_PATH") ?: "/home/yan/ubuntu/project/backup/app/keystore/expandroid.jks"
    val keystorePass = System.getenv("KEYSTORE_PASS") ?: "Expandroid2024!"
    val keyAlias = System.getenv("KEY_ALIAS") ?: "expandroid-key"
    val keyPass = System.getenv("KEY_PASS") ?: "Expandroid2024!"

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = keystorePass
            keyAlias = keyAlias
            keyPassword = keyPass
        }
    }

    defaultConfig {
        applicationId = "com.dingleinc.texttoolspro"
        minSdk = 24
        targetSdk = 35
        versionCode = 200031
        versionName = "7.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Use the same fixed signing for debug builds too
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    debugImplementation(libs.androidx.ui.tooling)
}
