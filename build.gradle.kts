plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.codecolorizer"
version = "1.10.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IDE utilisé pour compiler et pour "runIde". Le plugin fonctionne aussi
        // dans Ultimate, WebStorm, PyCharm, etc. (il ne dépend que de la plateforme).
        intellijIdeaCommunity("2024.3")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    // Publication sur le JetBrains Marketplace : `.\gradlew.bat publishPlugin`
    // Le jeton est lu dans la variable d'environnement PUBLISH_TOKEN (jamais écrit dans un fichier).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
