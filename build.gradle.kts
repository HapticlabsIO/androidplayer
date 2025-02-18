import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    alias(libs.plugins.jreleaser)
}

android {
    namespace = "io.hapticlabs.hapticlabsplayer"
    compileSdk = 34

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
            version = "1.0.0"

            pom {
                name = "Hapticlabs Player"
                description = "A module to play HLA and OGG haptic files on Android"
                url="https://hapticlabs.io"
                inceptionYear="2025"
                licenses {
                    license {
                        name="MIT License"
                        url="https://mit-license.org/"
                    }
                }


            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

jreleaser {
    project {
        inceptionYear = "2025"
        author("@robot-controller")
    }
    gitRootSearch = true
    signing {
        active = Active.ALWAYS
        armored = true
        verify = true
    }
    release {
        github {
            skipRelease = true
        }
    }
    deploy {
        maven {
            mavenCentral.create("sonatype") {
                active = Active.ALWAYS
                url = "https://central.sonatype.com/api/v1/publisher"
                stagingRepository(layout.buildDirectory.dir("staging-deploy").get().toString())
                setAuthorization("Basic")
                applyMavenCentralRules = false
                sign = true
                releaseRepository(false)
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.gson)
}