# Event Store

`smd-event-store` provides durable event publication and polling-based delivery. The built-in persistence uses PostgreSQL 15 or newer.

Use it when events must survive restarts or be retried independently of the publishing request. Delivery is at least once, so handlers must keep side effects transactional or idempotent.

## Spring Boot Setup

The Spring Boot starter already includes the event-store module. Your application still needs:

- a configured `DataSource`
- a PostgreSQL JDBC driver
- the SMD schema applied before polling starts

With Spring Boot dependency management, add JDBC, Flyway, and the PostgreSQL driver without versions:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}
```

Copy [event-store-schema.sql](../core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql) into a versioned application migration such as
`src/main/resources/db/migration/V1__smd_event_store.sql`. The event-store schema is not applied automatically and must exist before polling starts.

Then enable the store:

```yaml
smd:
  event-store:
    enabled: true
```

## Use Stable Event Type Names

The default `ClassNameEventTypeResolver` stores Java class names. Renaming or moving an event class can then make old events unreadable. Configure stable application-level names before storing
production events:

```java
@Bean
EventTypeResolver eventTypeResolver() {
    return new StaticEventTypeResolver(Map.of(
        "ticket.opened.v1", TicketOpenedEvent.class
    ));
}
```

Map each event class to one type name. Every type name already in the store must stay in the mapping or be [upcast](#evolve-stored-events) to a current one.

Spring Boot builds the default serializer with Jackson 3 and registers `SMDJacksonModule`. Add Jackson 3 `JacksonModule` or `JsonMapperBuilderCustomizer` beans for normal customization, or provide
your own `EventSerializer` bean for full control.

Enabling the store registers its inlet with the default event publisher. Connect durable processing groups to its outlet:

```java
@Bean
ProcessingGroupsConfigurer processingGroupsConfigurer(EventStore eventStore) {
    return groups -> {
        groups.processingGroup("ticket-activity").source(eventStore.outlet());
        groups.anyProcessingGroup().sync();
    };
}
```

Publishing stores events inside the publishing transaction. The scheduler polls and delivers them to `ticket-activity` in new transactions.

## Evolve Stored Events

Stored events keep the type name and JSON they were written with. To change an event, give it a new versioned type name, map only current names in the resolver, and register an
`EventUpcaster` bean for each older name:

```java
@Bean
EventTypeResolver eventTypeResolver() {
    return new StaticEventTypeResolver(Map.of(
        "ticket.opened.v2", TicketOpenedEvent.class
    ));
}

// Events stored as ticket.created.v1 continue as ticket.opened.v1.
@Bean
EventUpcaster ticketCreatedV1Upcaster() {
    return EventUpcaster.rename("ticket.created.v1", "ticket.opened.v1");
}

// ticket.opened.v2 added a description.
@Bean
EventUpcaster ticketOpenedV1Upcaster() {
    return EventUpcaster.transform("ticket.opened.v1", "ticket.opened.v2", payload -> payload.put("description", ""));
}
```

`transform` receives the stored payload as a Jackson `ObjectNode` and returns the payload of the next type. Upcasters chain by type name, so an event stored as `ticket.created.v1` passes
through both upcasters and is read as a `TicketOpenedEvent`.

Important rules:

- The message ID, subject, and metadata come from the stored row.
- Upcasters run on every read, including retries. Keep them pure, and never change a released upcaster.
- Invalid upcasters fail startup: two upcasters for one type, a cycle, a type that is neither upcast nor mapped, or an upcaster for a mapped type.
- An upcaster that fails or returns `null` fails the delivery, which is retried like any other failure.
- A custom `EventSerializer` bean ignores `EventUpcaster` beans.

## Publication and Subscriptions

An enabled store saves every event sent through Spring's default publisher, even with no durable groups or with polling disabled. The serializer must support every published event type.

With Spring, `eventStore.send(message)` joins an existing transaction or commits before returning when none exists. Within an SMD transaction, storage is deferred and rolls back with the transaction.

Closing a subscription stops its future polls and lets an active delivery finish. Close the store to shut down its scheduler; Spring handles this automatically.

An outlet filter skips nonmatching events and advances past them. Changing the filter later does not replay those events.

## Identify Event Subjects

A subject identifies an ordered sequence within each processing group:

```java
public record TicketOpenedEvent(
        @SubjectId UUID ticketId,
        String title,
        String description) implements Event {
}
```

`@SubjectId` may annotate one zero-argument method or record component. Any non-null value is converted with `toString()`; `null` and `Optional.empty()` mean no subject.

Important rules:

- The exact string is the identity. Event types do not add an implicit namespace.
- Use stable domain values. Do not use array, collection, debug, or object-identity strings.
- Blank subjects and invalid or duplicate accessors fail publication.
- Subject IDs must fit the PostgreSQL `VARCHAR(500)` column.
- All events without a subject share one global subjectless sequence per processing group.

## Make Durable Handlers Idempotent

Event-store delivery is at least once. Use the event message ID as an idempotency key when a handler writes a projection or records an external action:

```java
@Component
@ProcessingGroup("ticket-activity")
public class JdbcTicketActivityProjection {

