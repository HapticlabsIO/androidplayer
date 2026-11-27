pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Also the Maven artifactId, and what consuming composite builds substitute
rootProject.name = "hapticlabsplayer"

// To build against a local hapticlabsplayer-core instead of the published one, set the Gradle
// property hapticlabsAndroidPackageDir to a checkout of the HapticlabsAndroidPackage repository,
// such as .. where this is its submodule, whose hapticlabsplayer-core submodule Gradle then
// substitutes io.hapticlabs:hapticlabsplayer-core with. Builds including this one, such as the
// example app's, choose the core themselves
val hapticlabsAndroidPackageDir = providers.gradleProperty("hapticlabsAndroidPackageDir").orNull
if (hapticlabsAndroidPackageDir != null && gradle.parent == null) {
    includeBuild("$hapticlabsAndroidPackageDir/hapticlabsplayer-core")
}
