# Simple Message Dispatch (SMD)

> **EXPERIMENTAL**: SMD is still in early development and may introduce breaking changes while the project remains in the `0.0.x` line.

Simple Message Dispatch (SMD) is a lightweight Java library for CQRS and event-driven applications. It provides framework-agnostic messaging primitives, optional event-sourcing support, and Spring Boot
integration.

## Modules

| Module                         | Purpose                                                     | Guide                                                             |
|--------------------------------|-------------------------------------------------------------|-------------------------------------------------------------------|
| `smd-api`                      | Core command, query, event, metadata, and bus APIs          | [Core API](docs/core-api.md)                                      |
| `smd-serialization`            | Event serializers, type resolvers, and the Jackson module   | [Event Sourcing](docs/event-store.md#use-stable-event-type-names) |
| `smd-event-store`              | Event-sourced aggregates and the PostgreSQL 15+ event store | [Event Sourcing](docs/event-store.md)                             |
| `smd-event-store-test`         | Given-when-then aggregate test fixture                      | [Event Sourcing](docs/event-store.md#test-aggregates)             |
| `smd-test`                     | Test helpers and gateway/publisher stubs                    | [Testing Guide](docs/testing.md)                                  |
| `smd-opentelemetry`            | OpenTelemetry spans for every bus invocation                | [OpenTelemetry](docs/opentelemetry.md)                            |
| `smd-durable-delivery`         | PostgreSQL durable event dispatcher                         | [Durable Delivery](docs/durable-delivery.md)                      |
| `smd-spring-boot-starter`      | Spring Boot autoconfiguration                               | [Spring Boot Guide](docs/spring-boot.md)                          |
| `smd-spring-boot-starter-test` | Spring Boot test-scope stubs and lifecycle support          | [Testing Guide](docs/testing.md)                                  |

## Install

Add `mavenCentral()` and choose one of these starting points.

For Spring Boot, add the starter. It already exposes the core API, event-sourcing, durable-delivery, and OpenTelemetry modules, and its test module exposes `smd-test` and `smd-event-store-test`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-spring-boot-starter:0.0.13")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.13")
}
```

Without Spring Boot, add the core API and opt into the event store for event sourcing, durable delivery for at-least-once event delivery, and OpenTelemetry for tracing:

```kotlin
dependencies {
    implementation("app.dodb:smd-api:0.0.13")
    implementation("app.dodb:smd-event-store:0.0.13") // Optional
    implementation("app.dodb:smd-opentelemetry:0.0.13") // Optional
    implementation("app.dodb:smd-durable-delivery:0.0.13") // Optional
    testImplementation("app.dodb:smd-test:0.0.13")
    testImplementation("app.dodb:smd-event-store-test:0.0.13") // Optional
}
```

Start with [Getting Started](docs/getting-started.md), or browse the [documentation index](docs/README.md).

To let a coding agent build with SMD, copy [`.agents/skills/smd`](.agents/skills/smd/SKILL.md) from the tag of your SMD version into your application's skills
directory, such as `.agents/skills/` or `.claude/skills/`. Leave out its `evals/`: they test the skill against the ticket service.

To watch an application's commands, queries, and events live, see [SMD Monitor](https://github.com/DavidOpDeBeeck/smd-monitor), an optional monitor built on `smd-opentelemetry`.

## Examples

The [ticket service](examples/ticket-service/README.md) demonstrates a complete lifecycle with REST, JDBC projections, four event delivery modes, metadata lineage, and PostgreSQL event-store
processing.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history, upgrade notes, and breaking changes.

## License

This project is licensed under the [MIT License](LICENSE).
