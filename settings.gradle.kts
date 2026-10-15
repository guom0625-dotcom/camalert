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
        // 카카오내비 SDK와 그 의존성(카카오 SDK common)
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-public/")
        maven("https://devrepo.kakao.com/nexus/content/groups/public/")
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "camalert"
include(":app")
