# Event Store

`smd-event-store` provides durable event publication and polling-based delivery. The built-in persistence uses PostgreSQL 15 or newer.

Use it when events must survive restarts or be retried independently of the publishing request. Delivery is at least once, so handlers must keep side effects transactional or idempotent.

## Store Model

The store keeps events in domain-driven design terms. Every event has a global position, and an event that belongs to an aggregate also carries the aggregate's type, ID, and version.
SMD maps these to the `EventMessage` it publishes and delivers, so handlers keep working with messages:

| Column              | Meaning                                                       | In SMD messages                                   |
|---------------------|---------------------------------------------------------------|---------------------------------------------------|
| `global_position`   | Position across all events; processing groups follow it       | `smd.sequenceNumber` delivery metadata            |
| `event_id`          | Unique event identity                                         | `EventMessage.messageId()`                        |
| `aggregate_type`    | Stable `@Aggregate` name; empty for events outside aggregates | Not exposed                                       |
| `aggregate_id`      | Aggregate identity, or the `@SubjectId` of other events       | `EventMessage.subjectId()` and `smd.subjectId`    |
| `aggregate_version` | Position in the aggregate's stream, starting at 1             | Not exposed                                       |
| `event_type`        | Resolved event type name                                      | The payload class                                 |
| `payload`           | Serialized event                                              | `EventMessage.payload()`                          |
| `metadata`          | Serialized metadata                                           | `EventMessage.metadata()`                         |
| `occurred_at`       | Publication time                                              | `Metadata.timestamp()`                            |

An aggregate's events need no `@SubjectId`: the store records the aggregate ID and delivers it as the subject. An event outside an aggregate records its `@SubjectId` as
`aggregate_id`, with no type or version.

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

Every stored type and published event class must remain in the mapping. Migrate existing `event_type` values before changing a resolver used by a live store.

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

## Publication and Subscriptions

An enabled store saves every event sent through Spring's default publisher, even with no durable groups or with polling disabled. The serializer must support every published event type.

With Spring, `eventStore.send(message)` joins an existing transaction or commits before returning when none exists. Within an SMD transaction, storage is deferred and rolls back with the transaction.

Closing a subscription stops its future polls and lets an active delivery finish. Close the store to shut down its scheduler; Spring handles this automatically.

An outlet filter skips nonmatching events and advances past them. Changing the filter later does not replay those events.

## Identify Event Subjects

A subject identifies an ordered sequence within each processing group. Aggregate events use the aggregate ID. Give other events a subject with `@SubjectId`:

```java
public record InvoiceSentEvent(
        @SubjectId UUID invoiceId,
        String recipient) implements Event {
}
```

`@SubjectId` may annotate one zero-argument method or record component. Any non-null value is converted with `toString()`; `null` and `Optional.empty()` mean no subject.

Important rules:

- The exact string is the identity. Event types do not add an implicit namespace.
- Use stable domain values. Do not use array, collection, debug, or object-identity strings.
- Blank subjects and invalid or duplicate accessors fail publication.
- Subject IDs must fit the PostgreSQL `VARCHAR(500)` `aggregate_id` column.
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

## Event-Sourced Aggregates

An aggregate annotated with `@Aggregate` keeps its state as a stream of events in the store instead of in its own table. A `@CommandHandler` constructor creates the
aggregate from a command. For later commands, SMD loads the stream, replays it into the aggregate, calls the command handler method, and appends the events it applies:

```java
@Aggregate("ticket")
public class Ticket {

    @AggregateId
    private UUID ticketId;
    private TicketStatus status;

    private Ticket() {
    }

    @CommandHandler
    public Ticket(OpenTicketCommand command, AggregateEvents<TicketEvent> events) {
        events.apply(new TicketOpenedEvent(command.ticketId(), command.title()));
    }

    @CommandHandler
    public UUID handle(AssignTicketCommand command, AggregateEvents<TicketEvent> events) {
        if (status != TicketStatus.OPEN) {
            throw new InvalidTicketTransitionException(ticketId, status);
        }
        events.apply(new TicketAssignedEvent(ticketId, command.assignee()));
        return ticketId;
    }

    @EventSourcingHandler
    void on(TicketOpenedEvent event) {
        ticketId = event.ticketId();
        status = TicketStatus.OPEN;
    }

    @EventSourcingHandler
    void on(TicketAssignedEvent event) {
        status = TicketStatus.ASSIGNED;
    }
}

public record OpenTicketCommand(UUID ticketId, String title) implements Command<UUID> {
}

public record AssignTicketCommand(@TargetAggregateId UUID ticketId, String assignee) implements Command<UUID> {
}
```

