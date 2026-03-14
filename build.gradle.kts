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

    // Optional persistence backends - consumers add these as needed
    compileOnly("redis.clients:jedis:5.1.0")
    compileOnly("org.apache.kafka:kafka-clients:3.7.0")

    testImplementation(kotlin("test"))
    testImplementation("redis.clients:jedis:5.1.0")
    testImplementation("org.apache.kafka:kafka-clients:3.7.0")
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
