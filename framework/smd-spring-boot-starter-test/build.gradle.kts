dependencies {
    api(project(":smd-api"))
    api(project(":smd-test"))
    api(project(":smd-spring-boot-starter"))

    api(platform(libs.spring.boot.dependencies))
    api(platform(libs.junit.bom))

    implementation(libs.spring.context)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.junit.jupiter)

    integrationTestImplementation(libs.spring.boot.starter.test)
}