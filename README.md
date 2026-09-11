# Simple Message Dispatch (SMD)

> **EXPERIMENTAL**: SMD is still in early development and may introduce breaking changes while the project remains in the `0.0.x` line.

Simple Message Dispatch (SMD) is a lightweight Java library for CQRS and event-driven applications. It provides framework-agnostic messaging primitives, optional event-store support, and Spring Boot
integration.

## Modules

| Module                         | Purpose                                                   | Guide                                    |
|--------------------------------|-----------------------------------------------------------|------------------------------------------|
| `smd-api`                      | Core command, query, event, metadata, and bus APIs        | [Core API](docs/core-api.md)             |
| `smd-event-store`              | PostgreSQL 15+ event store, polling, sequencing           | [Event Store Guide](docs/event-store.md) |
| `smd-test`                     | Test helpers and gateway/publisher stubs                  | [Testing Guide](docs/testing.md)         |
| `smd-spring-boot-starter`      | Spring Boot autoconfiguration and event-store integration | [Spring Boot Guide](docs/spring-boot.md) |
| `smd-spring-boot-starter-test` | Spring Boot test-scope stubs and lifecycle support        | [Testing Guide](docs/testing.md)         |

## Install

Add `mavenCentral()` and choose one of these starting points.

For Spring Boot, add the starter. It already exposes the core API and event-store module:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-spring-boot-starter:0.0.11")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.11")
}
```

Without Spring Boot, add the core API and opt into the event store only when durable delivery is required:

```kotlin
dependencies {
    implementation("app.dodb:smd-api:0.0.11")
    implementation("app.dodb:smd-event-store:0.0.11") // Optional
    testImplementation("app.dodb:smd-test:0.0.11")
}
```

Start with [Getting Started](docs/getting-started.md), or browse the [documentation index](docs/README.md).

## Examples

The [ticket service](examples/ticket-service/README.md) demonstrates a complete lifecycle with REST, JDBC projections, three event delivery modes, metadata lineage, and PostgreSQL event-store
processing.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history, upgrade notes, and breaking changes.

## License

This project is licensed under the [MIT License](LICENSE).
