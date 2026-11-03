# SMD: Setup

## 2. Setup

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-spring-boot-starter:0.0.13")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.13")
}
```

The starter includes the core API, the event store, durable delivery, serialization, and OpenTelemetry. The test starter includes `smd-test` and `smd-event-store-test`.

The event store and durable delivery need PostgreSQL 15+, a `DataSource`, and their schemas:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}
```

Copy each schema into a versioned Flyway migration. SMD never applies them:

| Feature          | Schema                                                                                                                                               |
|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| Event store      | https://github.com/DavidOpDeBeeck/simple-message-dispatch/blob/main/core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql             |
| Durable delivery | https://github.com/DavidOpDeBeeck/simple-message-dispatch/blob/main/core/smd-durable-delivery/src/main/resources/db/smd/durable-delivery-schema.sql |

Use the schema of the tag that matches the SMD version, for example `blob/0.0.13/...`, when `main` has moved on. Then enable the features:

```yaml
smd:
  event-store:
    enabled: true        # Required for @Aggregate and for groups on eventStore.outlet()
  durable-delivery:
    enabled: true        # Required for durableDelivery.dispatcher(...)
```

Once the event store is enabled, it stores **every** event published through the default publisher, whether or not a group reads it from the store.

The event serializer is built from the `JsonMapper.Builder` of Spring Boot's Jackson auto-configuration. A context without `@EnableAutoConfiguration`, such as a test
that loads one layer configuration, fails at startup with a missing `EventSerializer` bean when the event store or durable delivery is enabled: add
`@EnableAutoConfiguration` ([12.3](testing.md#123-driven-adapters)).
