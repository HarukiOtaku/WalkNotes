plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    // Same runtime + same .litertlm model file as the Android app (LiteRT-LM 0.18.0, 2026-10-06)
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("com.civicwalk.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx1500m")
}
