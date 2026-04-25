dependencies {
    api(project(":smd-api"))
    api(project(":smd-event-store"))

    api(platform(libs.spring.boot.dependencies))

    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor(libs.spring.boot.configuration.processor)

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.transaction)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.jackson.jdk8)
    implementation(libs.spring.jdbc)

    compileOnly(libs.postgresql)

    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.spring.boot.starter.jdbc)
}
