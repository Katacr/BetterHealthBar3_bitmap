plugins {
    id("conventions-standard")
}

dependencies {
    implementation("io.papermc.paper:paper-api:${property("minecraft_version")}-R0.1-SNAPSHOT")
    api(libs.bundles.library.download)
}
// ("io.papermc.paper:paper-api:${property("minecraft_version")}-R0.1-SNAPSHOT")
// ("io.papermc.paper:paper-api:${property("minecraft_version")}.build.+")