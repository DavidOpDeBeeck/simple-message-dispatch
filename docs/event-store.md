# Event Store Guide

## What It Does

`smd-event-store` provides an `EventStoreChannel` that stores events in JDBC-backed storage and delivers them later to event handlers by processing group.

It is useful when you need:

- durable event publication
- polling-based delivery
- retry and gap handling
- explicit per-group token tracking

## Framework-Agnostic Setup

You need:

- `ConnectionProvider`
- `TransactionProvider`
- `EventStorage`
- `TokenStore`
- `EventSerializer`

```java
var eventStoreChannel = new EventStoreChannel(
        EventStoreChannelConfig.withDefaults()
                .transactionProvider(transactionProvider)
                .eventStorage(new JdbcEventStorage(connectionProvider))
                .tokenStore(new JdbcTokenStore(connectionProvider))
                .eventSerializer(new JacksonEventSerializer(objectMapper))
                .build()
);
```

Route event handlers to that channel:

```java
var eventBus = EventBusSpec.withDefaults()
        .processingGroups(locator, spec -> spec.anyProcessingGroup().channel(eventStoreChannel))
        .create();
```

## Spring Boot Setup

Before enabling the event store, make sure your application already has:

- a `DataSource`
- the event-store schema applied to that database

The schema file is [core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql](../core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql).

Then enable the event store:

```yaml
smd:
    event-store:
        enabled: true
```

Enabling the module creates the event-store beans. It does not route any handlers automatically.

You still must route the desired processing groups to `EventStoreChannel`:

```java

@Bean
public ProcessingGroupsConfigurer processingGroupsConfigurer(EventStoreChannel eventStoreChannel) {
    return spec -> spec.processingGroup("accounts").channel(eventStoreChannel);
}
```

One possible Spring Boot configuration:

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
                strategy: EXPONENTIAL
                initial-delay: 1s
                multiplier: 5.0
                max-delay: 5m
```

This example pins `thread-pool-size` to `1` for simple, predictable polling. If you omit it, SMD uses the library default scheduler size.

## Retry Backoff Strategies

`smd.event-store.processing.retry-backoff.strategy` supports three modes. Each mode has its own required settings:

- `EXPONENTIAL` uses `initial-delay`, `multiplier`, and `max-delay`
- `FIXED` uses `fixed-delay`
- `LINEAR` uses `initial-delay`, `increment`, and `max-delay`

### Exponential

```yaml
smd:
    event-store:
        processing:
            retry-backoff:
                strategy: EXPONENTIAL
                initial-delay: 1s
                multiplier: 5.0
                max-delay: 5m
```

### Fixed

```yaml
smd:
    event-store:
        processing:
            retry-backoff:
                strategy: FIXED
                fixed-delay: 30s
```

### Linear

```yaml
smd:
    event-store:
        processing:
            retry-backoff:
                strategy: LINEAR
                initial-delay: 1s
                increment: 5s
                max-delay: 1m
```

If a strategy is selected without the properties it needs, event-store configuration fails fast when Spring Boot creates the channel configuration.

If you use Flyway or Liquibase, apply the schema before the application starts polling.

## Event Type Resolution

By default, `JacksonEventSerializer` uses `ClassNameEventTypeResolver`, which stores the event class name as the event type.

For long-lived stores, prefer a stable application-level mapping by providing your own `EventTypeResolver`:

```java
var serializer = new JacksonEventSerializer(objectMapper, new EventTypeResolver() {
    @Override
    public String eventTypeFor(Event event) throws EventTypeResolutionException {
        if (event instanceof AccountCreated) {
            return "account-created";
        }
        throw new EventTypeResolutionException("Unknown event: " + event.getClass().getName());
    }

    @Override
    public Class<? extends Event> eventClassFor(String eventType) throws EventTypeResolutionException {
        if ("account-created".equals(eventType)) {
            return AccountCreated.class;
        }
        throw new EventTypeResolutionException("Unknown event type: " + eventType);
    }
});
```

In Spring Boot, you can override `EventTypeResolver` or replace the `EventSerializer` bean entirely.

## Processing Guarantees

The event store tracks progress per processing group through a token.

- token claiming prevents concurrent processing of the same group
- gap detection pauses progress when sequence numbers are missing
- retry backoff delays retries after failures
- retry exhaustion marks a message as abandoned

If a handler fails after producing side effects, SMD rolls back the current transaction before marking the message as failed or abandoned in a separate transaction.

If a batch partially succeeds, SMD rolls back the batch and retries only the successful prefix so token progress can still be recorded safely.

## Configuration Validation

The channel validates configuration values when it is built:

- retry counts, gap timeouts, and delay durations must be zero or greater
- polling delay must be greater than zero
- batch size must be greater than zero
- retry multiplier must be greater than zero

## Operational Assumptions

The current JDBC token-claiming implementation is PostgreSQL-oriented:

- it uses row locking with `FOR UPDATE SKIP LOCKED`
- it uses advisory locks to coordinate token creation/claiming

Treat PostgreSQL as the supported database shape for multi-instance polling unless you are intentionally replacing the persistence pieces.

## Schema Migration

The schema lives at [core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql](../core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql).

### Flyway

Copy the schema into a migration or include `classpath:db/smd` in Flyway locations.

```yaml
spring:
    flyway:
        locations:
            - classpath:db/migration
            - classpath:db/smd
```

### Liquibase

Include the schema through `sqlFile` in your Liquibase changelog.

## Related Docs

- [Getting Started](getting-started.md)
- [Spring Boot Guide](spring-boot.md)
- [Testing Guide](testing.md)
- [Spring Boot Testing Guide](spring-boot-testing.md)
