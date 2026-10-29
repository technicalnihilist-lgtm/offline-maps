pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "offline-maps"
include(":app")
include(":core:common")
include(":core:ui")
include(":core:data")
include(":core:map")
include(":core:offline")
include(":core:search")
include(":core:routing")
include(":core:location")
