import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("com.android.application")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val androidReleaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val androidReleaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val androidReleaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val androidReleaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val routerTunnelSourceFile = rootProject.file("router_tunnel for intel/router_tunnel.c")
val routerTunnelBuildOutputFile = layout.buildDirectory.file("routerTunnel/router_tunnel").get().asFile
val routerTunnelResourcesRootDir = layout.buildDirectory.dir("routerTunnelResources")
val routerTunnelResourcesMacosDir = routerTunnelResourcesRootDir.get().asFile.resolve("macos")

val buildRouterTunnelBinary = tasks.register<Exec>("buildRouterTunnelBinary") {
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
    inputs.file(routerTunnelSourceFile)
    outputs.file(routerTunnelBuildOutputFile)

    doFirst {
        routerTunnelBuildOutputFile.parentFile.mkdirs()
    }

    executable = "clang"
    args = buildList {
        add("-O2")
        add("-Wall")
        add("-Wextra")
        if (System.getProperty("os.arch").lowercase().contains("arm")) {
            add("-arch")
            add("arm64")
            add("-arch")
            add("x86_64")
        }
        add("-o")
        add(routerTunnelBuildOutputFile.absolutePath)
        add(routerTunnelSourceFile.absolutePath)
    }
}

val stageRouterTunnelBinary = tasks.register<Copy>("stageRouterTunnelBinary") {
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
    dependsOn(buildRouterTunnelBinary)

    from(routerTunnelBuildOutputFile)
    into(routerTunnelResourcesMacosDir)
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
    namespace = "asd.itamio.localbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "asd.itamio.localbridge"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (androidReleaseKeystorePath != null) {
                storeFile = file(androidReleaseKeystorePath)
            }
            if (androidReleaseKeystorePassword != null) {
                storePassword = androidReleaseKeystorePassword
            }
            if (androidReleaseKeyAlias != null) {
                keyAlias = androidReleaseKeyAlias
            }
            if (androidReleaseKeyPassword != null) {
                keyPassword = androidReleaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (
                androidReleaseKeystorePath != null &&
                androidReleaseKeystorePassword != null &&
                androidReleaseKeyAlias != null &&
                androidReleaseKeyPassword != null
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable.add("NullSafeMutableLiveData")
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
        dependsOn("stageRouterTunnelBinary")

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            packageName = "LocalBridge"
            packageVersion = "1.0.0"
            description = "Same-network file transfer for Android and macOS."
            copyright = "Copyright 2026 Itamio Pupmann. All rights reserved."
            vendor = "AsdUnionTech"
            appResourcesRootDir.set(routerTunnelResourcesRootDir)

            targetFormats(TargetFormat.Dmg)

            macOS {
                bundleID = "asd.itamio.localbridge"
                dockName = "LocalBridge"
                iconFile.set(rootProject.file("branding/macos/ft.icns"))
            }
        }
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
