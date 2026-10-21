# Design: Event Store Outbox and Inbox with ActiveMQ

Status: proposal, 2026-10-22. Nothing here is implemented or released. Existing event-store behavior stays the same, but the storage needs a schema migration (see [Storage](#storage)).

## Summary

- **Model.** The event store already makes delivery durable to its own log: transactional, ordered per subject, and retried. This proposal lets it do the same *around* another sink or
  source:
  ```java
  EventSink   eventStore.outbox(String name, EventSink sink)   // send = stored in the transaction, forwarded to sink later
  EventSource eventStore.inbox(EventSource source)             // received = stored with deduplication, delivered to the subscriber later
  EventMedium eventStore.durable(String name, EventMedium medium) // outbox(name, medium.inlet()) + inbox(medium.outlet())
  ```
  An outbox forwards exactly what was sent to it. An inbox delivers exactly what the wrapped source delivers, to the same subscriber. The contract (which events, to whom) stays the same.
  What changes is the timing, the guarantees, and the medium in between: the event store sits between the application and the wrapped sink or source.
- **ActiveMQ** is a plain `EventMedium`: `JmsEventMedium` pairs a `JmsEventSink` inlet with a `JmsEventSource` outlet. It is usable on its own when you don't need durability, like the
  built-in dispatchers. Durability comes from wrapping it.
- **Broker and API.** Target **ActiveMQ Artemis** through **Jakarta Messaging 3.1**, in a generic **`core/smd-jms`** module tested against Artemis. The only Artemis-specific piece is the
  `_AMQ_DUPL_ID` string property.
- **No new concept on the bus.** Configuration uses `.sinks(...)`, `.source(...)`, `.medium(...)` and `.selecting(...)` as they are today.
- **Ordering.** `@SubjectId` maps to `JMSXGroupID`. Per-subject order holds from the outbox to the consumer while group ownership is stable and the broker redelivers without delay. A spike
  confirms both the guarantee and the ways it breaks.
- **Delivery.** At least once in both directions. Duplicates are dropped by Artemis duplicate detection and permanently by the inbox, which deduplicates by `MessageId`. The outbox's
  at-least-once promise depends on a separate fix to the event store's gap handling: today a transaction that commits after `gap-timeout` is skipped for good (see [Gaps](#gaps)).

## Context

The event store is already an outbox for its own log:

| Need                               | SMD today                                                                                                 |
|------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Store atomically with state        | `EventStore.inlet()` defers its `INSERT` into the publishing transaction (`TransactionProvider.defer`)     |
| Durable cursor                     | `smd_token_store` row per processing group, claimed with a lock so one poller runs per group cluster-wide  |
| Per-key ordering and retry         | `smd_event_sequence_state` per (group, subject): store order, retry backoff, abandonment                   |
| Stable type names                  | `EventTypeResolver` / `StaticEventTypeResolver`                                                           |
| Serialization                      | `EventSerializer` → `SerializedEvent`                                                                     |
| Trace propagation                  | `traceparent` in metadata properties, picked up by `MessageTracing` on any thread or application           |
| Publication / subscription split   | `EventSink`, `EventSource`, `EventMedium`, `.selecting(EventSelector)`                                    |

What it lacks is a way to put that durability *between* the application and another medium. A first draft of this note added a relay: a processing group that read the whole event log and
sent it to a sink. That made the store send the broker events nobody had sent there. The consequences were a full history replay when a relay first starts, inbound events echoing back out,
and a made-up group name. See [Rejected Alternatives](#rejected-alternatives).

## Model

```text
publishing transaction                       polling thread                     broker
──────────────────────                       ──────────────                     ──────
eventPublisher.publish(e)
  └─ outbox("ticket-events", tickets.inlet())
       .send(e) → row in log outbox:ticket-events ──► forward in subject order ──► tickets.inlet().send(e)

broker consumer thread                       polling thread                     processing group
──────────────────────                       ──────────────                     ────────────────
billing.outlet() delivers m
  └─ inbox(billing.outlet())
       stores m in log inbox:billing-projection ──► deliver in subject order ──► subscriber.on(m)
         (if absent), then the broker ack                                         (retry, backoff, abandonment)
```

The rules:

1. **An outbox keeps the sink contract.** It forwards exactly the events sent to it, in the transaction that sent them. Events published before the outbox existed, events sent only to other
   sinks, and inbound events are never forwarded.
2. **An inbox keeps the source contract.** Each subscriber gets exactly what the wrapped source would deliver to it. Each subscriber's processing group gets its own inbox log.
3. **The event store owns both lifecycles.** `outbox(...)` starts forwarding on the store's scheduler and stops when the store closes. `inbox(source).subscribe(subscriber)` returns one
   `EventSubscription` that closes both the broker subscription and the store cursor.
4. **Both wrappers report `event-store` as their delivery** (`EventStore.DELIVERY`). Unlike a selecting wrapper, which only filters, they change the medium: a send to the outbox writes to
   the store, and the inbox's subscriber reads from the store. The wrapped medium's name appears where the event store hands an event to it or takes one from it: on the forward span and on
   the direct outlet.

The existing store stays what it is: `eventStore.inlet()` and `eventStore.outlet()` read and write the store's own log, `events`.

## API and Configuration

### Without Spring

```java
var eventStore = new EventStore(EventStoreConfig.withDefaults()
        // ... storage, serializer and transaction provider as in docs/event-store.md
        .sinkInterceptors(tracing)                     // spans for the outbox forward and the inbox insert
        .build());

var jms = JmsConfig.withDefaults()
        .connectionFactory(connectionFactory)          // jakarta.jms.ConnectionFactory
        .eventSerializer(eventSerializer)              // the event store's serializer: same stable type names
        .transactionProvider(transactionProvider)      // the JMS outlet handles each message in a transaction
        .subscriberName("ticket-service")             // durable subscription per group: "ticket-service.<processingGroup>"
        .build();

var tickets = new JmsEventMedium(jms, JmsDestination.topic("tickets.events"));
var billing = new JmsEventMedium(jms, JmsDestination.topic("billing.events"));

var eventBus = EventBusSpec.withDefaults()
        .interceptors(tracing, new TransactionalEventBusInterceptor(transactionProvider))
        .sinkInterceptors(tracing)
        .processingGroupInterceptors(tracing)
        .sinks(eventStore.inlet(),
               eventStore.outbox("ticket-events", tickets.inlet())
                       .selecting(EventSelector.eventType(TicketIntegrationEvent.class)))
        .processingGroups(locator, groups -> groups
                .processingGroup("billing-projection").source(eventStore.inbox(billing.outlet()))
                .anyProcessingGroup().source(eventStore.outlet()))
        .create();
```

- `outbox(name, sink)` fails with `"Event store already has an outbox named 'ticket-events'"` when a name is reused on one store. The name keys durable state, so renaming it starts a new, empty
  outbox. Entries left in the old log are not forwarded.
- Put `.selecting(...)` *outside* the outbox so only selected events are stored. A selector *inside* (`outbox(name, sink.selecting(...))`) is still correct, but every event is stored and the
  unselected ones are dropped when forwarded.
- `JmsEventMedium` used without wrappers (`.medium(tickets)`, `.sinks(tickets.inlet())`) is allowed and documented as non-transactional. It is a dual write, which is fine for notifications
  but is not an outbox.
- The JMS outlet fails early when a processing group subscribes without `subscriberName`: `"JMS topic 'billing.events' needs JmsConfig.subscriberName to name the durable subscription for
  processing group 'billing-projection'"`.
- A queue destination (`JmsDestination.queue(...)`) has no per-group copies, so a second group subscribing to the same queue outlet is rejected, naming both groups.

`eventStore.durable(name, medium)` returns an `EventMedium` whose inlet is `outbox(name, medium.inlet())` and whose outlet is `inbox(medium.outlet())`. A group that both publishes to and
consumes from one broker destination then needs a single line:

```java
groups.processingGroup("ticket-sync").medium(eventStore.durable("ticket-sync", tickets));
```

It is plain composition with no extra behavior, so `.selecting(...)` on the result filters both directions, as it does for any medium.

### Spring Boot

The connection comes from Spring Boot's Artemis support. SMD adds one small property block. Outboxes and inboxes stay explicit Java assembly: which events leave the application is a decision,
not a default.

```yaml
spring:
  artemis:
    broker-url: tcp://broker:61616
    user: ticket-service
    password: ${ARTEMIS_PASSWORD}
    pool:
      enabled: true

smd:
  event-store:
    enabled: true
  jms:
    enabled: true
    subscriber-name: ticket-service
    outlet:
      concurrency: 1          # consumers per subscription per instance
      receive-timeout: 1s
```

```java
@Bean(destroyMethod = "close")
JmsEventMedium ticketEvents(JmsConfig jms) {
    return new JmsEventMedium(jms, JmsDestination.topic("tickets.events"));
}

@Bean(destroyMethod = "close")
JmsEventMedium billingEvents(JmsConfig jms) {
    return new JmsEventMedium(jms, JmsDestination.topic("billing.events"));
}

@Bean
EventBusSpecCustomizer ticketEventsOutbox(EventStore eventStore, JmsEventMedium ticketEvents) {
    return spec -> spec.sinks(eventStore.outbox("ticket-events", ticketEvents.inlet())
            .selecting(EventSelector.eventType(TicketIntegrationEvent.class)));
}

@Bean
ProcessingGroupsConfigurer billingGroups(EventStore eventStore, JmsEventMedium billingEvents) {
    return groups -> groups.processingGroup("billing-projection").source(eventStore.inbox(billingEvents.outlet()));
}
```

`SMDJmsAutoConfiguration` is `@ConditionalOnClass(JmsEventMedium.class)`, `@ConditionalOnBean(ConnectionFactory.class)` and `smd.jms.enabled=true`. It builds only the `JmsConfig` bean. No
outbox, inbox or medium is autoconfigured.

## Module Placement

```
core/smd-jms                       app.dodb.smd.jms         JmsConfig, JmsDestination, JmsEventMedium, JmsEventSink, JmsEventSource
  api(project(":smd-api"))
  api(project(":smd-event-store"))   // EventSerializer, SerializedEvent; see open question 4
  api("jakarta.jms:jakarta.jms-api:3.1.0")
  integrationTestImplementation("org.apache.activemq:artemis-jakarta-client")
  integrationTestImplementation("org.testcontainers:testcontainers-activemq")

core/smd-event-store               app.dodb.smd.eventstore  EventStore.outbox/inbox/durable(...), EventStoreConfig.sinkInterceptors(...)   // no JMS or OTel dependency

framework/smd-spring-boot-starter  app.dodb.smd.spring.jms  SMDJmsAutoConfiguration, SMDJmsProperties
  compileOnly(project(":smd-jms"))   // apps opt in by adding app.dodb:smd-jms and spring-boot-starter-artemis
```

The outbox and inbox are broker-independent and belong to the event store. They can be tested with the built-in dispatchers and work unchanged with any future Kafka or HTTP medium. Core stays
free of Spring, and the starter doesn't pull JMS into every application.

## Which ActiveMQ

| Option                         | Message groups (`JMSXGroupID`) | Broker duplicate detection                                      | Jakarta JMS 3            | Notes                                                                                     |
|--------------------------------|--------------------------------|-----------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------------|
| **Artemis + Jakarta JMS 3.1**  | Yes, per queue/subscription    | Yes: `_AMQ_DUPL_ID`, persisted ID cache per address             | `artemis-jakarta-client` | Shared durable subscriptions map directly to processing groups. Spring Boot `spring-boot-starter-artemis` |
| Artemis core client            | Yes                            | Yes                                                             | n/a                      | Vendor-locked API; buys nothing the medium needs                                          |
| ActiveMQ Classic 6 + Jakarta   | Yes                            | Producer audit by JMS message ID only (in memory, per producer) | Yes (6.x)                | No application-supplied dedup key; a restarted forwarder is a new producer, so resends are not caught |
| AMQP 1.0 (Qpid JMS)            | Artemis maps it                | Via `_AMQ_DUPL_ID` application property                         | Yes                      | Later option for non-Java consumers; same JMS API                                         |

**Recommendation:** a generic `smd-jms` on `jakarta.jms-api` 3.1, integration-tested only against Artemis. Everything except `_AMQ_DUPL_ID` is standard JMS. That property is a plain string,
set unconditionally, so `main` needs no Artemis dependency. The support claim: "JMS 3.1; verified on Artemis". Other brokers work without broker-side deduplication.

## Storage

The outbox and inbox reuse the event store's tables, token processor and sequence processor. Only a `log` dimension is new.

```sql
ALTER TABLE smd_event_store          ADD COLUMN log VARCHAR(255) NOT NULL DEFAULT 'events';
ALTER TABLE smd_token_store          ADD COLUMN log VARCHAR(255) NOT NULL DEFAULT 'events';
ALTER TABLE smd_event_sequence_state ADD COLUMN log VARCHAR(255) NOT NULL DEFAULT 'events';
-- keys become (log, message_id), (log, processing_group) and (log, processing_group, subject_id)
CREATE INDEX smd_event_store_log_sequence ON smd_event_store (log, sequence_number);
```

| Log                      | Written by                                 | Read by                                    | Consumer key in token/sequence state |
|--------------------------|--------------------------------------------|--------------------------------------------|--------------------------------------|
| `events`                 | `eventStore.inlet()`                       | `eventStore.outlet()` processing groups     | processing group (unchanged)         |
| `outbox:<name>`          | `outbox(name, sink).send(...)`             | that outbox's forwarder                    | outbox name                          |
| `inbox:<processingGroup>`| `inbox(source)`'s subscription for a group | that group's subscriber                    | processing group                     |

- **One sequence, read per log.** `sequence_number` stays one `BIGSERIAL` shared by all logs. Each cursor reads only its own log:

  ```sql
  SELECT … FROM smd_event_store
  WHERE log = ? AND sequence_number > ?
  ORDER BY sequence_number
  LIMIT ?
  ```

  It does not walk the whole sequence. The store reads one batch per poll (`batch-size` rows every `polling-delay`), so a cursor that skipped other logs' rows would be limited by the total
  traffic of every log. A new outbox on a large table would spend days skipping history before forwarding anything, and a quiet outbox could never catch up with a busy `events` log.
- **Hole checks.** Positions missing between two rows of one log are usually other logs' rows. When the cursor finds positions missing in its own log, it counts the rows in that range
  across all logs, using the existing unique index on `sequence_number`:

  ```sql
  SELECT count(*) FROM smd_event_store WHERE sequence_number > ? AND sequence_number < ?
  ```

  If every missing position exists in another log, there is no gap and the cursor continues. If any position is missing everywhere, it is a real gap and the store's existing gap handling
  applies. The check runs only when a hole is found, so it costs nothing on a log that receives all the traffic.
- **Separate keys per log.** An outbox and a processing group with the same name never share state, and a publication sent to both the inlet and an outbox becomes two rows.
- **Released behavior.** Existing rows migrate to `events`. The `events` log is read with the same query plus `log = 'events'`, so the inlet and outlet behave exactly as today.

The inbox stores with `INSERT … SELECT … WHERE NOT EXISTS (… log = ? AND message_id = ?)`, not `ON CONFLICT DO NOTHING`. PostgreSQL evaluates `nextval` before the conflict check, so every
duplicate would burn a sequence number and leave a gap. `NOT EXISTS` does not burn a position for an ordinary redelivery. One case still does: two consumers inserting the same message
concurrently both pass the check, one hits the unique key, and its rolled-back insert leaves a real gap. That only happens when the broker redelivers a message that another consumer is still
processing, such as after a consumer crash. It then falls under [Gaps](#gaps), like any rolled-back transaction.

### Gaps

Every log shares one sequence, so a real gap stalls every cursor until it is resolved, whichever log it came from. Today a gap is resolved by waiting `gap-timeout` (5m by default) and then
skipping it. That skip has two consequences:

- **Latency.** Any rolled-back transaction (a publication, an outbox send or an inbox insert) stalls every cursor, including local handler groups and every outbox, for up to `gap-timeout`.
- **Loss.** A transaction that commits *after* its position was skipped writes a row behind every cursor, and that row is never read. For handler groups, that is an existing silent loss.
  For an outbox it breaks the outbox's promise: an entry sent in a successful transaction is never forwarded.

**The outbox's at-least-once promise therefore depends on fixing gap handling first.** The leading candidate replaces the timeout with transaction visibility:
- each row records its writing transaction (`pg_current_xact_id()`);
- cursors read only rows from transactions older than `pg_snapshot_xmin(pg_current_snapshot())`, ordered by transaction and position.

Every transaction below that point has finished, so a missing position can be skipped safely and nothing waits for a timeout. The cost is that any long-running transaction in the database
delays cursors: latency rather than loss. This is an event-store change of its own, needed by handler groups as much as by the outbox, and is designed separately. Until it lands, the outbox's
guarantee holds only for transactions that commit within `gap-timeout` of taking their position.

## Outbox Semantics

- **Send.** `outbox.send(message)` defers an insert into `outbox:<name>` into the current transaction, exactly like the inlet. Without a transaction, the insert commits before `send` returns.
- **Forward.** A cursor per outbox runs on the store's scheduler. It deserializes each entry and calls `sink.send(message)` in subject order, inside the cursor's batch transaction. It does
  **not** add the attempt metadata (`processingId`, `sequenceNumber`, `subjectId`, `errorCount`) that handlers receive. The sink gets the message exactly as it was sent.
- **Transaction ownership.** The cursor's database transaction owns its token and per-subject state. The JMS send is not part of that transaction: it is a synchronous persistent send that
  completes before the database commits. No XA.

| Situation                                        | Result                                                                                                        |
|--------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| Forward succeeds, DB commit succeeds             | Delivered once                                                                                                |
| Forward succeeds, DB commit fails or process dies | Resent on a later poll → **duplicate**, dropped by `_AMQ_DUPL_ID` and the consumer's inbox                     |
| Later entry in the batch fails                   | Batch rolls back; the successful prefix is replayed → **duplicates** of that prefix                            |
| Forward fails or times out                       | That subject backs off and retries; other subjects continue                                                    |

- **Never abandon.** A handler group abandons a subject after `max-retries`. An outbox does not: dropping is not an acceptable outbox outcome. A failing subject keeps retrying, capped at
  `max-delay`, and stays visible in `EventSequenceState`. Its later entries wait behind it, which preserves order. A poison entry therefore blocks its subject. SMD offers no operator
  action for it in the first version (see [Decisions](#decisions)). "Never abandon" covers entries the forwarder reads. An entry whose position was skipped as a gap is never read at all,
  which is why the outbox depends on the [gap fix](#gaps).
- **Broker down.** Because nothing is abandoned, an outage loses nothing and preserves order. But each subject records failures and backs off on its own, so recovery can take up to
  `max-delay` after the broker returns, with one log line per subject per attempt. A group-level pause would fix that, but the outbox needs a signal from the sink to tell "medium unavailable"
  from "this message failed". See open question 8.
- **Interceptors and spans.** At publish time, the bus's sink interceptors run around `outbox.send` as around any sink. At forward time, the store runs its own
  `EventStoreConfig.sinkInterceptors(...)` around `sink.send(message)`, on the polling thread and inside the cursor's batch transaction. The `EventSinkContext` holds the wrapped sink, so
  `MessageTracing` records `send … delivery jms`. A forward that fails records the failure on that span (see [Observability](#observability)).
- **Scheduling disabled.** Entries are stored but not forwarded, the same as durable groups.

## Inbox Semantics

`inbox(source).subscribe(subscriber)`:

1. Subscribes to `source` with an internal subscriber that has the same processing-group name. For each message, it defers an insert-if-absent into `inbox:<processingGroup>` in the source's
   transaction.
2. Starts a cursor on `inbox:<processingGroup>` that delivers to `subscriber` with the store's normal semantics: subject order, attempt metadata, retry, backoff and abandonment per
   `processing` settings.
3. Returns an `EventSubscription` that closes both.

**What the inbox requires of its source.** The inbox's guarantee rests on one rule, which the `EventSource` contract does not state today: *a source may acknowledge or discard a message only
after `subscriber.on(message)` returns normally, and must redeliver it if `on` throws.* `on` returns only after the insert is durable:
- inside the source's transaction, the deferred insert runs before that transaction commits, and the source commits it before acknowledging;
- without a transaction, `TransactionProvider.defer` runs the insert immediately, in its own transaction.

The JMS outlet follows the rule (see the table below). The built-in dispatchers follow it trivially, since they never acknowledge anything. A source that acknowledges before calling `on`, such
as auto-acknowledging JMS or Kafka auto-commit, can lose messages behind an inbox. This proposal adds the rule to the `EventSource` documentation in `core-api.md` ("A source owns delivery
threads, ordering, retries, failures, and lifecycle").

With the JMS outlet as the wrapped source, each broker message is handled like this:

| Step                                          | Transaction                                                                  |
|-----------------------------------------------|------------------------------------------------------------------------------|
| Receive on a `SESSION_TRANSACTED` context      | JMS transaction open                                                         |
| Deserialize, insert-if-absent into inbox log   | `transactionProvider.doInNewTransaction(...)`, owned by the JMS outlet       |
| DB commit, then `context.commit()` (ack)       | DB first, broker acknowledgement second: a crash between → redelivery → no-op insert |
| Handler runs later on the polling thread       | The store's delivery transaction, independent of the broker                  |

- **Duplicates** are absorbed permanently: redeliveries, DLQ re-sends and manual replays all hit the `(log, message_id)` key.
- **Failures.** The inbox insert fails only when the database is down, and then rollback and redelivery is the right answer. Handler failures happen later with SMD's per-subject semantics,
  not the broker's DLQ, so a failing subject does not let its later events pass.
- **Isolation.** Inbound events never enter the `events` log. Groups on `eventStore.outlet()` don't see them, and outboxes never forward them. A group that needs both local and inbound events
  needs a merged source (open question 10).
- **Attempt metadata** comes from the inbox log (`sequenceNumber`, `errorCount` and so on), so it is local to this application.
- **Interceptors and spans.** The store runs `EventStoreConfig.sinkInterceptors(...)` around the inbox insert, on the source's thread and inside its transaction. The `EventSinkContext` holds
  the inbox log as an internal sink reporting `event-store`, so `MessageTracing` records `send … delivery event-store`. The insert is deferred to the end of the transaction, so the span
  covers handing the insert to the transaction, not the commit. The inlet's `send` span works the same way today. The later delivery to the subscriber is an ordinary processing-group
  handling with the bus's processing-group interceptors.

### The JMS Outlet Without an Inbox

`groups.processingGroup("notifications").source(billing.outlet())` runs handlers directly in the JMS outlet's transaction (same table as above, with the handler in place of the insert). Retry
policy is then the broker's: `redelivery-delay`, `max-delivery-attempts` and the dead-letter address. Ordered subscriptions need `redelivery-delay = 0`, which turns retries into a tight loop,
and dead-lettering lets the subject's later messages continue. Use the direct outlet for idempotent, order-tolerant handlers. Use the inbox for everything else.

Recommended Artemis address settings for SMD subscriptions:

```xml
<address-setting match="billing.events">
    <redelivery-delay>0</redelivery-delay>
    <max-delivery-attempts>5</max-delivery-attempts>
    <dead-letter-address>DLA</dead-letter-address>
    <auto-create-dead-letter-resources>true</auto-create-dead-letter-resources>
</address-setting>
```

### JMS Outlet Details

- Each `subscribe(subscriber)` opens a shared durable subscription named `<subscriberName>.<processingGroup>`. Every group gets its own copy and its own progress. All application instances
  attach to the same subscription and compete, and message groups keep each subject on one consumer.
- `concurrency(n)` sets consumers per subscription per instance, each on its own thread and context (default `1`). Java 25 removes the `synchronized` pinning that made blocking `receive`
  costly on virtual threads.
- Each rollback is logged with group, subscription, `smd_message_id`, `JMSType` and `JMSXDeliveryCount`.

## Ordering

Mapping: `JMSXGroupID = subjectId`, the same string the event store uses as the subject's identity. Subjectless events get `JMSXGroupID = "smd-subjectless"`, mirroring the store's single
subjectless sequence (open question 1).

Guaranteed for one subject, from one outbox to one subscription:

1. **Outbox → broker.** The forwarder sends in log order per subject. One forwarder runs per outbox cluster-wide (token lock), and a failing subject holds its later entries back. Each send is
   synchronous and persistent.
2. **Broker → consumer.** Artemis delivers a group in FIFO order to one consumer of a queue or shared subscription. *Spike: 6 groups × 40 messages, 3 competing consumers on one shared durable
   subscription. Each group landed on exactly one consumer, in order.*
3. **Consumer rollback.** A transacted rollback with `redelivery-delay = 0` keeps the group's order. *Spike: rolled back message 1 while 2–5 arrived; committed 1, 2, 3, 4, 5.*
4. **Inbox → handler.** Arrival order per subject becomes inbox-log order, then subject order with the store's retry.

Not guaranteed:

- **Across subjects**, outboxes or subscriptions.
- **With a redelivery delay.** *Spike: with `redelivery-delay = 2s`, rolling back message 1 while 2–5 arrive commits 2, 3, 4, 5, 1. With all five already queued, the outcome was
  timing-dependent.* SMD cannot enforce the broker setting, so the docs must state it.
- **After dead-lettering**, on the direct outlet. The inbox avoids it.
- **During consumer membership changes.** Not verified (open question 6).
- **Across two outboxes.** Two outboxes that split one subject's events by type each keep their own order, but not the subject's order across types.
- **Late commits, until the gap fix.** A position skipped after `gap-timeout` is never forwarded. This is existing store behavior, and the outbox inherits it until [gap handling](#gaps)
  changes.
- **Clustered Artemis**, which needs grouping handlers. The design assumes one live broker with an HA backup.

## Wire Format

`JmsEventSink` sends a `jakarta.jms.BytesMessage`, `DeliveryMode.PERSISTENT`, with no expiry and default priority. `JmsEventSource` reverses the mapping.

| SMD                                                  | JMS                                         | Notes                                                                                                   |
|------------------------------------------------------|---------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Payload                                              | Body (bytes)                                | `SerializedEvent.serializedPayload()`; JSON with `JacksonEventSerializer`                               |
| Event type                                           | `JMSType`                                   | Stable name from `EventTypeResolver`; usable in JMS selectors                                           |
| `MessageId`                                          | `smd_message_id`                            | UUID string; `JMSMessageID` is assigned by the provider and cannot carry it                             |
| `MessageId`                                          | `_AMQ_DUPL_ID`                              | Artemis duplicate detection; ignored by other brokers                                                  |
| Subject                                              | `smd_subject_id`                            | Absent for subjectless events. Inbound reads this, not `JMSXGroupID`                                   |
| Subject                                              | `JMSXGroupID`                               | Subject, or `smd-subjectless`                                                                           |
| Metadata (principal, timestamp, parent, properties)  | `smd_metadata`                              | `SerializedEvent.serializedMetadata()` as a UTF-8 string; authoritative on inbound                      |
| Trace context                                        | `traceparent`, `tracestate` (when present)  | Copies for broker tooling and OTel JMS instrumentation; inbound uses `smd_metadata`                     |
| Format version                                       | `smd_wire_version` = `1`                    | Inbound rejects versions it does not know                                                              |

The sink never receives attempt metadata from the outbox (see [Outbox Semantics](#outbox-semantics)), so nothing local leaks onto the wire. On a direct `.sinks(tickets.inlet())` the sink
sends the publication's metadata as is.

Metadata travels as one `smd_metadata` property rather than one JMS property per entry, for three reasons:

- It reuses `EventSerializer` unchanged.
- It round-trips the principal and parent exactly.
- It avoids JMS identifier rules: keys like `x-request-id` are not legal property names.

The cost is that metadata properties can't be used in broker selectors. Event type and subject can.

Inbound rebuilds a `SerializedEvent` from these properties and the body, then calls `EventSerializer.deserialize`. The two applications must agree on stable type names. An unknown type rolls
back. Behind an inbox it is redelivered until dead-lettered, because the insert never happened.

**CloudEvents.** The CloudEvents spec has no JMS binding. Its AMQP binding's `cloudEvents:id` names are not legal JMS identifiers. A conformant option is **structured mode**: the body is a
JSON CloudEvent (`id`, `type`, `subject`, `time`, `source`, and SMD metadata as extensions). That would be an opt-in wire format for non-SMD consumers. Deferred.

**Principal trust.** `SMDJacksonModule` serializes `Principal` with Jackson class type info. That is fine inside one application's own database. From a broker it is input from another trust
domain: it names a class to instantiate and claims an identity. The minimum before release is a polymorphic type allow-list for inbound deserialization (open question 3).

## Observability

The store reuses the existing `EventSinkInterceptor` contract for the two steps that run outside the bus: the outbox forward and the inbox insert. `EventStoreConfig.sinkInterceptors(...)` takes
them, applied in registration order with the first wrapping the rest, as on the bus. There is no new interceptor type, span type or attribute. Both steps record ordinary `send` spans, and
smd-monitor counts them like any other `send`.

The trace across two applications:

```text
publish TicketOpened                       event                                      (app A, request thread)
├─ send TicketOpened                       delivery event-store                       (eventStore.inlet())
├─ send TicketOpened                       delivery event-store                       (outbox: stored in the transaction)
├─ send TicketOpened                       delivery jms                               (outbox forward, app A polling thread)
├─ send TicketOpened                       delivery event-store                       (inbox insert, app B consumer thread)
└─ process TicketOpened                    group billing-projection, event-store      (app B, inbox polling thread)
```

Every span is a child of `publish`, following the existing rules:
- the polling and consumer threads have no current span, so a span's parent is the span in the message's metadata;
- the stored metadata carries `publish`'s `traceparent`;
- a `send` never changes the message's trace context.

`messaging.message.id` is the same across both applications. The two `delivery event-store` sends in app A are the inlet and the outbox. Only the forward says `jms`, so the broker hop
is the one `jms` span in the trace. The gaps between the spans show polling and broker latency. A forward that fails while the broker is down records an error span
for each attempt. That is visible, but it is noisy until open question 8 is settled.

**Spring Boot.** The starter installs its `MessageTracing` bean on `EventStoreConfig`, as it does on the buses, at `TRACING_INTERCEPTOR_ORDER`. Without an `OpenTelemetry` bean, or with
`smd.opentelemetry.enabled=false`, nothing is installed. A user-built `EventStoreConfig` must add `.sinkInterceptors(tracing)` explicitly, and the
[OpenTelemetry](../opentelemetry.md#without-spring) guide must list it next to the buses.

## Testing Plan

Unit tests in `smd-event-store` need no broker, because the outbox and inbox wrap any sink or source. Use the built-in dispatchers and small fakes:

- `outbox_send_forwardsAfterCommitInSubjectOrder`, `outbox_rolledBackTransaction_forwardsNothing`, `outbox_createdAfterPublications_forwardsOnlyLaterSends`
- `outbox_sinkFails_retriesSubjectWithoutAbandoning`, `outbox_sinkFails_otherSubjectsContinue`, `outbox_duplicateName_fails`
- `outbox_forward_omitsAttemptMetadata`
- `outbox_forward_runsStoreSinkInterceptorsAroundWrappedSink`, `inbox_insert_runsStoreSinkInterceptorsInSourceTransaction`
- `inbox_duplicateMessage_deliveredOnce`, `inbox_duplicateMessage_doesNotCreateSequenceGap`, `inbox_handlerFails_retriedBySubject`
- `inbox_events_notVisibleOnOutletOrOutbox`, `inbox_close_closesSourceSubscriptionAndCursor`
- `durable_medium_publishesThroughOutboxAndConsumesThroughInbox`
- `outlet_existingRows_readAsEventsLog` (migration)
- `outboxCursor_busyEventsLog_readsOnlyOwnRows`, `cursor_holeFilledByOtherLog_continuesWithoutGap`, `cursor_holeMissingFromAllLogs_detectsGap`

`smd-jms` unit tests: `send_eventMessage_mapsWireFormat` (property by property), `receive_unknownWireVersion_rollsBack`, `subscribe_withoutSubscriberName_fails`,
`subscribe_queueAlreadySubscribed_failsWithBothGroups`.

Integration tests in `smd-jms`, using Testcontainers PostgreSQL and `ArtemisContainer`:

- `outbox_storedEvents_arriveInSubjectOrderAcrossCompetingConsumers`
- `outbox_dbCommitFailsAfterSend_duplicateDroppedByBroker`
- `outbox_brokerStoppedThenStarted_allForwardedInOrder`
- `inbox_redeliveredAfterLostAck_storedOnce`
- `outlet_handlerFails_redeliveredThenDeadLettered`
- `roundTrip_traceparentAndLineage_preservedAcrossApplications` (two buses, one broker, `opentelemetry-sdk-testing`)

Spring integration tests:
- `SMDJmsAutoConfiguration` creates `JmsConfig` only with `smd.jms.enabled` and a `ConnectionFactory`, and autoconfigures no outbox or inbox.
- The event-store autoconfiguration installs `MessageTracing` as a store sink interceptor when an `OpenTelemetry` bean exists, and installs nothing when `smd.opentelemetry.enabled=false`.

Use bounded Awaitility waits and a short polling delay. The spike's `ArtemisContainer` setup (`apache/activemq-artemis:2.42.0`, address settings through
`-Dbrokerconfig.addressSettings.<match>.<property>`) carries over.

## Spike

[`docs/design/spikes/activemq-outbox`](spikes/activemq-outbox) is a standalone Gradle build, not part of the SMD build. Run it with `./gradlew -p docs/design/spikes/activemq-outbox test`
(needs Docker). It runs against Artemis 2.42.0 with the Jakarta client:

| Test                                                                     | Finding                                                                                     |
|--------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `sharedDurableSubscription_competingConsumers_keepsPerGroupOrder`        | 6 groups × 40 messages, 3 consumers: each group on one consumer, in order; all consumers used |
| `duplicateId_resentFromNewConnection_isDroppedByBroker`                  | Same `_AMQ_DUPL_ID` from a new connection is dropped; other IDs pass                        |
| `duplicateId_resentToTopic_isDroppedForDurableSubscription`              | Same for a topic with a durable subscription                                                |
| `rollback_withoutRedeliveryDelay_keepsGroupOrder`                        | Rollback of message 1 while 2–5 arrive commits 1, 2, 3, 4, 5                                |
| `rollback_withRedeliveryDelay_letsLaterGroupMessagesOvertake`            | With a 2s redelivery delay, commits 2, 3, 4, 5, 1                                           |

Stable over three consecutive runs. The spike tests broker behavior only, so it is unaffected by the change of model.

## Prior Art

| System                                | Outbox                                                                    | Inbox                                         | Compared with this design                                                                                     |
|---------------------------------------|---------------------------------------------------------------------------|-----------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| Debezium outbox (Outbox Event Router) | Application writes an outbox table; CDC from the WAL into Kafka (Kafka Connect) | Consumer's job, by event ID header            | Same "outbox table written in the transaction", but a separate CDC deployment. `outbox:<name>` rows are plain, so CDC stays possible |
| Spring Modulith externalization       | Event publication registry, one entry per listener; `@Externalized` sends after commit; incomplete entries resubmitted | None built in                                 | Closest to "forward what was sent": registry entries exist only for actual targets. No per-subject order or retry state |
| Axon (Kafka/AMQP extensions)          | A tracking processor over the event store runs a publisher                | Kafka/AMQP message source into processors     | This is the relay model this note rejects: it forwards the log, not what was sent to the destination         |
| Eventuate Tram                        | `message` table per destination + CDC service to Kafka, ActiveMQ, RabbitMQ, Redis | `received_messages` table for idempotency     | Same outbox/inbox split, with the relay as a separate service. SMD's inbox also *orders and retries*, not just deduplicates |

What SMD adds: the outbox and inbox are the event store's existing delivery machinery (subject order, retry, backoff, abandonment) put around any sink or source, configured with the existing
delivery API.

## Rejected Alternatives

- **Relay as a processing group over the event log** (`EventBusSpec.relay(group, eventStore.outlet(), sink)`, or a delivery-level `EventRelay`). It forwards whatever is in the log rather than
  what was sent to the destination:
  - a new relay replays all history, because a new cursor starts at position 0;
  - inbox events echo back out;
  - it needs a made-up group that shares token state with handler groups;
  - every relay needs an explicit selector to be safe.
- **A cursor over the shared `events` log with a membership table.** The event is stored once, but it makes the main outlet deliver inbound events, unless the outlet is filtered by
  membership too, which amounts to a log column.
- **Separate tables per outbox and inbox.** Each would need its own contiguous sequence and its own copies of the token and sequence tables. A shared sequence with a `log` column keeps one
  gap model.
- **Walking the shared sequence and skipping other logs' rows.** It reuses the outlet-filter mechanism, but a cursor's throughput becomes the total traffic of all logs divided into
  one batch per poll. A new outbox would start by skipping the whole history.
- **Send to JMS in the publishing transaction, or after commit.** That is a dual write: a rollback after the send leaves a sent message, and a crash after the commit loses it.
- **XA / two-phase commit.** Heavy, needs a transaction manager, and SMD core does not own the transaction technology.
- **Artemis core API or an ActiveMQ-only module.** Vendor lock-in for one property.
- **ActiveMQ Classic as the reference broker.** No persistent, application-keyed duplicate detection.
- **In-process retry in `JmsEventSource`.** It duplicates the broker's policy, blocks the consumer and every group on it, and still ends in the DLQ. The inbox is the answer.
- **One JMS property per metadata entry.** Keys are not legal JMS identifiers in general, and the principal would need a second serialization path.
- **Autoconfigured outboxes from properties.** What leaves the application is a decision.

## Open Questions

1. **Subjectless grouping.** Pin subjectless events to one group (`smd-subjectless`: mirrors the store, serializes them on one consumer), or leave them ungrouped (parallel, unordered)? The
   proposal is to pin them.
2. **Latency and throughput.** Each hop waits up to one polling delay (5s by default): outbox forward, then inbox delivery. One forwarder runs per outbox cluster-wide, with one synchronous send
   per event. Options are `LISTEN/NOTIFY` wake-ups, and batched transacted JMS sessions committed before the DB.
3. **Inbound principal.** Trust it, replace it with a configured principal, or restrict it to an allow-list of `Principal` types?
4. **Serialization placement.** `smd-jms` depends on `smd-event-store` only for `EventSerializer` and `SerializedEvent`. Should they move to `smd-api` or a small `smd-serialization` module
   before 0.1? That would be a breaking package move.
5. **Gap handling.** Replace `gap-timeout` with transaction visibility, or something else? This is a prerequisite for the outbox's promise and a fix for an existing loss in handler groups
   (see [Gaps](#gaps)). It is designed separately.
6. **Order across consumer reassignment.** Needs a spike: stop a consumer mid-group with prefetched and in-flight messages. Consider `consumerWindowSize = 0` for ordered subscriptions.
7. **Inbound delivery count.** Should the direct JMS outlet expose `JMSXDeliveryCount` as metadata, like the store's `errorCount`?
8. **Outage signal.** Correctness needs no new API, because an outbox never abandons. A clean group pause (fast recovery, one log line) needs the sink to say "medium unavailable", for example
   with an `EventDeliveryUnavailableException` in `smd-api` thrown by `JmsEventSink` on JMS failures. Add it, or accept per-subject backoff?
9. **Other sink interceptors on the store.** The starter installs `MessageTracing` on the store. Should it also install the application's other `EventSinkInterceptor` beans, as it does on the
    bus? That would be consistent, but those interceptors would then also see forwards and inbox inserts on polling and consumer threads.
10. **Merged sources.** A group has one source. A group that needs local and inbound events needs a merging `EventSource`. Is that needed?

## Decisions

- **`eventStore.durable(name, medium)`** is part of the API: an outbox inlet plus an inbox outlet for `.medium(...)` composition.
- **Tracing the outbox forward and the inbox insert** uses `EventStoreConfig.sinkInterceptors(...)` with the existing `EventSinkInterceptor` contract, and the starter installs
  `MessageTracing` there (see [Observability](#observability)). Rejected:
  - no spans: the hop is invisible in smd-monitor;
  - user-applied `tracing.traced(sink)` wrappers: easy to forget, and they can't reach the inbox insert;
  - relying on the OTel Java agent's JMS instrumentation: it starts a new trace on the polling thread and overwrites the copied `traceparent`. It remains usable alongside.
- **The outbox and inbox report `event-store` as their `delivery()`.** The publish-time outbox span and the forward span are then distinct (`event-store`, then `jms`), and the inbox
  group's `process` span says where its handlers read from. The selecting-wrapper precedent of reporting the delegate's delivery does not apply, because these wrappers change the medium.
- **Poison entries in an outbox** get no operator action in code for now. A blocked subject is visible in `EventSequenceState` and in the forwarder's logs. Fixing it is a database
  operation. Revisit once real usage shows what is needed.

## Implementation Slices

0. Prerequisite, designed separately: [gap handling](#gaps) that never skips a position whose transaction may still commit.
1. `smd-event-store`: `log` column, per-log reads with hole checks, per-log state, and a migration script. `events` behavior is unchanged, so existing tests must pass untouched.
2. `smd-event-store`: `EventStoreConfig.sinkInterceptors(...)` and `EventStore.outbox(name, sink)`: deferred insert, intercepted forwarder without attempt metadata, never-abandon policy.
   Tested with built-in dispatchers.
3. `smd-event-store`: `EventStore.inbox(source)`: insert-if-absent, per-group cursor, combined subscription. Then `EventStore.durable(name, medium)`. Tested with built-in dispatchers.
4. `core/smd-jms`: `JmsConfig`, `JmsDestination`, `JmsEventMedium` (sink, source, wire format). Artemis integration tests, including the outbox and inbox round trip.
5. Decide open question 8; if accepted, add the outage signal and the group pause.
6. Starter: `MessageTracing` on `EventStoreConfig`, `SMDJmsAutoConfiguration` and properties, `docs/jms.md` and `opentelemetry.md` updates, and a second application in the
   ticket-service example.
