dependencies {
    val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
