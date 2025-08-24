plugins {
    java
    `maven-publish`
    signing
}

group = "app.dodb"
version = "0.0.2"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        withSourcesJar()
        withJavadocJar()
    }

    dependencies {
        testImplementation(platform(rootProject.libs.junit.bom))
        testImplementation(rootProject.libs.junit.jupiter)
        testImplementation(rootProject.libs.assertj)
        testRuntimeOnly(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    val integrationTest by sourceSets.creating {
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }

    configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
    configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

    var integrationTestTask = tasks.register<Test>("integrationTest") {
        description = "Runs integration tests"
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        shouldRunAfter(tasks.test)
    }

    tasks.named("check") {
        dependsOn(integrationTestTask)
    }

    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])

                groupId = rootProject.group.toString()
                artifactId = project.name
                version = rootProject.version.toString()

                pom {
                    name.set("Simple Message Dispatch")
                    description.set("A lightweight Java library designed to support CQRS and event-driven applications.")
                    url.set("https://github.com/DavidOpDeBeeck/simple-message-dispatch")
                    inceptionYear.set("2025")
                    licenses {
                        license {
                            name.set("GPL-3.0 license")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                        }
                    }
                    developers {
                        developer {
                            id.set("davidodb")
                            name.set("David Op de Beeck")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/DavidOpDeBeeck/simple-message-dispatch.git")
                        developerConnection.set("scm:git:ssh://github.com/DavidOpDeBeeck/simple-message-dispatch.git")
                        url.set("https://github.com/DavidOpDeBeeck/simple-message-dispatch")
                    }
                }
            }
        }

        repositories {
            mavenLocal()
            repositories {
                maven {
                    name = "sonatype"
                    url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    credentials {
                        username = findProperty("sonatypeUsername") as String
                        password = findProperty("sonatypePassword") as String
                    }
                }
            }
        }
    }

    signing {
        useInMemoryPgpKeys(
            findProperty("signing.key") as String?,
            findProperty("signing.password") as String?
        )
        sign(publishing.publications["mavenJava"])
    }
}