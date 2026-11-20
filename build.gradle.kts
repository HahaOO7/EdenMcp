plugins {
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

subprojects {
    pluginManager.apply("java-library")

    group = rootProject.property("group") as String
    version = rootProject.property("version") as String

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies.add("compileOnly", "org.jetbrains:annotations:26.1.0")

    configurations.configureEach {
        resolutionStrategy.force(
            "com.google.guava:guava:33.8.0-jre",
            "org.apache.logging.log4j:log4j-api:2.26.1",
            "org.codehaus.plexus:plexus-utils:3.6.1",
            "org.apache.commons:commons-lang3:3.18.0"
        )
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

