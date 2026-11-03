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
        // FrostWire publishes jlibtorrent here; harmless if Maven Central already has it.
        maven {
            url = uri("https://dl.frostwire.com/maven")
            content { includeGroup("com.frostwire") }
        }
    }
}
rootProject.name = "stream_buddy"
include(":app")
