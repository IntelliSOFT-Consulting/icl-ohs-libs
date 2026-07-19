plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "ke.go.moh.icl"
version = "0.1.0"

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // Only a JVM target is built today: the two current consumers (icl-ohs backend
    // services) are JVM-only. commonMain still avoids every JVM-only dependency, so
    // adding ios()/js() targets later - for a future mobile client - needs no source
    // changes, only a new target block here.

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.jakarta.mail.api)
                implementation(libs.angus.mail)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.greenmail)
            }
        }
    }
}
