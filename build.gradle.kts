import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation(kotlin("test"))

    intellijPlatform {
        intellijIdea("2026.2") {
            type = IntelliJPlatformType.IntellijIdeaUltimate
        }
        bundledPlugin("com.intellij.modules.jcef")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("intellij.ssh.plugin")
        testFramework(TestFrameworkType.Platform)
        testBundledModule("com.intellij.modules.ultimate")
    }
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
}
