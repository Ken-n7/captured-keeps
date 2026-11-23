// Pure Kotlin (no Android) — business logic and print engine, tested on the laptop JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Sample strips: drop photos in <repo>/samples/photos, PNGs come out in core/build/samples
    systemProperty("samplePhotosDir", rootProject.file("../samples/photos").path)
    systemProperty("sampleOutDir", layout.buildDirectory.dir("samples").get().asFile.path)
}
