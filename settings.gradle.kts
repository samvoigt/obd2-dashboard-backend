pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "obd2-dashboard-backend"

include(":archive")
include(":archive-gcp")
include(":live")
include(":registry")
include(":registry-firestore")
include(":replay")
include(":server")
include(":tools")
