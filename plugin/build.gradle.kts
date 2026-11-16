import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hooks:luckperms"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.+")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val integrationServerDirProvider = layout.buildDirectory.dir("integration-server")
val luckPermsPluginJarProvider = layout.buildDirectory.file("integration-libs/LuckPerms-Bukkit.jar")

val downloadLuckPerms = tasks.register("downloadLuckPerms") {
    val outFile = layout.buildDirectory.file("integration-libs/LuckPerms-Bukkit.jar")
    outputs.file(outFile)
    // Script-object capture in doLast is not configuration-cache friendly; task is safe to rerun.
    notCompatibleWithConfigurationCache("Downloads external file in a simple script action")
    doLast {
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        if (!out.exists() || out.length() == 0L) {
            val url = uri("https://download.luckperms.net/1678/bukkit/loader/LuckPerms-Bukkit-5.5.91.jar").toURL()
            url.openStream().use { input ->
                Files.copy(input, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

val prepareIntegrationServer = tasks.register("prepareIntegrationServer") {
    val lpFile = layout.buildDirectory.file("integration-libs/LuckPerms-Bukkit.jar")
    val serverDir = layout.buildDirectory.dir("integration-server")
    val shadowJarFile = layout.buildDirectory.file("libs/plugin-${project.version}-all.jar")
    val runDir = layout.projectDirectory.dir("run")
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
            commands:
              classification:
                - pattern: "^mcp mode set (safe|moderate|unsafe)$"
                  level: safe
            audit:
              log-tool-calls: true
              log-mode-changes: true
            """.trimIndent() + "\n"
        )

        // Copy runtime server files provisioned by run-paper (versions/libraries/cache/.paper) if available
        val run = runDir.asFile
        if (run.exists()) {
            listOf("versions", "libraries", "cache", ".paper").forEach { name ->
                val src = run.resolve(name)
                if (src.exists()) {
                    val dest = dir.resolve(name)
                    if (!dest.exists()) {
                        src.copyRecursively(dest, overwrite = true)
                    }
                }
            }
        }

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
        useJUnitPlatform()

        val paperJarPath = providers.gradleProperty("integration.paperJar")
            .orElse(providers.provider {
                val inServer = layout.buildDirectory.file("integration-server/versions/26.3/paper-26.3.jar").get().asFile
                if (inServer.exists()) inServer.absolutePath
                else {
                    val f = layout.projectDirectory.file("run/versions/26.3/paper-26.3.jar").asFile
                    if (f.exists()) f.absolutePath else ""
                }
            })
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
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
