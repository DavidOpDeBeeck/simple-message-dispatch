# Event Sourcing

`smd-event-store` keeps events in PostgreSQL 15+ as the record of what happened. [Aggregates](#event-sourced-aggregates) rebuild their state from their own events, and
processing groups build projections from the same events. The module is included in the Spring Boot starter.

An `EventStore` is an `EventMedium`: its inlet stores every published event in the publisher's transaction, and its outlet polls the store and delivers the events to each
subscribed processing group. Unlike a [durable event dispatcher](durable-delivery.md), the store keeps every event, so a new processing group starts at the first one.

## Guarantees

| Concern      | Behavior                                                                                               |
|--------------|--------------------------------------------------------------------------------------------------------|
| Storing      | An event is stored only if the publisher's transaction commits; a failure to store fails the publisher |
| Aggregates   | A command's events are appended with consecutive versions; a concurrent change fails and rolls back    |
| Delivery     | At least once per processing group                                                                     |
| Order        | Per [subject](#identify-event-subjects), in the order the events were stored                           |
| Failures     | Retried with backoff, then abandoned after `max-retries`; only the failing subject waits               |
| Transactions | A processing group's handlers and its progress commit together                                         |

Handlers must still be idempotent: a crash or a later failure in the same batch can deliver an event again.

## Spring Boot Setup

The Spring Boot starter already includes the event-sourcing module. Your application still needs:

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

Then enable event sourcing:

```yaml
smd:
  event-store:
    enabled: true
```

The starter creates an `EventStore` bean, registers its inlet with the default event publisher, and registers the aggregate command handlers in the `@EnableSMD` packages.

### Process Stored Events

Connect processing groups to the store's outlet:

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

## Event-Sourced Aggregates

An `@Aggregate` keeps its state as a stream of events in the store instead of in its own table, and handles its own commands. For every command, SMD loads the aggregate's
stream, replays it, calls the command handler, and appends the events the handler applies:

```java
@Aggregate("ticket")
public class Ticket {

    @AggregateId
    private UUID ticketId;
    private TicketStatus status;

    private Ticket() {
    }

    @AggregateCommandHandler
    public Ticket(OpenTicketCommand command, AggregateEvents<TicketEvent> events) {
        events.apply(new TicketOpenedEvent(UUID.randomUUID(), command.title()));
    }

    @AggregateCommandHandler
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

public record OpenTicketCommand(String title) implements Command<UUID> {
}

public record AssignTicketCommand(@TargetAggregateId UUID ticketId, String assignee) implements Command<UUID> {
}
```

| Annotation                 | Target                             | Meaning                                                                         |
|----------------------------|------------------------------------|---------------------------------------------------------------------------------|
| `@Aggregate("ticket")`     | class                              | Event-sourced aggregate. The name is stored with every event and must be stable |
| `@AggregateId`             | field                              | Identity of the aggregate. With the name, it is the subject of its events       |
| `@AggregateCommandHandler` | constructor or method              | A constructor creates the aggregate; a method handles later commands            |
| `@TargetAggregateId`       | command record component or method | Selects the aggregate a later command changes                                   |
| `@EventSourcingHandler`    | method                             | Applies an event to the aggregate's state                                       |

### Write Aggregates

- **Creating:** an `@AggregateCommandHandler` constructor applies at least one event, and an `@EventSourcingHandler` for those events assigns the `@AggregateId`. The command
  returns the new ID, so its result type matches the `@AggregateId` field, and it has no `@TargetAggregateId`.
- **Changing:** an `@AggregateCommandHandler` method handles a command whose `@TargetAggregateId` has the `@AggregateId` type. A command for an unknown aggregate fails with
  `AggregateNotFoundException`.
- **Replaying:** the aggregate needs a no-argument constructor of any visibility. Keep `@EventSourcingHandler` methods free of side effects, because they run on every replay.
- `events.apply(event)` in a method updates the state immediately; in the constructor it takes effect once the constructor returns.
- Use `@AggregateCommandHandler`, not `@CommandHandler`, in an aggregate.

Aggregate command handlers take the parameters of a `@CommandHandler`, plus `AggregateEvents`. Event sourcing handlers take the parameters of an `@EventHandler` and, like
event handlers, also receive subtypes of their event; order several matching handlers with `@EventSourcingHandler(order = ...)`.

Aggregate command handlers can also take collaborators, such as a domain service. SMD resolves every other parameter through the `ObjectCreator`, which in Spring Boot is
the bean of that type:

```java
@AggregateCommandHandler
public UUID handle(AssignTicketCommand command, AssigneeDirectory assignees, AggregateEvents<TicketEvent> events) {
    events.apply(new TicketAssignedEvent(ticketId, assignees.require(command.assignee())));
    return ticketId;
}
```

### Transactions and Concurrency

Each command runs in one transaction. The applied events are published with the next aggregate versions, stored by the event store's inlet, and delivered to other sinks and
processing groups like any other event. If the handler throws, nothing is appended.

A unique index on the aggregate type, ID, and version guards concurrent changes: when another transaction stored the same version first, the command fails with
`ConcurrentAggregateChangeException` and rolls back. SMD does not retry; send the command again. Handle at most one command per aggregate in a transaction.

An aggregate's events have the aggregate as their [subject](#identify-event-subjects): its `@Aggregate` name as the type and its ID. Every medium, such as a
[durable event dispatcher](durable-delivery.md), keeps them in order, with no `@SubjectId` needed. An aggregate event that declares a different `@SubjectId` fails publication.

The store only stores the events its inlet receives. The Spring Boot starter registers the inlet with the default publisher. Without Spring, register it with
`sinks(eventStore.inlet())`: `medium(eventStore)` only stores the events its processing groups handle, so an aggregate could not be loaded.

### Register Aggregates

With Spring Boot and `smd.event-store.enabled=true`, the `@Aggregate` classes in the `@EnableSMD` packages are registered on the command bus. They are not beans.

Without Spring, create `EventSourcedAggregates` and locate the aggregates by package:

```java
var aggregates = new EventSourcedAggregates(eventStore, eventBus, new MetadataFactory(principalProvider, timeProvider));
var aggregateCommandHandlers = new PackageBasedAggregateLocator(List.of("com.example.ticket"), aggregates, objectCreator);
var commandBus = CommandBusSpec.withDefaults()
    .commandHandlers(() -> commandHandlers.locate().and(aggregateCommandHandlers.locate()))
    .create();
```

To change an aggregate outside a command handler, such as from a process manager, use its `AggregateRepository`. It follows the same rules and joins the current transaction:

```java
var tickets = aggregates.repository(Ticket.class);

Ticket ticket = tickets.create(events -> new Ticket(new OpenTicketCommand("Cannot sign in"), events));
UUID assigned = tickets.update(ticketId, (ticket, events) -> ticket.handle(new AssignTicketCommand(ticketId, "alice"), events));
```

### Test Aggregates

`AggregateTestFixture` from `smd-event-store-test` tests one aggregate in given-when-then style, without a database or Spring. `smd-spring-boot-starter-test` already exposes it;
otherwise add it:

```kotlin
dependencies {
    testImplementation("app.dodb:smd-event-store-test:0.0.xx")
}
```

```java
class TicketTest {

    private final AggregateTestFixture<Ticket> fixture = AggregateTestFixture.of(Ticket.class);

    @Test
    void assignTicketCommand_openTicket_appendsAssignedEvent() {
        fixture.given(new TicketOpenedEvent(ticketId, "Cannot sign in"))
            .when(new AssignTicketCommand(ticketId, "alice"))
            .expectEvents(new TicketAssignedEvent(ticketId, "alice"))
            .expectResult(ticketId);
    }

    @Test
    void resolveTicketCommand_openTicket_rejectsTransition() {
        fixture.given(new TicketOpenedEvent(ticketId, "Cannot sign in"))
            .when(new ResolveTicketCommand(ticketId, "Reset mail resent"))
            .expectException(InvalidTicketTransitionException.class);
    }

    @Test
    void openTicketCommand_noPriorActivity_appliesOpenedEvent() {
        fixture.givenNoPriorActivity()
            .when(new OpenTicketCommand("Cannot sign in"))
            .expectEventsSatisfying(events -> events.singleElement(type(TicketOpenedEvent.class))
                .returns("Cannot sign in", TicketOpenedEvent::title));
    }
}
```

The fixture runs the aggregate's real command handlers against an in-memory event store.

| Step                              | Meaning                                                            |
|-----------------------------------|--------------------------------------------------------------------|
| `givenNoPriorActivity()`          | The aggregate does not exist yet                                   |
| `given(events...)`                | Past events of the aggregate, which must assign its `@AggregateId` |
| `givenCommands(commands...)`      | Commands handled before the tested one                             |
| `when(command)`                   | Handles the tested command                                         |
| `expectEvents(events...)`         | The command applied exactly these events, in order                 |
| `expectNoEvents()`                | The command applied no events                                      |
| `expectResult(result)`            | The command returned this result                                   |
| `expectException(type)`           | The command failed with this type; an overload takes assertions    |
| `expectState(assertions)`         | AssertJ assertions on the aggregate after the command              |
| `expect...Satisfying(assertions)` | AssertJ assertions on the events or result, such as a generated ID |

Expectations on events, the result, or the state fail when the command failed. Configure collaborators, time, principal, or a serializer with the builder:

```java
private final AggregateTestFixture<Ticket> fixture = AggregateTestFixture.builder(Ticket.class)
    .collaborator(AssigneeDirectory.class, assignees)
    .time(() -> Instant.parse("2026-10-31T09:00:00Z"))
    .eventSerializer(eventSerializer)
    .build();
```

Pass your application's `EventSerializer` to also test that events survive serialization; by default the fixture keeps them in memory.

## Store Model

Every stored event has a global position, and an event with a subject also stores the subject's type and ID. An aggregate's events also store their version. Handlers keep
working with `EventMessage`s:

| Column            | Meaning                                                 | In SMD messages                        |
|-------------------|---------------------------------------------------------|----------------------------------------|
| `global_position` | Position across all events; processing groups follow it | `smd.globalPosition` delivery metadata |
| `event_id`        | Unique event identity                                   | `EventMessage.messageId()`             |
| `subject_type`    | The `@Aggregate` name or `@SubjectId` type              | `EventMessage.subject()`               |
| `subject_id`      | The aggregate ID or `@SubjectId` value                  | `EventMessage.subject()`               |
| `subject_version` | Position in an aggregate's stream, starting at 1        | Not exposed                            |
| `event_type`      | Resolved event type name                                | The payload class                      |
| `payload`         | Serialized event                                        | `EventMessage.payload()`               |
| `metadata`        | Serialized metadata                                     | `EventMessage.metadata()`              |
| `occurred_at`     | Publication time                                        | `Metadata.timestamp()`                 |

Events without a subject leave the subject columns empty.

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

A subject is what an event is about, identified by a type and an ID. Within each processing group, the events of one subject are handled in order. An aggregate's events
have the aggregate as their subject. Give other events a subject with `@SubjectId`, naming the subject type:

```java
public record InvoiceSentEvent(
        @SubjectId("invoice") UUID invoiceId,
        String recipient) implements Event {
}
```

`@SubjectId` may annotate one zero-argument method or record component. Any non-null value is converted with `toString()`; `null` and `Optional.empty()` mean no subject.

Important rules:

- The type and the ID together are the identity: invoice `42` and shipment `42` are different subjects. Give events about the same thing the same type, and use an
  aggregate's `@Aggregate` name for events about that aggregate.
- Use stable domain values. Do not use array, collection, debug, or object-identity strings.
- Blank subject IDs and invalid or duplicate accessors fail publication.
- Subject types must fit `VARCHAR(255)` and subject IDs `VARCHAR(500)`.
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

Before each delivery attempt, SMD adds these reserved transient properties to the event metadata:

| Property             | Constant                             | Value                                     |
|----------------------|--------------------------------------|-------------------------------------------|
| `smd.processingId`   | `ProcessingMetadata.PROCESSING_ID`   | New UUID for this attempt                 |
| `smd.globalPosition` | `EventStoreMetadata.GLOBAL_POSITION` | The event's `global_position`             |
| `smd.errorCount`     | `ProcessingMetadata.ERROR_COUNT`     | Failures already recorded for the subject |

Handlers can read them with `@MetadataValue(ProcessingMetadata.ERROR_COUNT)` or `metadata.property(...)`. They describe one delivery, so the messages a handler sends do not
inherit them, and a route that forwards the event to another medium does not carry them along (see [Inherited Properties](core-api.md#inherited-properties)). When a
stored property has the same name, `property(...)` returns the transient value; the metadata stored in `smd_event_store` is never modified.

## Inspect Processing State

The stores expose point-in-time, non-locking diagnostic snapshots:

```java
var token = tokenStore.tokenState("ticket-activity");
var global = eventSequenceStore.eventSequenceState("ticket-activity", new EventSequenceId.Global());
var ticket = eventSequenceStore.eventSequenceState("ticket-activity", new EventSequenceId.OfSubject(new Subject("ticket", ticketId.toString())));
```

`TokenState` reports contiguous progress and any active gap. `EventSequenceState` reports status, progress, failure count, and the latest error for one sequence: the global
sequence of events without a subject, or one subject. Missing state is returned as `Optional.empty()`. SMD does not publish metrics or an HTTP endpoint; adapt these snapshots to your monitoring system
if needed.

## Framework-Agnostic Setup

Without Spring Boot, add the module directly:

```kotlin
dependencies {
    implementation("app.dodb:smd-event-store:0.0.xx")
    implementation("tools.jackson.core:jackson-databind:3.0.4")
    runtimeOnly("org.postgresql:postgresql:42.7.10")
}
```

`EventStore` lives in `app.dodb.smd.eventstore`, aggregates in its `aggregate` subpackage, and the PostgreSQL implementations in `storage.postgres`. Serializers come
from `smd-serialization` (`app.dodb.smd.serialization`), which `smd-event-store` exposes. Supply a `ConnectionProvider` and a `TransactionProvider`, then construct the store:

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

Close `EventStore` during application shutdown so its scheduler terminates cleanly. Implement `EventStorage`, `TokenStore`, and `EventSequenceStore` plus an equivalent schema to support
another database.

## Schema

Installations need all three tables from the bundled schema:

- `smd_event_store`
- `smd_token_store`
- `smd_event_sequence_state`

The schema uses PostgreSQL `UNIQUE NULLS NOT DISTINCT`, which requires PostgreSQL 15 or newer. It is not applied automatically. Copy it into a versioned Flyway migration or include it through
Liquibase `sqlFile`.

The schema changed incompatibly after 0.0.12, and SMD provides no upgrade script.
