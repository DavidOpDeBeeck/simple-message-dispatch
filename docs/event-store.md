# Event Store Guide

## What It Does

`smd-event-store` provides an `EventStoreChannel` for durable, polling-based event delivery. It includes PostgreSQL 15+ implementations for storing events and tracking processing progress.

It is useful when you need:

- durable event publication
- polling-based delivery
- retry and gap handling
- explicit per-group token tracking
- independent retry and ordering state for event subjects

## Framework-Agnostic Setup

A framework-agnostic setup using the built-in PostgreSQL persistence needs:

- `ConnectionProvider`
- `TransactionProvider`
- `EventStorage`
- `TokenStore`
- `EventSubjectSequenceStore`
- `EventSerializer`

SMD provides PostgreSQL implementations for the three persistence abstractions:

```java
var eventStoreChannel = new EventStoreChannel(
        EventStoreChannelConfig.withDefaults()
                .transactionProvider(transactionProvider)
                .eventStorage(new PostgresEventStorage(connectionProvider))
                .tokenStore(new PostgresTokenStore(connectionProvider))
                .eventSequenceStore(new PostgresEventSubjectSequenceStore(connectionProvider))
                .eventSerializer(new JacksonEventSerializer(objectMapper))
                .build()
);
```

Route event handlers to that channel:

```java
var eventBus = EventBusSpec.withDefaults()
        .processingGroups(locator, spec -> spec.anyProcessingGroup()
                .channel(eventStoreChannel))
        .create();
```

