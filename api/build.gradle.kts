plugins {
    `maven-publish`
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // JitPack serves multi-module artifacts under com.github.<user>.<repo>.
            groupId = "com.github.haha007.EdenMcp"
            artifactId = "edenmcp-api"
            pom {
                name.set("EdenMcp API")
                description.set("Public API for registering EdenMcp tools and hooks")
            }
        }
    }
}
