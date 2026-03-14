dependencies {
    api(project(":smd-api"))

    implementation(libs.guava)
    implementation(libs.slf4j)

    compileOnly(libs.jackson.databind)
}
