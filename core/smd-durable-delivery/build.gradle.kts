dependencies {
    api(project(":smd-api"))
    api(project(":smd-serialization"))

    implementation(libs.slf4j)

    testImplementation(libs.jackson.databind)
}