`EventStoreChannel` is directly subscribable. Each event's optional subject ID determines its logical sequence; processing groups do not configure sequence resolvers.
See [Event Subjects and Sequencing](#event-subjects-and-sequencing).

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
    return spec -> spec.processingGroup("accounts")
            .channel(eventStoreChannel);
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
                base-delay: 1s
                multiplier: 5.0
                max-delay: 5m
```

This example pins `thread-pool-size` to `1` for simple, predictable polling. If you omit it, SMD uses the library default scheduler size.

## Event Subjects and Sequencing

An `Event` can declare a non-blank subject ID. `EventMessage` captures that value when the event is published, and the event store persists it separately from the payload and metadata:

```java
public record AccountCreated(UUID accountId, String name) implements Event {
    @SubjectId
    public String accountSubject() {
        return "account:" + accountId;
    }
}
```

Any non-null value can supply the subject. The framework captures its `toString()` representation:

```java
public record TenantCreated(@SubjectId UUID tenantId, String name) implements Event {
}
```

Use only values with a stable, domain-defined `toString()` representation. Default object identity strings, arrays, unordered collections, and debug-oriented representations are not suitable because
the captured value is persisted as sequencing identity. For namespacing or more complex computation, annotate a method returning the final value.

Subject accessors are discovered once per event class and cached. An event without `@SubjectId`, an accessor returning `null`, or an accessor returning `Optional.empty()` has no subject ID and joins the
global subjectless sequence for each processing group. Publication fails immediately when annotations are duplicated, an accessor has an invalid signature, or a resolved subject ID is blank.

The exact subject ID identifies the logical sequence for every processing group. Subject IDs are not implicitly scoped by event type, so applications should namespace values such as `account:123` and
`order:123` when those events must not share retry state. All events without a subject ID share one global sequence. A literal value such as `$global` has no special framework behavior and identifies a
normal named subject.

When a handler fails, its transaction is rolled back and the failure is recorded for that subject. During backoff, later polls may still handle other subjects in the fetched window. Once retries are
exhausted, the subject becomes `ABANDONED`; later events with that subject are skipped without invoking the handler, while other subjects continue normally.

`smd.event-store.processing.batch-size` caps how many stored events a processing group fetches and scans in one poll cycle. If the earliest event is in retry backoff, the poll can still handle other
subjects within that bounded window; later events wait for a later poll.

The processing-group token records the highest contiguous store position that is no longer blocked. An event from a later subject may therefore be handled and recorded in `smd_event_sequence_state`
while the group token remains behind an earlier event whose subject is in backoff.

PostgreSQL stores subjects in `VARCHAR(500)` columns. The core event API imposes no maximum length, so applications using the built-in PostgreSQL stores must keep subjects within that storage limit.

After migration, existing stored events keep a null `subject_id` and therefore join the global subjectless sequence; the framework does not derive subject IDs from stored payloads. See
[Schema Migration](#schema-migration) before upgrading an existing installation.

## Retry Backoff Strategies

`smd.event-store.processing.retry-backoff.strategy` supports three modes. Each mode can be tuned with its own settings:

- `EXPONENTIAL` uses `base-delay`, `multiplier`, and `max-delay`
- `FIXED` uses `fixed-delay`
- `LINEAR` uses `base-delay`, `increment`, and `max-delay`

### Exponential

```yaml
smd:
    event-store:
        processing:
            retry-backoff:
                strategy: EXPONENTIAL
                base-delay: 1s
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
                base-delay: 1s
                increment: 5s
                max-delay: 1m
```

If you omit retry backoff settings, SMD uses the documented defaults. Invalid values still fail fast when Spring Boot creates the channel configuration.

If you use Flyway or Liquibase, apply the schema before the application starts polling.

## Event Type Resolution

By default, `JacksonEventSerializer` uses `ClassNameEventTypeResolver`, which stores the event class name as the event type.

For long-lived stores, prefer stable application-level names with `StaticEventTypeResolver`:

```java
var eventTypeResolver = new StaticEventTypeResolver(Map.of(
        "account-created", AccountCreated.class,
        "account-closed", AccountClosed.class
));
var serializer = new JacksonEventSerializer(objectMapper, eventTypeResolver);
```

Serialization fails when an event class or stored event type is missing from the static mapping. Implement `EventTypeResolver` directly when resolution needs to be dynamic.

In Spring Boot, you can override `EventTypeResolver` or replace the `EventSerializer` bean entirely.

When the event store is enabled, Spring Boot creates the default `EventSerializer` from Boot's Jackson 3 `JsonMapper.Builder` and registers `SMDJacksonModule` automatically.

To add Jackson modules for event serialization while keeping SMD's defaults, provide Jackson 3 `JacksonModule` beans:

```java
@Bean
JacksonModule eventSerializationModule() {
    return new MyEventSerializationModule();
}
```

To customize the base Jackson 3 mapper builder, provide Spring Boot `JsonMapperBuilderCustomizer` beans:

```java
@Bean
JsonMapperBuilderCustomizer eventJsonMapperBuilderCustomizer() {
    return builder -> builder.findAndAddModules();
}
```

For framework-agnostic setup, add modules to the mapper you pass into `JacksonEventSerializer`:

```java
var objectMapper = JsonMapper.builder()
        .addModule(new SMDJacksonModule())
        .addModule(new MyEventSerializationModule())
        .build();
var serializer = new JacksonEventSerializer(objectMapper, eventTypeResolver);
```

For full control in Spring Boot, replace the `EventSerializer` bean entirely.

## Handler Processing Metadata

Before each event-delivery attempt, the event store adds these metadata properties to the deserialized event message:

- `processingId` — a new UUID for this delivery attempt.
- `sequenceNumber` — the event's global store position.
- `subjectId` — the event's persisted subject ID; omitted for subjectless events.
- `errorCount` — the number of failures already recorded for that subject (`0` on the initial attempt).

Handlers can also receive these values through `@MetadataValue`. They are merged into the deserialized event metadata for the current attempt and replace properties with the same reserved names. The
metadata stored in `smd_event_store` is not changed.

## Processing Diagnostics

The token and event-sequence stores expose read-only snapshots for targeted operational diagnostics:

```java
var tokenState = tokenStore.tokenState("accounts");
var globalSequenceState = eventSequenceStore.globalEventSequenceState("accounts");
var accountSequenceState = eventSequenceStore.eventSequenceState("accounts", "account:123");
```

`TokenState` reports processing progress and any detected gap. `EventSequenceState` reports the status, progress, recorded failure count, and most recent failure details for one global or
subject-specific sequence. Fields that have not been initialized are represented by `Optional.empty()`.

These methods issue non-locking reads and do not claim or create state. Each result is a point-in-time snapshot that may change immediately as event processing continues. The API does not publish
metrics or expose an HTTP endpoint; applications can adapt the targeted snapshots to their own monitoring system when appropriate.

## Processing Guarantees

The event store separates global scan progress from per-sequence processing state:

- `smd_token_store` tracks the scan checkpoint and gap state for each processing group.
- `smd_event_sequence_state` tracks status, failure count, last failure, and last processed position for each processing-group/subject pair.
- Non-blocking claims prevent concurrent processors from handling the same processing group or sequence state.
- Gap detection pauses the processing-group checkpoint when sequence numbers are missing.
- Retry backoff delays only the failing subject.
- Retry exhaustion marks the subject as abandoned.

Each polled batch is handled in one transaction. If a handler fails after earlier events in the batch succeeded, SMD rolls back the batch and replays the successful prefix in a smaller transaction so
that progress can commit. The failed event is retried on a later poll. Once it is the first unresolved event in a batch, another failure is recorded in a separate transaction and starts its subject's
retry backoff or abandonment transition.

Each poll scans at most `batch-size` stored events. Successfully handled events and events skipped for an abandoned subject update their sequence state. The processing-group token advances only across
the highest contiguous range that is no longer blocked by backoff or a concurrent subject claim.

Delivery is at least once around transaction rollback and process failure, so handlers should keep side effects transactional or idempotent. `max-retries` counts attempts after the initial attempt;
after all of them fail, the subject is abandoned.

## Configuration Validation

The channel validates configuration values when it is built:

- retry counts, gap timeouts, and delay durations must be zero or greater
- polling delay must be greater than zero
- batch size must be greater than zero
- retry multiplier must be greater than zero

## PostgreSQL Persistence

The built-in persistence implementations are PostgreSQL-specific:

- `PostgresEventStorage`
- `PostgresTokenStore`
- `PostgresEventSubjectSequenceStore`

The token and sequence stores use PostgreSQL concurrency primitives:

- row locking with `FOR UPDATE SKIP LOCKED`
- transaction-scoped advisory locks to coordinate row creation and claiming
- `UNIQUE NULLS NOT DISTINCT` to give each processing group one subjectless sequence

`UNIQUE NULLS NOT DISTINCT` requires PostgreSQL 15 or newer. Spring Boot configures these implementations by default. To support another database, provide compatible `EventStorage`, `TokenStore`, and
`EventSubjectSequenceStore` beans and manage an equivalent schema.

## Schema Migration

The schema lives at [core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql](../core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql).

Fresh installations need all three tables:

- `smd_event_store`
- `smd_token_store`
- `smd_event_sequence_state`

When upgrading an existing installation:

1. Add the nullable subject column before deploying the new code: `ALTER TABLE smd_event_store ADD COLUMN IF NOT EXISTS subject_id VARCHAR(500);`.
2. Create `smd_event_sequence_state`, including its `UNIQUE NULLS NOT DISTINCT` constraint, using the definition in the bundled schema.
3. Make the legacy timestamp nullable before the new token store can create rows without it: `ALTER TABLE smd_token_store ALTER COLUMN last_updated_at DROP NOT NULL;`.
4. Keep `last_processed_sequence_number`, `last_gap_detected_at`, and `gap_sequence_number` so processing resumes from the current group positions.
5. After rollback to the previous version is no longer required, optionally remove `last_processed_message_id`, `last_failed_message_id`, `last_updated_at`, `error_count`, `last_error_message`, and
   `last_error_at` from `smd_token_store`.

Retry state now belongs to each row in `smd_event_sequence_state`. Apply these changes as an explicit migration appropriate to your deployment rather than relying on application startup to modify the
schema. The bundled `CREATE TABLE IF NOT EXISTS` statements do not alter existing tables. Upgrading resets any in-progress group-level retry state because the previous schema has no per-subject state.

### Flyway

Copy the schema, or the relevant upgrade statements, into a versioned Flyway migration. The bundled `event-store-schema.sql` is not named as a Flyway versioned migration and is not executed
automatically merely by adding its directory to `spring.flyway.locations`.

For example, place the required statements in your application as `src/main/resources/db/migration/V2__update_smd_event_store.sql`.

### Liquibase

Include the schema through `sqlFile` in your Liquibase changelog.

## Related Docs

- [Getting Started](getting-started.md)
- [Spring Boot Guide](spring-boot.md)
- [Testing Guide](testing.md)
- [Spring Boot Testing Guide](spring-boot-testing.md)
