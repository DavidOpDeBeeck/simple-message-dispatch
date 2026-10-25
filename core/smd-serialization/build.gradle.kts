dependencies {
    api(project(":smd-api"))

    compileOnly(libs.jackson.databind)

    testImplementation(libs.jackson.databind)
}
