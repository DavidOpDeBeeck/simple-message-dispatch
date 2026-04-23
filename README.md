# Simple Message Dispatch (SMD)

> **EXPERIMENTAL**: SMD is still in early development and may introduce breaking changes while the project remains in the `0.0.x` line.

Simple Message Dispatch (SMD) is a lightweight Java library for CQRS and event-driven applications. It provides framework-agnostic messaging primitives, optional event-store support, and Spring Boot
integration.

## Modules

| Module                         | Purpose                                                   | Guide                                                    |
|--------------------------------|-----------------------------------------------------------|----------------------------------------------------------|
| `smd-api`                      | Core command, query, event, metadata, and bus APIs        | [Getting Started](docs/getting-started.md)               |
| `smd-event-store`              | JDBC event store, polling, token tracking, serialization  | [Event Store Guide](docs/event-store.md)                 |
| `smd-test`                     | Test helpers and gateway/publisher stubs                  | [Testing Guide](docs/testing.md)                         |
| `smd-spring-boot-starter`      | Spring Boot autoconfiguration and event-store integration | [Spring Boot Guide](docs/spring-boot.md)                 |
| `smd-spring-boot-starter-test` | Spring Boot test-scope stubs and lifecycle support        | [Spring Boot Testing Guide](docs/spring-boot-testing.md) |

## Install

Add `mavenCentral()` and choose the modules that match your setup:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-api:0.0.x")
    // Optional: durable event storage and polling
    implementation("app.dodb:smd-event-store:0.0.x")
    // Optional: Spring Boot autoconfiguration
    implementation("app.dodb:smd-spring-boot-starter:0.0.x")
    // Optional: framework-agnostic test helpers
    testImplementation("app.dodb:smd-test:0.0.x")
    // Optional: Spring Boot test support
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.x")
}
```

For the next step, start with [docs/getting-started.md](docs/getting-started.md).

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history, upgrade notes, and breaking changes.

## License

This project is licensed under the [MIT License](LICENSE).
