plugins {
    java
    `maven-publish`
}

group = "app.dodb"
version = "0.0.1"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        withSourcesJar()
    }

    tasks.test {
        useJUnitPlatform()
    }

    dependencies {
        testImplementation(platform(rootProject.libs.junit.bom))
        testImplementation(rootProject.libs.junit.jupiter)
        testImplementation(rootProject.libs.assertj)
        testRuntimeOnly(rootProject.libs.junit.platform.launcher)
    }

    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])

                groupId = rootProject.group.toString()
                artifactId = project.name
                version = rootProject.version.toString()
            }
        }

        repositories {
            mavenLocal()
        }
    }
}