| Annotation              | Target                      | Meaning                                                                          |
|-------------------------|-----------------------------|----------------------------------------------------------------------------------|
| `@Aggregate("ticket")`  | class                       | Event-sourced aggregate. The name is stored with every event and must be stable  |
| `@AggregateId`          | field                       | Identity. Stored as `aggregate_id` and delivered as each event's subject         |
| `@CommandHandler`       | constructor or method       | A constructor creates the aggregate; a method handles later commands             |
| `@TargetAggregateId`    | command method or component | Selects the stream to load. Required on the commands of handler methods          |
| `@EventSourcingHandler` | method                      | Applies one event to the aggregate's state. Events without a handler are ignored |

Command handlers, constructors and methods alike, are public and take the command and optionally `AggregateEvents`, `MessageId`, `Metadata`, `Principal`, `Instant`, and
`@MetadataValue` strings. Keep event sourcing handlers free of side effects: they also run on every replay.

- A creating constructor's command returns the aggregate ID, so its result type must match the `@AggregateId` field: `OpenTicketCommand` is a `Command<UUID>`. The constructor must
  apply at least one event, and its `@EventSourcingHandler`s must assign the ID. Inside the constructor, `events.apply(event)` only records the event; SMD applies it once the
  constructor returns, so code after `apply` in the constructor does not see the new state.
- In a handler method, `events.apply(event)` runs the matching `@EventSourcingHandler` immediately, so the method sees the new state, and records the event for appending.
- The aggregate also needs a no-argument constructor of any visibility to replay its stream. It holds no dependencies and is created for each command.

For each command, in one transaction:

1. A creating command calls its constructor. Any other command loads the stream selected by `@TargetAggregateId` and replays it; an empty stream fails with
   `AggregateNotFoundException`.
2. The command handler method runs. If a constructor or method throws, nothing is appended.
3. Each applied event is published through the event publisher with the next aggregate version, after the loaded version. The event store's inlet stores it with
   `aggregate_type`, `aggregate_id`, and `aggregate_version`; other sinks and processing groups receive it as usual.

Applied events need no `@SubjectId`. Processing groups that read from the store receive the aggregate ID as the event's subject, so each aggregate's events are handled in
version order. Sinks that receive the event directly see only a declared `@SubjectId`.

A unique index on `(aggregate_type, aggregate_id, aggregate_version)` guards concurrent changes. When another transaction already stored the same version, storing fails with
`ConcurrentAggregateChangeException` and the transaction rolls back. SMD does not retry; send the command again to handle it against the new state.

Creating an aggregate whose ID already has a stream fails the same way, with an expected version of 0.

Event storage is deferred until the transaction completes, so a second command for the same aggregate in the same transaction loads the stream without the first command's
events and fails with `ConcurrentAggregateChangeException`.

Publication fails if no event store inlet receives an aggregate event, and if an applied event declares a `@SubjectId` that differs from the aggregate's `@AggregateId`.

With Spring Boot and `smd.event-store.enabled=true`, classes annotated with `@Aggregate` in the `@EnableSMD` packages register their command handlers next to the bean-based ones.
They are not beans. Without Spring, register them on the command bus explicitly:

```java
var aggregates = eventStore.aggregateCommandHandlers(List.of(Ticket.class), eventBus);
var commandBus = CommandBusSpec.withDefaults()
    .commandHandlers(() -> commandHandlers.locate().and(aggregates.locate()))
    .create();
```

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

Before each delivery attempt, SMD merges these reserved properties into the event metadata. `ProcessingMetadata` holds their names:

