# Simple Message Dispatch (SMD)

> **EXPERIMENTAL**: This library is in early development and **subject to breaking changes**. The version will remain **non-final (v0.0.x)** until it is stable.

**Simple Message Dispatch (SMD)** is a lightweight Java library designed to support **CQRS** and **event-driven** applications. It is **framework agnostic** by design, with dedicated support for *
*Spring Boot** provided via an integration module.

## Artifacts

| Artifact                       | Description                                |
|--------------------------------|--------------------------------------------|
| `smd-api`                      | Framework agnostic building blocks         |
| `smd-test`                     | Utilities for unit tests                   |
| `smd-spring-boot-starter`      | Spring Boot integration                    |
| `smd-spring-boot-starter-test` | Spring Boot testing support for unit tests |

## Gradle Setup

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-api:0.0.1")
    testImplementation("app.dodb:smd-test:0.0.1")
    implementation("app.dodb:smd-spring-boot-starter:0.0.1")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.1")
}
```

## Example Usage

Code examples will follow soon...

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for details.