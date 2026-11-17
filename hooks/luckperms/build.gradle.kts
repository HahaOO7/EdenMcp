dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
    compileOnly("net.luckperms:api:5.4")
}
