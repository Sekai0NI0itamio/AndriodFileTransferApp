import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("com.android.application")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)

    androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }

        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("org.jmdns:jmdns:3.6.3")
                implementation("io.ktor:ktor-server-core-jvm:2.3.12")
                implementation("io.ktor:ktor-server-cio-jvm:2.3.12")
                implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }

        val androidMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("androidx.activity:activity-compose:1.10.1")
                implementation("androidx.core:core-ktx:1.16.0")
            }
        }

        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "com.githubbasedengineering.localbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.githubbasedengineering.localbridge"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.githubbasedengineering.localbridge.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "LocalBridge"
            packageVersion = "1.0.0"

            macOS {
                bundleID = "com.githubbasedengineering.localbridge"
                dockName = "LocalBridge"
            }
        }
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
