// On-device database (Room + bundled SQLite). Plain JVM so it's tested on the laptop;
// the same code runs on Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
}

kotlin { jvmToolchain(17) }

ksp {
    arg("room.schemaLocation", file("schemas").path) // committed: needed to write migrations later
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core"))
    api("androidx.room:room-runtime:2.8.3")
    api("androidx.sqlite:sqlite-bundled:2.6.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    ksp("androidx.room:room-compiler:2.8.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
