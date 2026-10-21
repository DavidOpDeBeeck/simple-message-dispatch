plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.apache.activemq:artemis-jakarta-client:2.42.0")
    testImplementation("org.testcontainers:testcontainers-activemq:2.0.3")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
