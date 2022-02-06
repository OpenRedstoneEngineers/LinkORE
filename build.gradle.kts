plugins {
    val kotlinVersion = "1.5.0"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("kapt") version kotlinVersion apply false
    id("com.github.johnrengelman.shadow") version "2.0.4" apply false
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}
