dependencies {
    api(project(":smd-api"))
    api(project(":smd-serialization"))

    implementation(libs.guava)
    implementation(libs.reflections)
    implementation(libs.slf4j)

    testImplementation(project(":smd-test"))
    testImplementation(libs.jackson.databind)
}
