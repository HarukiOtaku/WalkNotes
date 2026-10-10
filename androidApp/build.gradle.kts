plugins {
    // AGP 9.x has built-in Kotlin support — the org.jetbrains.kotlin.android plugin must NOT be applied.
    id("com.android.application")
}

android {
    namespace = "com.civicwalk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.civicwalk.app"
        minSdk = 26          // LiteRT-LM requires Android 8.0+
        targetSdk = 35
        versionCode = 17
        versionName = "0.4.12"
        // The prototype target is one modern phone (Snapdragon 8 Gen 2, arm64).
        // Every extra ABI adds ~10 MB of LiteRT native libraries to the APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // On-device runtime: the SAME .litertlm model file the cli harness uses
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // UI: Material Components gives the tabbed layout, cards and the model-manager progress bar;
    // AppCompat is what makes the in-app language switch (per-app locales) work.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Street map for the exploration tab: free OpenFreeMap vector tiles, no API key.
    implementation("org.maplibre.gl:android-sdk:11.11.0")
}
