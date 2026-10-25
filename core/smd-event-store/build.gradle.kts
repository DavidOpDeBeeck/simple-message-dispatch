dependencies {
    api(project(":smd-api"))
    api(project(":smd-serialization"))

    implementation(libs.guava)
    implementation(libs.slf4j)

    compileOnly(libs.jackson.databind)

    testImplementation(libs.jackson.databind)
}
