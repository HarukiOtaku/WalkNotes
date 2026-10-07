buildscript {
    dependencies {
        // AGP 9's built-in Kotlin defaults to KGP 2.2.10; LiteRT-LM 0.18.0 ships Kotlin 2.4.0
        // metadata, so the Kotlin Gradle plugin on the build classpath must be >= 2.4.0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("com.android.application") version "9.4.1" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
