plugins {
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hooks:spark"))
    implementation(project(":hooks:luckperms"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

tasks {
    build {
        dependsOn(shadowJar)
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
