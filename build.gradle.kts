plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    `maven-publish`
}

group = "io.github.drain3"
version = "0.9.11"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // Redis persistence via Lettuce
    compileOnly("io.lettuce:lettuce-core:6.3.2.RELEASE")

    testImplementation(kotlin("test"))
    testImplementation("io.lettuce:lettuce-core:6.3.2.RELEASE")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Drain3 Kotlin")
                description.set("Kotlin port of Drain3 - online log template miner based on the Drain algorithm")
                url.set("https://github.com/drain3-kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