| Property             | Constant          | Value                                                 |
|----------------------|-------------------|-------------------------------------------------------|
| `smd.processingId`   | `PROCESSING_ID`   | New UUID for this attempt                             |
| `smd.sequenceNumber` | `SEQUENCE_NUMBER` | The event's `global_position`                         |
| `smd.subjectId`      | `SUBJECT_ID`      | The event's `aggregate_id`; omitted when it has none  |
| `smd.errorCount`     | `ERROR_COUNT`     | Failures already recorded for the subject             |

Handlers can read them with `@MetadataValue(ProcessingMetadata.ERROR_COUNT)`. They describe one delivery, so the messages a handler sends do not inherit them (see
[Choose Inherited Properties](core-api.md#choose-inherited-properties)). Attempt metadata replaces stored properties with the same names but does not modify the metadata stored in `smd_event_store`.

## Inspect Processing State

The stores expose point-in-time, non-locking diagnostic snapshots:

```java
var token = tokenStore.tokenState("ticket-activity");
var global = eventSequenceStore.globalEventSequenceState("ticket-activity");
var ticket = eventSequenceStore.eventSequenceState("ticket-activity", ticketId.toString());
```

`TokenState` reports contiguous progress and any active gap. `EventSequenceState` reports status, progress, failure count, and the latest error for one aggregate ID. Missing state is returned as
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
    .eventSequenceStore(new PostgresEventSequenceStore(connectionProvider))
    .eventSerializer(new JacksonEventSerializer(mapper, eventTypeResolver))
    .build();

var eventStore = new EventStore(eventStoreConfig);

var eventBus = EventBusSpec.withDefaults()
    .interceptors(new TransactionalEventBusInterceptor(transactionProvider))
    .sinks(eventStore.inlet())
    .processingGroups(locator, groups -> groups.anyProcessingGroup().source(eventStore.outlet()))
    .create();
```

`EventStore` implements `EventMedium`: its inlet stores events through the transaction provider, and its outlet polls them. Use `.medium(eventStore)` to register both together.

Close `EventStore` during application shutdown so its scheduler terminates cleanly. Implement `EventStorage`, `TokenStore`, and `EventSequenceStore` plus an equivalent schema to support
another database.

## Schema and Upgrades

Fresh installations need all three tables from the bundled schema:

- `smd_event_store`
- `smd_token_store`
- `smd_event_sequence_state`

The schema uses PostgreSQL `UNIQUE NULLS NOT DISTINCT`, which requires PostgreSQL 15 or newer. It is not applied automatically. Copy it into a versioned Flyway migration or include it through
Liquibase `sqlFile`.

Installations created with 0.0.12 or earlier use the previous column names and have no aggregate columns. Stop every instance, then upgrade with:

```sql
ALTER TABLE smd_event_store RENAME COLUMN message_id TO event_id;
ALTER TABLE smd_event_store RENAME COLUMN subject_id TO aggregate_id;
ALTER TABLE smd_event_store RENAME COLUMN serialized_payload TO payload;
ALTER TABLE smd_event_store RENAME COLUMN serialized_metadata TO metadata;
ALTER TABLE smd_event_store RENAME COLUMN created_at TO occurred_at;
ALTER TABLE smd_event_store RENAME COLUMN sequence_number TO global_position;
ALTER TABLE smd_event_store
    ADD COLUMN aggregate_type    VARCHAR(255),
    ADD COLUMN aggregate_version BIGINT,
    ADD CONSTRAINT smd_event_store_aggregate_revision_check
        CHECK ((aggregate_type IS NULL AND aggregate_version IS NULL)
            OR (aggregate_type IS NOT NULL AND aggregate_version IS NOT NULL AND aggregate_id IS NOT NULL));

CREATE UNIQUE INDEX smd_event_store_aggregate_stream
    ON smd_event_store (aggregate_type, aggregate_id, aggregate_version)
    WHERE aggregate_type IS NOT NULL;

ALTER TABLE smd_token_store RENAME COLUMN last_processed_sequence_number TO last_processed_position;
ALTER TABLE smd_token_store RENAME COLUMN gap_sequence_number TO gap_position;

ALTER TABLE smd_event_sequence_state RENAME COLUMN subject_id TO aggregate_id;
ALTER TABLE smd_event_sequence_state RENAME COLUMN last_processed_sequence_number TO last_processed_position;
```

Existing events keep their subject as `aggregate_id` and have no aggregate type, so they are processed as before.
