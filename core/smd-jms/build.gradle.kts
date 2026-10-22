dependencies {
    api(project(":smd-api"))
    api(project(":smd-event-store"))
    api(libs.jakarta.jms.api)

    implementation(libs.slf4j)

    testImplementation(project(":smd-test"))
    testImplementation(libs.jackson.databind)

    integrationTestImplementation(libs.artemis.jakarta.client)
    integrationTestImplementation(libs.testcontainers.activemq)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
}
