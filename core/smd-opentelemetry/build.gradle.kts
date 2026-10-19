dependencies {
    api(project(":smd-api"))
    api(libs.opentelemetry.api)

    testImplementation(libs.opentelemetry.sdk.testing)
}
