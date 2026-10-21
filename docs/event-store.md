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

An outlet filter skips nonmatching events and advances past them. Changing the filter later does not replay those events; [reset the group](#rebuild-a-processing-group) to
deliver them under the new filter.

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

When a poll inspected a full batch and advanced the token, the group polls again without waiting for `polling-delay`. A group that is behind, for example after a reset or an outage, therefore
catches up at processing speed. Each repeated poll goes through the shared scheduler queue, so other groups' polls still interleave. Otherwise the next poll waits `polling-delay`.

## Configuration

The defaults are suitable for a basic installation:

| Property                               |              Default | Meaning                                 |
|----------------------------------------|---------------------:|-----------------------------------------|
| `scheduling.enabled`                   |               `true` | Start polling for subscribed groups     |
| `scheduling.initial-delay`             |                `10s` | Wait before the first poll              |
| `scheduling.polling-delay`             |                 `5s` | Delay before polling a caught-up group  |
| `scheduling.thread-pool-size`          | available processors | Shared scheduler threads                |
| `processing.max-retries`               |                  `3` | Retries after the initial failure       |
| `processing.batch-size`                |                `100` | Maximum events scanned per poll         |
| `processing.gap-timeout`               |                 `5m` | Wait before skipping a missing position |
| `processing.reset-lock-timeout`        |                `30s` | Wait for the token lock during a reset  |
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
      reset-lock-timeout: 30s
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

Durations and retry counts must be non-negative. Polling delay, reset lock timeout, batch size, scheduler size, and exponential multiplier must be greater than zero. Invalid configuration fails during application
startup.

## Delivery Metadata

Before each delivery attempt, SMD merges these reserved properties into the event metadata:

| Property         | Value                                             |
|------------------|---------------------------------------------------|
| `processingId`   | New UUID for this attempt                         |
| `sequenceNumber` | Global store position                             |
| `subjectId`      | Persisted subject; omitted for subjectless events |
| `errorCount`     | Failures already recorded for the subject         |
| `replay`         | `"true"` at or below the group's replay boundary  |

Handlers can read them with `@MetadataValue`. Attempt metadata replaces stored properties with the same names but does not modify the metadata stored in `smd_event_store`.

## Inspect Processing State

The stores expose point-in-time, non-locking diagnostic snapshots:

```java
var token = tokenStore.tokenState("ticket-activity");
var global = eventSequenceStore.globalEventSequenceState("ticket-activity");
var ticket = eventSequenceStore.eventSequenceState("ticket-activity", ticketId.toString());
```

`TokenState` reports contiguous progress, any active gap, and the replay boundary. `EventSequenceState` reports status, progress, failure count, and the latest error for one subject. Missing state is returned as
`Optional.empty()`. SMD does not publish metrics or an HTTP endpoint; adapt these snapshots to your monitoring system if needed.

## Rebuild a Processing Group

`EventStore.reset` replays a processing group from the start of the store. Use it after fixing a projection bug or changing what a projection stores:

```java
var result = eventStore.reset("ticket-view", () -> jdbc.execute("TRUNCATE ticket_view"));
```

The reset runs in its own transaction and commits before it returns:

1. It waits up to `reset-lock-timeout` for the group's token lock. A batch in flight on any node finishes and commits first. Pollers never wait for the reset; they skip the group while
   the reset holds the lock.
2. It runs the optional reset action, here a `TRUNCATE` of the group's read model.
3. It deletes the group's subject state, including failed and abandoned subjects, and moves the token to the start.

The reset action and the token move commit together or not at all. An exception from the reset action propagates unchanged and rolls back the whole reset. No node needs to stop, but readers
see an empty and then growing read model until the replay catches up. Use an in-place reset for internal or small read models, and a [blue/green rebuild](#bluegreen-rebuilds) otherwise.

| Result                   | Meaning                                                                                                     |
|--------------------------|-------------------------------------------------------------------------------------------------------------|
| `Reset`                  | Committed. Reports the previous position and the replay boundary                                            |
| `TokenClaimed`           | The lock was not acquired within `reset-lock-timeout`. Nothing changed; retry later                         |
| `UnknownProcessingGroup` | The group has no token and no subscription to this event store on this node. Nothing changed or was created |

Abandoned subjects get another attempt during the replay. If the cause is still there, they fail and are abandoned again through the normal retry path.

Outlet filters run on every replayed event, so a reset delivers events that an earlier filter skipped. Every replayed event is still deserialized, even when the filter drops it: a stored type
that no longer deserializes fails the group during the replay.

### Replayed Deliveries

The reset records the group's previous position as its replay boundary. Events at or below the boundary are delivered with the `replay` property set to `"true"`; later events get
`"false"`. When the token passes the boundary, the store clears it and logs `Replay finished`. Resetting again during a replay keeps the furthest boundary.

Missing store positions at or below the boundary are skipped immediately, because the group already passed them. Above the boundary, `gap-timeout` applies as usual.

### Keep Side Effects in Their Own Groups

Reset only groups whose handlers are safe to run again, such as projections. Put emails, webhooks, and outbound commands in their own processing groups and do not reset those groups. A handler
that must combine a projection with a side effect can guard on the property:

```java
@EventHandler
public void on(TicketResolvedEvent event, @MetadataValue("replay") String replay) {
    projection.markResolved(event.ticketId());
    if (!"true".equals(replay)) {
        mailer.sendResolvedMail(event.ticketId());
    }
}
```

Like the other delivery properties, `replay` is inherited by messages a handler sends, and is stored with the events it publishes.

### Blue/Green Rebuilds

A processing group without a token starts at the beginning of the store, so a rebuild without an empty read model needs no reset:

1. Deploy a copy of the projection under a new group, for example `@ProcessingGroup("ticket-view-v2")`, writing to `ticket_view_v2`. Both groups receive every event.
2. Wait until the new group has caught up with the old one:

   ```java
   var v1 = eventStore.progress("ticket-view").orElseThrow();
   var v2 = eventStore.progress("ticket-view-v2").orElseThrow();
   boolean caughtUp = v2.position() >= v1.position();
   ```

   Compare with the old group's position, not with `head()`: both groups pause at the same recent gaps.
3. Switch reads to `ticket_view_v2` in the application.
4. Remove the old handler. Its token and subject rows stay behind and are not read again.

A new group has no replay boundary. Its whole backfill is delivered with `replay` set to `"false"`, and it waits `gap-timeout` at every historical gap. Never introduce a side-effect handler
under a new group name on a store with history.

`progress` returns `ProcessingProgress` with the group's `position`, the store's `head`, the `replayUntil` boundary, `remaining()`, and `replaying()`. It is empty for a group without a
token.

## Framework-Agnostic Setup

Without Spring Boot, add the module directly:

```kotlin
dependencies {
    implementation("app.dodb:smd-event-store:0.0.12")
    implementation("tools.jackson.core:jackson-databind:3.0.4")
    runtimeOnly("org.postgresql:postgresql:42.7.10")
}
```

`EventStore` and its configuration live in `app.dodb.smd.eventstore`; persistence types and serializers use the `storage` and `serialization` subpackages. Supply a `ConnectionProvider` and
`TransactionProvider`, then construct the store:

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

Close `EventStore` during application shutdown so its scheduler terminates cleanly. Implement `EventStorage`, `TokenStore`, and `EventSubjectSequenceStore` plus an equivalent schema to support
another database.

## Schema and Upgrades

Fresh installations need all three tables from the bundled schema:

- `smd_event_store`
- `smd_token_store`
- `smd_event_sequence_state`

The schema uses PostgreSQL `UNIQUE NULLS NOT DISTINCT`, which requires PostgreSQL 15 or newer. It is not applied automatically. Copy it into a versioned Flyway migration or include it through
Liquibase `sqlFile`.

Installations created with SMD 0.0.12 or earlier need the replay boundary column before upgrading:

```sql
ALTER TABLE smd_token_store
    ADD COLUMN replay_until_sequence_number BIGINT;
```
