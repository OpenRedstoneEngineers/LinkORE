plugins {
    kotlin("jvm")
    id("com.squareup.sqldelight") version "1.5.0"
}

sqldelight {
    database("Database") {
        packageName = "org.openredstone.linkore.db"
    }
}

dependencies {
    implementation(project(":api"))
    implementation("org.openredstone.koreutils.messaging:api")
    implementation("com.squareup.sqldelight:sqlite-driver:1.5.0")
    implementation(deps.kotlinx.coroutines.core)
    implementation(deps.kotlinx.coroutines.jdk8)
    implementation(deps.jackson.core)
    implementation(deps.jackson.module.kotlin)
    implementation(deps.javacord)
    deps.ktor.apply {
        arrayOf(
            server.core,
            server.netty,
            auth,
            client.apache,
            html.builder,
            locations,
            jackson,
        ).forEach(::implementation)
    }
}
