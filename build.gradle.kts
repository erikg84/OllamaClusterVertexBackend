plugins {
    kotlin("jvm") version "2.1.10"
    application
}

group = "com.dallaslabs"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val vertxVersion = "4.5.3"
val jacksonVersion = "2.15.0"
val logbackVersion = "1.4.7"

dependencies {
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.10.1")

    // Vert.x
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-web-client:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-config:$vertxVersion")
    implementation("io.vertx:vertx-config-yaml:$vertxVersion")

    // Jackson for JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("io.github.microutils:kotlin-logging:3.0.5")

    // UUID generation
    implementation("com.fasterxml.uuid:java-uuid-generator:4.2.0")
    implementation("io.vertx:vertx-web-api-contract:4.5.1")
    testImplementation(kotlin("test"))
    implementation("io.vertx:vertx-mongo-client:4.4.4")
}

application {
    mainClass.set("com.dallaslabs.MainKt")  // Replace with your actual main class path
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
