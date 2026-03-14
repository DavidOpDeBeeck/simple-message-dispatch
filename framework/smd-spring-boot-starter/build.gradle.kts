dependencies {
    api(project(":smd-api"))
    api(project(":smd-event-store"))

    api(platform(libs.spring.dependencies))

    annotationProcessor(platform(libs.spring.dependencies))
    annotationProcessor(libs.spring.configuration.processor)

    implementation(libs.spring.autoconfigure)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.jackson.jdk8)
    implementation(libs.spring.jdbc)

    compileOnly(libs.postgresql)

    integrationTestImplementation(libs.spring.test)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.junit)
}