import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20-Beta2"
    id("com.gradleup.shadow") version "9.6.0"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "numunun"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.199-stable")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.117.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.133-stable")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

// Kotlin 표준 라이브러리를 jar 안에 같이 넣는다.
tasks.shadowJar {
    archiveBaseName.set("Ouroboros")
    archiveClassifier.set("")
}

tasks.jar { enabled = false }
tasks.build { dependsOn(tasks.shadowJar) }

tasks.runServer {
    minecraftVersion("26.3")
}
