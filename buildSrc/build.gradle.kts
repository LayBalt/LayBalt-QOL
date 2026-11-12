plugins {
    `kotlin-dsl`
}

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    implementation("net.fabricmc:fabric-loom:1.17.19")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.20")

    // NoammAddons 6541bcf6: unit tests for the variant comments (src/test/kotlin/PreprocessingTest.kt).
    // Gradle does not run buildSrc tests during a normal build; run them with ./gradlew :buildSrc:test
    testImplementation(kotlin("test"))
}
