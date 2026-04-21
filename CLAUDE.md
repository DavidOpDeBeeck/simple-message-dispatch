# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
./gradlew build

# Run unit tests (all modules)
./gradlew test

# Run integration tests (all modules)
./gradlew integrationTest

# Run both (same as build check)
./gradlew check

# Run a single test class
./gradlew :smd-api:test --tests "app.dodb.smd.api.command.AnnotatedCommandHandlerTest"
./gradlew :smd-spring-boot-starter:integrationTest --tests "app.dodb.smd.spring.event.EventBusIntegrationTest"

# Publish to local Maven repo
./gradlew publishToMavenLocal
```

Integration tests for the event store use Testcontainers (Docker required) and the `eventstore` Spring profile.

## Project Structure

Five Gradle subprojects mapped to two directories:

| Module                         | Path                                     | Purpose                                             |
|--------------------------------|------------------------------------------|-----------------------------------------------------|
| `smd-api`                      | `core/smd-api`                           | Framework-agnostic core (buses, handlers, channels) |
| `smd-event-store`              | `core/smd-event-store`                   | Event store (JDBC storage, polling, token tracking) |
| `smd-test`                     | `core/smd-test`                          | Test utilities (stubs, `SMDTestExtension`)          |
| `smd-spring-boot-starter`      | `framework/smd-spring-boot-starter`      | Spring Boot autoconfiguration                       |
| `smd-spring-boot-starter-test` | `framework/smd-spring-boot-starter-test` | Spring test scope support                           |

Each module has a standard `src/main`, `src/test`, and `src/integrationTest` source set.

## Architecture

### Message Types

There are three message types, each with a corresponding bus and gateway:

- **Command** (`Command<R>`) — dispatched via `CommandGateway`/`CommandBus`, returns a result `R`
- **Query** (`Query<R>`) — dispatched via `QueryGateway`/`QueryBus`, returns a result `R`
- **Event** (`Event`) — published via `EventPublisher`/`EventBus`, fan-out to all subscribed processing groups

All messages carry a `Metadata` record containing `Principal`, `Instant timestamp`, `MessageId parentMessageId`, and `Map<String, String> additionalProperties`.

### Handler Discovery & Annotation Model

Handlers are plain classes with annotated methods. The annotation drives which bus picks them up:

- `@CommandHandler` on a method → handled by `CommandBus`
- `@QueryHandler` on a method → handled by `QueryBus`
- `@EventHandler` on a method → handled by `EventBus`
- `@ProcessingGroup` on a class → groups event handlers for the event store channel

Handler method parameters are resolved by type from the message:

- The payload type (`Command<R>`, `Query<R>`, or `Event` subclass)
- `MessageId` — the message's ID
- `Metadata` — the full metadata object
- `Principal` — the principal from metadata
- `Instant` — the timestamp from metadata
- `@MetadataValue("key") String value` — extracts a key from `additionalProperties`

Handler classes are discovered via package scanning (`PackageBasedCommandHandlerLocator`, etc.), instantiated by `ObjectCreator` (Spring context in Spring Boot, constructor-based otherwise).

### Bus Interceptors

Each bus supports an interceptor chain (`CommandBusInterceptor`, `QueryBusInterceptor`, `EventInterceptor`). Interceptors receive the message and a `proceed()` call to continue the chain. In Spring
Boot, `TransactionalCommandBusInterceptor`, `TransactionalEventInterceptor`, and `TransactionalQueryBusInterceptor` are registered automatically at highest precedence.

### Event Channels

The `EventBus` dispatches to one or more `EventChannel` implementations:

- **Synchronous** (default) — handlers invoked on the publishing thread
- **Async-await** — handlers invoked on a thread pool, publisher awaits completion
- **Async-fire-and-forget** — publisher returns immediately, handlers invoked asynchronously
- **`EventStoreChannel`** (in `smd-event-store`) — defers event storage within the transaction (via `TransactionProvider.defer`), then polls the store per processing group using a
  `ScheduledExecutorService`

The `EventStoreChannel` uses a token-per-processing-group model (`TokenStore` / `smd_token_store` table) to track position. It includes gap detection, exponential backoff on failure, and
configurable max retries.

Event store DB schema is at `core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql`.

### Bus Builder Pattern

Buses are constructed via a spec/builder:

```java
// Framework-agnostic
CommandBusSpec.withDefaults()
    .commandHandlers(new PackageBasedCommandHandlerLocator(packages, objectCreator))
    .interceptors(interceptor)
    .create();

// With processing group configuration
EventBusSpec.withDefaults()
    .processingGroups(locator, spec -> {
        spec.processingGroup("notifications").async().await();
        spec.anyProcessingGroup().sync();
    })
    .create();
```

### Spring Boot Integration

Activate with `@EnableSMD` on a configuration class. This imports `SMDRegistrar` and `SMDConfiguration` autoconfiguration, which wires all three gateways, locators, and
transactional interceptors.

Enable the event store via `application.yml`:

```yaml
smd:
  event-store:
    enabled: true
    scheduling:
      enabled: true
      initial-delay: 10s
      polling-delay: 5s
      thread-pool-size: 1
    processing:
      max-retries: 3
      batch-size: 100
      gap-timeout: 5m
      retry-backoff:
        strategy: EXPONENTIAL  # FIXED | LINEAR | EXPONENTIAL
        initial-delay: 1s
        multiplier: 5.0
        max-delay: 5m
```

A `DataSource` bean is required when the event store is enabled.

### Test Utilities

- `SMDTestExtension` (in `smd-test`) — programmatic test helper that wires buses with stub providers; call `smd.send(command)`, `smd.send(event)`, `smd.getEvents()`, `smd.stubCommand(cmd, response)`,
  etc.
- `CommandGatewayStub`, `QueryGatewayStub`, `EventPublisherStub` — injectable stubs for unit tests
- `@EnableSMDStubs` + `SMDTestScopeLifecycleExtension` (in `smd-spring-boot-starter-test`) — Spring integration test support with per-test scope reset
