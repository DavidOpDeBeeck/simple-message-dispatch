dependencies {
    api(project(":smd-api"))

    api(platform(libs.spring.dependencies))

    implementation(libs.spring.context)
    implementation(libs.spring.autoconfigure)
}