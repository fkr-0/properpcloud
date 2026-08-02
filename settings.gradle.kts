pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "properpcloud"

include(":app")
include(":core-model")
include(":metadata-online")
include(":metadata-tags")
include(":source-pcloud")
include(":source-webdav")
include(":desktop-app")
