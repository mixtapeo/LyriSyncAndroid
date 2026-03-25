plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mixtapeo.lyrisync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mixtapeo.lyrisync"
        minSdk = 24
        targetSdk = 36
        // System.getenv returns a String?, so we handle the null case and convert to Int
        versionCode = 2
        // We trim "v" from the tag if it exists (e.g., v1.0.4 -> 1.0.4)
        versionName = "v0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signingConfig = signingConfigs.getByName("debug")

            // This ensures the APK stays "unsigned" so the GitHub Action can sign it properly
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    viewBinding {
        enable = true
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.common)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // This tells Gradle to include the Spotify SDK file you just pasted
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    
    // This is the missing piece the Spotify SDK needs
    implementation(libs.gson)
    implementation(libs.androidx.appcompat)
    
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
