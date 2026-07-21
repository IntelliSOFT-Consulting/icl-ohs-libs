import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.publish)
}

// group/version come from the root gradle.properties, shared with :server.

// Publishing to Maven Central - see README.md's "Publishing to Maven Central" section
// for the one-time account/signing setup this depends on.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "auth-core", version.toString())

    pom {
        name.set("icl-authentication-lib")
        description.set("Kotlin Multiplatform authentication library for Keycloak-backed services")
        // TODO: replace with the real repository URL once one exists.
        url.set("https://github.com/REPLACE_ME/icl-authentication-lib")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("intellisoft")
                name.set("Intellisoft")
                // TODO: replace with a real contact URL/email for the maintaining org.
                url.set("https://github.com/REPLACE_ME")
            }
        }
        scm {
            url.set("https://github.com/REPLACE_ME/icl-authentication-lib")
            connection.set("scm:git:https://github.com/REPLACE_ME/icl-authentication-lib.git")
            developerConnection.set("scm:git:ssh://git@github.com/REPLACE_ME/icl-authentication-lib.git")
        }
    }
}

kotlin {
    jvmToolchain(21)

    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    // Enable these once a mobile client SDK is ready to consume commonMain directly
    // (DTOs + KeycloakHttpClient are already 100% shareable as-is):
    // androidTarget()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)   // Ktor client engine used server-side too

                implementation(libs.exposed.core)
                implementation(libs.exposed.jdbc)
                implementation(libs.exposed.java.time)
                implementation(libs.postgresql)
                implementation(libs.hikaricp)

                implementation(libs.java.jwt)
                implementation(libs.jwks.rsa)

                implementation(libs.flyway.core)
                implementation(libs.flyway.postgresql)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.junit.jupiter)
            }
        }
    }
}
