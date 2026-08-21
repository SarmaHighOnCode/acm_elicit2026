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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "setu"

// :core and :sim are pure JVM on purpose. They are the half of this project that can be built
// and tested with no Android SDK and no phone attached, which is what lets two people work in
// parallel without blocking each other.
include(":core")
include(":sim")
include(":app")