    private final JdbcTemplate jdbc;

    public JdbcTicketActivityProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventHandler
    public void handle(TicketOpenedEvent event, MessageId eventId) {
        jdbc.update("""
                INSERT INTO ticket_activity (event_id, ticket_id, title)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
            eventId.value(),
            event.ticketId(),
            event.title()
        );
    }
}
```

The `event_id` column must be unique. Keep the idempotency write and the handler's other database changes in the same delivery transaction. The event store already provides durable retry for external
calls: let failures propagate so the event remains unprocessed and is retried. Because delivery is at least once and a network failure can leave the remote outcome unknown, pass the event ID as an
idempotency key when the external API supports it.

## Processing and Failure Behavior

Each processing group has a scan token and independent state for every subject:

- Events for one subject are processed in store order.
- A failing subject enters retry backoff while other subjects in the fetched window may continue.
- `max-retries` counts attempts after the first attempt. With the default `3`, a subject is abandoned after four failed attempts.
- Once abandoned, later events for that subject are skipped; other subjects continue.
- The group token advances only across contiguous positions that are no longer blocked.
- A missing store position pauses the token. After `gap-timeout`, processing skips the missing range and continues.

A fetched batch runs in one transaction. If a later handler fails, the batch rolls back and SMD replays the successful prefix in a smaller transaction. The failing event is retried on a later poll.
Crashes and rollbacks can cause redelivery, so handlers must remain idempotent.

`batch-size` limits the number of stored events inspected per poll. A subject beyond that window waits for a later poll even when an earlier subject is in backoff.

## Configuration

The defaults are suitable for a basic installation:

| Property                               |              Default | Meaning                                 |
|----------------------------------------|---------------------:|-----------------------------------------|
| `scheduling.enabled`                   |               `true` | Start polling for subscribed groups     |
| `scheduling.initial-delay`             |                `10s` | Wait before the first poll              |
| `scheduling.polling-delay`             |                 `5s` | Delay between completed polls           |
| `scheduling.thread-pool-size`          | available processors | Shared scheduler threads                |
| `processing.max-retries`               |                  `3` | Retries after the initial failure       |
| `processing.batch-size`                |                `100` | Maximum events scanned per poll         |
| `processing.gap-timeout`               |                 `5m` | Wait before skipping a missing position |
| `processing.retry-backoff.strategy`    |        `EXPONENTIAL` | Backoff calculation                     |
| `processing.retry-backoff.base-delay`  |                 `1s` | Initial exponential or linear delay     |
| `processing.retry-backoff.multiplier`  |                `5.0` | Exponential growth factor               |
| `processing.retry-backoff.fixed-delay` |                 `1s` | Fixed-strategy delay                    |
| `processing.retry-backoff.increment`   |                 `5s` | Linear increase per retry               |
| `processing.retry-backoff.max-delay`   |                 `5m` | Exponential or linear delay cap         |

All properties live below `smd.event-store`. A complete exponential example is:

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

To use another strategy, replace the `processing.retry-backoff` mapping with one of these:

```yaml
# Wait the same amount after every failure.
retry-backoff:
  strategy: FIXED
  fixed-delay: 30s
```

```yaml
# Add increment to the delay after every failure, capped by max-delay.
retry-backoff:
  strategy: LINEAR
  base-delay: 1s
  increment: 5s
  max-delay: 1m
```

Durations and retry counts must be non-negative. Polling delay, batch size, scheduler size, and exponential multiplier must be greater than zero. Invalid configuration fails during application
startup.

## Delivery Metadata

Before each delivery attempt, SMD adds these reserved properties to the event metadata as transient properties. `ProcessingMetadata` holds their names:

| Property             | Constant          | Value                                             |
|----------------------|-------------------|---------------------------------------------------|
| `smd.processingId`   | `PROCESSING_ID`   | New UUID for this attempt                         |
| `smd.sequenceNumber` | `SEQUENCE_NUMBER` | Global store position                             |
| `smd.subjectId`      | `SUBJECT_ID`      | Persisted subject; omitted for subjectless events |
| `smd.errorCount`     | `ERROR_COUNT`     | Failures already recorded for the subject         |

Handlers can read them with `@MetadataValue(ProcessingMetadata.ERROR_COUNT)` or `metadata.property(...)`. They describe one delivery, so the messages a handler sends do not
inherit them, and a route that forwards the event to another medium does not carry them along (see [Inherited Properties](core-api.md#inherited-properties)). When a
stored property has the same name, `property(...)` returns the transient value; the metadata stored in `smd_event_store` is never modified.

## Inspect Processing State

The stores expose point-in-time, non-locking diagnostic snapshots:

```java
var token = tokenStore.tokenState("ticket-activity");
var global = eventSequenceStore.globalEventSequenceState("ticket-activity");
var ticket = eventSequenceStore.eventSequenceState("ticket-activity", ticketId.toString());
```

`TokenState` reports contiguous progress and any active gap. `EventSequenceState` reports status, progress, failure count, and the latest error for one subject. Missing state is returned as
`Optional.empty()`. SMD does not publish metrics or an HTTP endpoint; adapt these snapshots to your monitoring system if needed.

## Framework-Agnostic Setup

Without Spring Boot, add the module directly:

```kotlin
dependencies {
    implementation("app.dodb:smd-event-store:0.0.12")
    implementation("tools.jackson.core:jackson-databind:3.0.4")
    runtimeOnly("org.postgresql:postgresql:42.7.10")
}
```

`EventStore` and its configuration live in `app.dodb.smd.eventstore`, and persistence types in its `storage` subpackage. Serializers come from `smd-serialization`, in
`app.dodb.smd.serialization` with the Jackson support in its `jackson` subpackage, which `smd-event-store` exposes. Supply a `ConnectionProvider` and `TransactionProvider`, both in `app.dodb.smd.api.framework`, then construct the store:

```java
var mapper = JsonMapper.builder()
    .addModule(new SMDJacksonModule())
    .build();

var eventTypeResolver = new StaticEventTypeResolver(Map.of(
    "ticket.opened.v1", TicketOpenedEvent.class
));

var eventStoreConfig = EventStoreConfig.withDefaults()
    .transactionProvider(transactionProvider)
    .eventStorage(new PostgresEventStorage(connectionProvider))
    .tokenStore(new PostgresTokenStore(connectionProvider))
    .eventSequenceStore(new PostgresEventSubjectSequenceStore(connectionProvider))
    .eventSerializer(new JacksonEventSerializer(mapper, eventTypeResolver, List.of()))
    .build();

var eventStore = new EventStore(eventStoreConfig);

var eventBus = EventBusSpec.withDefaults()
    .interceptors(new TransactionalEventBusInterceptor(transactionProvider))
    .sinks(eventStore.inlet())
    .processingGroups(locator, groups -> groups.anyProcessingGroup().source(eventStore.outlet()))
    .create();
```

Pass any `EventUpcaster`s as the serializer's last argument.

`EventStore` implements `EventMedium`: its inlet stores events through the transaction provider, and its outlet polls them. Use `.medium(eventStore)` to register both together.

Close `EventStore` during application shutdown so its scheduler terminates cleanly. Implement `EventStorage`, `TokenStore`, and `EventSubjectSequenceStore` plus an equivalent schema to support
another database.

## Schema and Upgrades

Fresh installations need all three tables from the bundled schema:

- `smd_event_store`
- `smd_token_store`
- `smd_event_sequence_state`

The schema uses PostgreSQL `UNIQUE NULLS NOT DISTINCT`, which requires PostgreSQL 15 or newer. It is not applied automatically. Copy it into a versioned Flyway migration or include it through
Liquibase `sqlFile`.
