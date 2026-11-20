import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val paperCoordinates = Regex("""^(\d+(?:\.\d+)*)\.build\.(\d+)(?:-.*)?$""")
    .matchEntire(paperApiVersion)
    ?: error("Unsupported paperApiVersion '$paperApiVersion'; expected <mc-version>.build.<build>[-suffix]")
val paperMcVersion = paperCoordinates.groupValues[1]
val paperBuildNumber = paperCoordinates.groupValues[2]
val paperServerSha = providers.gradleProperty("paperServerSha256").get().lowercase()
val integrationPaperJarProvider = layout.buildDirectory.file("integration-paper/paper-$paperMcVersion-$paperBuildNumber.jar")
val paperJarOverride = providers.gradleProperty("integration.paperJar")

dependencies {
    implementation(project(":core"))
    implementation(project(":hooks:luckperms"))
    implementation(project(":hooks:spark"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val integrationServerDirProvider = layout.buildDirectory.dir("integration-server")
val luckPermsPluginJarProvider = layout.buildDirectory.file("integration-libs/LuckPerms-Bukkit.jar")

val downloadLuckPerms = tasks.register("downloadLuckPerms") {
    val out = luckPermsPluginJarProvider.get().asFile
    val expectedSha = providers.gradleProperty("luckPermsSha256").get().lowercase()
    outputs.file(out)
    doLast {
        fun sha256(file: java.io.File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        // Verify any cached jar too; re-download if missing or tampered with.
        if (out.exists() && sha256(out) == expectedSha) return@doLast
        if (out.exists()) {
            logger.lifecycle("LuckPerms jar checksum mismatch; re-downloading")
            out.delete()
        }
        out.parentFile.mkdirs()
        val tmp = out.resolveSibling(out.name + ".download")
        val url = URI.create("https://download.luckperms.net/1678/bukkit/loader/LuckPerms-Bukkit-5.5.91.jar").toURL()
        url.openStream().use { input ->
            Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val actual = sha256(tmp)
        check(actual == expectedSha) {
            "LuckPerms jar SHA-256 mismatch: expected $expectedSha but got $actual. " +
                "Update luckPermsSha256 in gradle.properties only after verifying the new artifact."
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val downloadIntegrationPaper = tasks.register("downloadIntegrationPaper") {
    description = "Downloads the pinned Paper server jar used by the integration tests."
    group = "verification"
    val out = integrationPaperJarProvider.get().asFile
    val version = paperMcVersion
    val build = paperBuildNumber
    val expectedSha = paperServerSha
    inputs.property("paperVersion", version)
    inputs.property("paperBuild", build)
    inputs.property("sha256", expectedSha)
    outputs.file(out)
    doLast {
        fun sha256(file: java.io.File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        // Verify any cached jar too; re-download if missing or tampered with.
        if (out.exists() && sha256(out) == expectedSha) return@doLast
        if (out.exists()) {
            logger.lifecycle("Paper jar checksum mismatch; re-downloading")
            out.delete()
        }
        out.parentFile.mkdirs()
        val tmp = out.resolveSibling(out.name + ".download")
        val url = URI.create(
            "https://fill-data.papermc.io/v1/objects/$expectedSha/paper-$version-$build.jar"
        ).toURL()
        url.openStream().use { input ->
            Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val actual = sha256(tmp)
        check(actual == expectedSha) {
            "Paper jar SHA-256 mismatch: expected $expectedSha but got $actual. " +
                "Update paperServerSha256 and the pinned Paper build only after verifying the new artifact."
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val prepareIntegrationServer = tasks.register("prepareIntegrationServer") {
    val lpFile = layout.buildDirectory.file("integration-libs/LuckPerms-Bukkit.jar")
    val serverDir = layout.buildDirectory.dir("integration-server")
    val shadowJarFile = layout.buildDirectory.file("libs/plugin-${project.version}-all.jar")
    dependsOn("shadowJar", "downloadLuckPerms")
    inputs.file(shadowJarFile)
    inputs.file(lpFile)
    outputs.dir(serverDir)
    doLast {
        val dir = serverDir.get().asFile
        val pluginsDir = dir.resolve("plugins")
        val edenDir = pluginsDir.resolve("EdenMcp")
        edenDir.mkdirs()

        dir.resolve("eula.txt").writeText("eula=true\n")
        dir.resolve("server.properties").writeText(
            """
            online-mode=false
            server-port=25590
            query.port=25590
            rcon.port=25591
            enable-rcon=false
            spawn-protection=0
            max-players=5
            level-name=world
            """.trimIndent() + "\n"
        )

        val testPort = 18765
        edenDir.resolve("config.yml").writeText(
            """
            mcp:
              enabled: true
              mode: unsafe
              transport:
                type: http-sse
                bind: 127.0.0.1
                port: $testPort
                tls:
                  enabled: false
            hooks:
              luckperms:
                enabled: true
              spark:
                enabled: true
            commands:
              classification: []
            audit:
              log-tool-calls: true
              log-mode-changes: true
            """.trimIndent() + "\n"
        )

        val pluginJar = shadowJarFile.get().asFile
        Files.copy(pluginJar.toPath(), pluginsDir.resolve("EdenMcp.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)

        val lp = lpFile.get().asFile
        if (lp.exists()) {
            Files.copy(lp.toPath(), pluginsDir.resolve("LuckPerms.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against a provisioned Paper server (alias for :plugin:test)."
    group = "verification"
    dependsOn(tasks.test)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    test {
        dependsOn(prepareIntegrationServer)
        if (!paperJarOverride.isPresent) {
            dependsOn(downloadIntegrationPaper)
        }
        useJUnitPlatform()

        val paperJarPath = paperJarOverride.orElse(integrationPaperJarProvider.get().asFile.absolutePath)
        val serverDirPath = layout.buildDirectory.dir("integration-server").map { it.asFile.absolutePath }
        val javaBin = providers.provider { System.getProperty("java.home") + "/bin/java" }

        systemProperty("edenmcp.it.serverDir", serverDirPath.get())
        systemProperty("edenmcp.it.paperJar", paperJarPath.get())
        systemProperty("edenmcp.it.java", javaBin.get())
        systemProperty("edenmcp.it.mcpPort", "18765")
        systemProperty("edenmcp.it.mcpTls", "false")
        systemProperty("junit.jupiter.execution.timeout.default", "5m")

        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            showStandardStreams = true
        }
    }

    runServer {
        minecraftVersion(paperMcVersion)
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
