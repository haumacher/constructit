plugins {
    kotlin("multiplatform") version "1.9.24"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js(IR) {
        browser {
            binaries.executable()
            commonWebpackConfig {
                outputFileName = "constructit.js"
            }
        }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
                implementation("com.microsoft.playwright:playwright:1.44.0")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.11.0")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // forward -De2e=1 to the test JVM so the (otherwise-skipped) browser E2E can opt in
    System.getProperty("e2e")?.let { systemProperty("e2e", it) }
    testLogging { events("passed", "failed", "skipped") }
}
