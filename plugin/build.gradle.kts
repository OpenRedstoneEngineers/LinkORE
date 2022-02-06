import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("com.github.johnrengelman.shadow")
}

dependencies {
    implementation(project(":api"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.openredstone.koreutils:bungee:master-SNAPSHOT")
    implementation("org.openredstone.koreutils.messaging:impl:master-SNAPSHOT")
    implementation(deps.kotlinx.coroutines.core)
    implementation(deps.ktor.client.apache)
    implementation(deps.ktor.client.jackson)
    compileOnly(deps.bungee.api)
    implementation("net.kyori:adventure-text-minimessage:4.1.0-SNAPSHOT")
    implementation("net.kyori:adventure-platform-bungeecord:4.0.0-SNAPSHOT")
}


tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        javaParameters = true
        freeCompilerArgs = listOf(
            "-Xopt-in=kotlin.ExperimentalStdlibApi",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
