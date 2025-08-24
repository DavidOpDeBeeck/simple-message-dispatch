rootProject.name = "simple-message-dispatch"

include("smd-api")
include("smd-spring-boot-starter")
include("smd-spring-boot-starter-test")
include("smd-test")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
    versionCatalogs {
        create("libs") {
            version("guava", "33.4.8-jre")
            version("guice", "7.0.0")
            version("reflections", "0.10.2")
            version("junit", "5.12.2")
            version("assertj", "3.27.3")
            version("spring", "3.5.0")

            library("guava", "com.google.guava", "guava").versionRef("guava")
            library("guice", "com.google.inject", "guice").versionRef("guice")
            library("reflections", "org.reflections", "reflections").versionRef("reflections")
            library("junit.bom", "org.junit", "junit-bom").versionRef("junit")
            library("junit.jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()
            library("assertj", "org.assertj", "assertj-core").versionRef("assertj")
            library("spring.dependencies", "org.springframework.boot", "spring-boot-dependencies").versionRef("spring")
            library("spring.context", "org.springframework", "spring-context").withoutVersion()
            library("spring.autoconfigure", "org.springframework.boot", "spring-boot-autoconfigure").withoutVersion()
            library("spring.test", "org.springframework.boot", "spring-boot-starter-test").withoutVersion()
        }
    }
}
