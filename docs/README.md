# SMD Documentation

Simple Message Dispatch (SMD) is a Java library for commands, queries, and events. Start with Spring Boot for automatic wiring, or use the core API when you want to construct the buses yourself.

## Requirements

- Java 25
- Spring Boot 4 for Spring applications
- PostgreSQL 15 or newer when using the built-in event store

## First Use

If you are new to SMD, follow this path:

1. [Getting Started](getting-started.md) builds and runs one ticket command, query, and event flow.
2. [Testing](testing.md) verifies the handler flow with isolated repositories and SMD test support.
3. [Spring Boot](spring-boot.md) explains metadata, interceptors, and the event-delivery choices.
4. [Event Store](event-store.md) adds durable PostgreSQL-backed event processing.

## Reference Guides

| Goal                                            | Guide                                 |
|-------------------------------------------------|---------------------------------------|
| Add SMD to a Spring Boot application            | [Getting Started](getting-started.md) |
| Understand messages or wire SMD without Spring  | [Core API](core-api.md)               |
| Customize Spring Boot wiring and event delivery | [Spring Boot](spring-boot.md)         |
| Store and process events durably                | [Event Store](event-store.md)         |
| Test handlers and message flows                 | [Testing](testing.md)                 |

The project is experimental and may introduce breaking changes while it remains in the `0.0.x` line. Check the [changelog](../CHANGELOG.md) when upgrading.
