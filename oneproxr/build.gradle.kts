import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

val publishVersion = providers.gradleProperty("PUBLISH_VERSION")
    .orElse(providers.environmentVariable("PUBLISH_VERSION"))
    .orElse("0.1.0-SNAPSHOT")

val githubPackagesRepository = providers.gradleProperty("GITHUB_PACKAGES_REPOSITORY")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))

val githubPackagesUsername = providers.gradleProperty("GITHUB_PACKAGES_USERNAME")
    .orElse(providers.gradleProperty("gpr.user"))
    .orElse(providers.environmentVariable("GITHUB_ACTOR"))

val githubPackagesToken = providers.gradleProperty("GITHUB_PACKAGES_TOKEN")
    .orElse(providers.gradleProperty("gpr.key"))
    .orElse(providers.environmentVariable("GITHUB_TOKEN"))

android {
    namespace = "io.onepro.xr"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.onepro"
            artifactId = "oneproxr"
            version = publishVersion.get()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("oneproxr")
                description.set("Android library for XREAL One Pro XR sensor streaming and head tracking")
                url.set(
                    "https://github.com/${
                        githubPackagesRepository.orElse("UNCONFIRMED/UNCONFIRMED").get()
                    }"
                )
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
            }
        }
    }

    repositories {
        if (githubPackagesRepository.isPresent) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${githubPackagesRepository.get()}")
                credentials {
                    username = githubPackagesUsername.orNull
                    password = githubPackagesToken.orNull
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
