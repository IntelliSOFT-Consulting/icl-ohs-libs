plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.ktor)
}

// group/version come from the root gradle.properties, shared with :core.

application {
    mainClass.set("ke.intellisoft.icl.auth.server.MainKt")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
}

// `./gradlew :server:run` for local dev.
// The Ktor Gradle plugin also gives you `:server:buildFatJar` for the Docker image
// referenced in docker-compose.yml.
