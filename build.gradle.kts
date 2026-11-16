plugins {
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

subprojects {
    apply(plugin = "java-library")

    group = rootProject.property("group") as String
    version = rootProject.property("version") as String

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

