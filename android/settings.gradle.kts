pluginManagement {
    repositories {
        google()
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

rootProject.name = "BiliNote"

include(":app")
include(":core:network")
include(":core:database")
include(":core:designsystem")
include(":feature:home")
include(":feature:batch")
include(":feature:library")
include(":feature:reader")
include(":feature:settings")
include(":benchmark")
include(":baselineprofile")
