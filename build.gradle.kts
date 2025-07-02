import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    alias(libs.plugins.jreleaser)
    signing
}

android {
    namespace = "io.hapticlabs.hapticlabsplayer"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.hapticlabs"
            artifactId = "hapticlabsplayer"
            version = "0.4.1"

            pom {
                name = "Hapticlabs Player"
                version = "0.4.1"
                description = "A module to play HLA and OGG haptic files on Android"
                url = "https://github.com/HapticlabsIO/androidplayer"
                inceptionYear = "2025"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/HapticlabsIO/androidplayer/blob/main/LICENSE.txt"
                    }
                }
                developers {
                    developer {
                        id = "robot-controller"
                        name = "Michi"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/HapticlabsIO/androidplayer.git"
                    developerConnection = "scm:git:git://github.com/HapticlabsIO/androidplayer.git"
                    url = "https://github.com/HapticlabsIO/androidplayer"
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        maven {
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

jreleaser {
    project {
        version = "0.4.1"
        description = "A module to play HLA and OGG haptic files on Android"
        copyright = "Copyright (c) 2025 Hapticlabs GmbH"
    }
    signing {
        active = Active.ALWAYS
        armored = true
    }
    deploy {
        maven {
            mavenCentral.create("sonatype") {
                active = Active.ALWAYS
                url = "https://central.sonatype.com/api/v1/publisher"
                stagingRepository(layout.buildDirectory.dir("staging-deploy").get().toString())
                setAuthorization("Basic")
                namespace = "io.hapticlabs"
                applyMavenCentralRules = false
                sign = true
                checksums = true
                sourceJar = true
                javadocJar = true
                retryDelay = 60
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.mediarouter)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.gson)
}