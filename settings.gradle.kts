rootProject.name = "simple-message-dispatch"

include("smd-api")
include("smd-serialization")
include("smd-event-store")
include("smd-test")
include("smd-opentelemetry")
include("smd-durable-delivery")
include("smd-spring-boot-starter")
include("smd-spring-boot-starter-test")

includeBuild("examples/ticket-service")

project(":smd-api").projectDir = file("core/smd-api")
project(":smd-serialization").projectDir = file("core/smd-serialization")
project(":smd-event-store").projectDir = file("core/smd-event-store")
project(":smd-test").projectDir = file("core/smd-test")
project(":smd-opentelemetry").projectDir = file("core/smd-opentelemetry")
project(":smd-durable-delivery").projectDir = file("core/smd-durable-delivery")
project(":smd-spring-boot-starter").projectDir = file("framework/smd-spring-boot-starter")
project(":smd-spring-boot-starter-test").projectDir = file("framework/smd-spring-boot-starter-test")