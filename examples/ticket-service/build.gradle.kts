plugins {
    java
    `java-test-fixtures`
    id("org.springframework.boot") version "4.0.3"
}

group = "app.dodb"
version = libs.versions.smd.get()

java {
    sourceCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
    withJavadocJar()
}

val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

val acceptanceTest = sourceSets.create("acceptanceTest") {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
configurations[acceptanceTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[acceptanceTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(libs.smd.spring.boot.starter)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.smd.test)
    testImplementation(libs.smd.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.test)

    add(integrationTest.implementationConfigurationName, libs.testcontainers.jdbc)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.postgresql)

    add(acceptanceTest.implementationConfigurationName, libs.testcontainers.jdbc)
    add(acceptanceTest.implementationConfigurationName, libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

val acceptanceTestTask = tasks.register<Test>("acceptanceTest") {
    description = "Runs acceptance tests"
    group = "verification"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    shouldRunAfter(integrationTestTask)
}

tasks.named("check") {
    dependsOn(integrationTestTask, acceptanceTestTask)
}
