rootProject.name = "simple-message-dispatch"

include("smd-api")
include("smd-event-store")
include("smd-test")
include("smd-spring-boot-starter")
include("smd-spring-boot-starter-test")

project(":smd-api").projectDir = file("core/smd-api")
project(":smd-event-store").projectDir = file("core/smd-event-store")
project(":smd-test").projectDir = file("core/smd-test")
project(":smd-spring-boot-starter").projectDir = file("framework/smd-spring-boot-starter")
project(":smd-spring-boot-starter-test").projectDir = file("framework/smd-spring-boot-starter-test")