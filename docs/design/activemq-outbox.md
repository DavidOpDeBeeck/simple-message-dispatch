# Design: Event Store Outbox and Inbox with ActiveMQ

Status: proposal, 2026-10-22. Nothing here is implemented or released. Existing event-store behavior stays the same, but the storage needs a schema migration (see [Storage](#storage)).

## Summary

- **Model.** The event store already makes delivery durable to its own log: transactional, ordered per subject, and retried. This proposal lets it do the same *around* another sink or
  source:
  ```java
  EventSink   eventStore.outbox(String name, EventSink sink)   // send = stored in the transaction, forwarded to sink later
  EventSource eventStore.inbox(EventSource source)             // received = stored with deduplication, delivered to the subscriber later
  ```
  An outbox forwards exactly what was sent to it. An inbox delivers exactly what the wrapped source delivers, to the same subscriber. Only the timing and the guarantees change, the same way
  `.selecting(...)` changes only which events pass.
- **ActiveMQ** is a plain `EventMedium`: `JmsEventMedium` pairs a `JmsEventSink` inlet with a `JmsEventSource` outlet. It is usable on its own when you don't need durability, like the
  built-in dispatchers. Durability comes from wrapping it.
- **Broker and API.** Target **ActiveMQ Artemis** through **Jakarta Messaging 3.1**, in a generic **`core/smd-jms`** module tested against Artemis. The only Artemis-specific piece is the
  `_AMQ_DUPL_ID` string property.
- **No new concept on the bus.** Configuration uses `.sinks(...)`, `.source(...)`, `.medium(...)` and `.selecting(...)` as they are today.
- **Ordering.** `@SubjectId` maps to `JMSXGroupID`. Per-subject order holds from the outbox to the consumer while group ownership is stable and the broker redelivers without delay. A spike
  confirms both the guarantee and the ways it breaks.
- **Delivery.** At least once in both directions. Duplicates are dropped by Artemis duplicate detection and permanently by the inbox, which deduplicates by `MessageId`.

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
4. **Each wrapper reports the delivery of what it wraps.** A selecting sink or source already does this. So the outbox in front of the JMS sink and the inbox behind the JMS source report
   `jms` (open question 4).

The existing store stays what it is: `eventStore.inlet()` and `eventStore.outlet()` read and write the store's own log, `events`.

## API and Configuration

### Without Spring

```java
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

A possible convenience, not part of the first slice: `eventStore.durable(name, medium)` returns an `EventMedium` whose inlet is `outbox(name, medium.inlet())` and whose outlet is
`inbox(medium.outlet())`, so `.medium(eventStore.durable("tickets", tickets))` works. See open question 12.

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
  api(project(":smd-event-store"))   // EventSerializer, SerializedEvent; see open question 5
  api("jakarta.jms:jakarta.jms-api:3.1.0")
  integrationTestImplementation("org.apache.activemq:artemis-jakarta-client")
  integrationTestImplementation("org.testcontainers:testcontainers-activemq")

core/smd-event-store               app.dodb.smd.eventstore  EventStore.outbox(...), EventStore.inbox(...)   // no JMS dependency

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
```

| Log                      | Written by                                 | Read by                                    | Consumer key in token/sequence state |
|--------------------------|--------------------------------------------|--------------------------------------------|--------------------------------------|
| `events`                 | `eventStore.inlet()`                       | `eventStore.outlet()` processing groups     | processing group (unchanged)         |
| `outbox:<name>`          | `outbox(name, sink).send(...)`             | that outbox's forwarder                    | outbox name                          |
| `inbox:<processingGroup>`| `inbox(source)`'s subscription for a group | that group's subscriber                    | processing group                     |

- **One sequence for all logs.** `sequence_number` stays one `BIGSERIAL`. Each cursor walks the shared sequence and skips other logs' rows, the same way an outlet filter skips nonmatching
  events today. Gap detection is therefore unchanged: a missing number is still a missing number. (A per-log query that checks holes against the shared sequence is a later optimization.)
- **Separate keys per log.** An outbox and a processing group with the same name never share state, and a publication sent to both the inlet and an outbox becomes two rows.
- **Released behavior.** Existing rows migrate to `events`, and the inlet and outlet behave exactly as today.

The inbox stores with `INSERT … SELECT … WHERE NOT EXISTS (… log = ? AND message_id = ?)`, not `ON CONFLICT DO NOTHING`. PostgreSQL evaluates `nextval` before the conflict check, so every
duplicate would burn a sequence number, and that gap would stall every cursor for `gap-timeout`. A concurrent race for the same ID still raises a unique violation, which rolls back and
redelivers, and the retry is a no-op.

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
  `max-delay`, and stays visible in `EventSequenceState`. Its later entries wait behind it, which preserves order. A poison entry therefore blocks its subject until an operator acts (open
  question 13).
- **Broker down.** Because nothing is abandoned, an outage loses nothing and preserves order. But each subject records failures and backs off on its own, so recovery can take up to
  `max-delay` after the broker returns, with one log line per subject per attempt. A group-level pause would fix that, but the outbox needs a signal from the sink to tell "medium unavailable"
  from "this message failed". See open question 9.
- **Delivery name and spans.** The outbox's `send` at publish time records a `send … delivery jms` span through the bus's sink interceptors. The later forward runs outside the bus and records
  no span (open question 10).
- **Scheduling disabled.** Entries are stored but not forwarded, the same as durable groups.

## Inbox Semantics

`inbox(source).subscribe(subscriber)`:

1. Subscribes to `source` with an internal subscriber that has the same processing-group name. For each message, it defers an insert-if-absent into `inbox:<processingGroup>` in the source's
   transaction.
2. Starts a cursor on `inbox:<processingGroup>` that delivers to `subscriber` with the store's normal semantics: subject order, attempt metadata, retry, backoff and abandonment per
   `processing` settings.
3. Returns an `EventSubscription` that closes both.

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
  needs a merged source (open question 11).
- **Attempt metadata** comes from the inbox log (`sequenceNumber`, `errorCount` and so on), so it is local to this application.

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
- **During consumer membership changes.** Not verified (open question 7).
- **Across two outboxes.** Two outboxes that split one subject's events by type each keep their own order, but not the subject's order across types.
- **Missing positions.** A position skipped after `gap-timeout` is never forwarded. This is existing store behavior, and the outbox inherits it.
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

The trace across two applications, with the current interceptors:

```text
publish TicketOpened                       event                                      (app A, request thread)
├─ send TicketOpened                       delivery event-store                       (eventStore.inlet())
└─ send TicketOpened                       delivery jms                               (outbox: stored in the transaction)
     … forwarded on app A's polling thread, received on app B's consumer thread: no spans …
   process TicketOpened                    group billing-projection, jms              (app B, inbox polling thread)
```

App B's `process` span continues app A's `publish`, because the stored metadata carries its `traceparent`. `messaging.message.id` is the same in both applications. The broker hop shows up only
as the time between the spans. Recording spans for the forward and for the inbox insert is open question 10.

## Testing Plan

Unit tests in `smd-event-store` need no broker, because the outbox and inbox wrap any sink or source. Use the built-in dispatchers and small fakes:

- `outbox_send_forwardsAfterCommitInSubjectOrder`, `outbox_rolledBackTransaction_forwardsNothing`, `outbox_createdAfterPublications_forwardsOnlyLaterSends`
- `outbox_sinkFails_retriesSubjectWithoutAbandoning`, `outbox_sinkFails_otherSubjectsContinue`, `outbox_duplicateName_fails`
- `outbox_forward_omitsAttemptMetadata`
- `inbox_duplicateMessage_deliveredOnce`, `inbox_duplicateMessage_doesNotCreateSequenceGap`, `inbox_handlerFails_retriedBySubject`
- `inbox_events_notVisibleOnOutletOrOutbox`, `inbox_close_closesSourceSubscriptionAndCursor`
- `outlet_existingRows_readAsEventsLog` (migration)

`smd-jms` unit tests: `send_eventMessage_mapsWireFormat` (property by property), `receive_unknownWireVersion_rollsBack`, `subscribe_withoutSubscriberName_fails`,
`subscribe_queueAlreadySubscribed_failsWithBothGroups`.

Integration tests in `smd-jms`, using Testcontainers PostgreSQL and `ArtemisContainer`:

- `outbox_storedEvents_arriveInSubjectOrderAcrossCompetingConsumers`
- `outbox_dbCommitFailsAfterSend_duplicateDroppedByBroker`
- `outbox_brokerStoppedThenStarted_allForwardedInOrder`
- `inbox_redeliveredAfterLostAck_storedOnce`
- `outlet_handlerFails_redeliveredThenDeadLettered`
- `roundTrip_traceparentAndLineage_preservedAcrossApplications` (two buses, one broker, `opentelemetry-sdk-testing`)

Spring integration test: `SMDJmsAutoConfiguration` creates `JmsConfig` only with `smd.jms.enabled` and a `ConnectionFactory`, and autoconfigures no outbox or inbox.

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
4. **Delivery name.** Should the outbox and inbox report the wrapped delivery (`jms`, as a selecting wrapper does) or `event-store`, which describes their own immediate step more literally?
5. **Serialization placement.** `smd-jms` depends on `smd-event-store` only for `EventSerializer` and `SerializedEvent`. Should they move to `smd-api` or a small `smd-serialization` module
   before 0.1? That would be a breaking package move.
6. **Gap-timeout latency.** A rolled-back publishing transaction leaves a sequence gap that stalls every cursor, outboxes included, for up to `gap-timeout` (5m). This is existing behavior that
   becomes visible externally.
7. **Order across consumer reassignment.** Needs a spike: stop a consumer mid-group with prefetched and in-flight messages. Consider `consumerWindowSize = 0` for ordered subscriptions.
8. **Inbound delivery count.** Should the direct JMS outlet expose `JMSXDeliveryCount` as metadata, like the store's `errorCount`?
9. **Outage signal.** Correctness needs no new API, because an outbox never abandons. A clean group pause (fast recovery, one log line) needs the sink to say "medium unavailable", for example
   with an `EventDeliveryUnavailableException` in `smd-api` thrown by `JmsEventSink` on JMS failures. Add it, or accept per-subject backoff?
10. **Spans for the hidden hops.** Should the outbox forward and the inbox insert record spans? Both run outside the bus, so they would need the bus's interceptors or their own `MessageTracing`
    hook.
11. **Merged sources.** A group has one source. A group that needs local and inbound events needs a merging `EventSource`. Is that needed?
12. **`eventStore.durable(name, medium)`.** Add the convenience medium (outbox inlet + inbox outlet) for `.medium(...)` composition, or keep the API to the two wrappers?
13. **Poison entries in an outbox.** A never-abandoning outbox blocks a subject on a poison entry. What operator action is supported: skip the entry, or move it aside, through a store API?

## Implementation Slices

1. `smd-event-store`: `log` column, per-log cursors and state, and a migration script. `events` behavior is unchanged, so existing tests must pass untouched.
2. `smd-event-store`: `EventStore.outbox(name, sink)`: deferred insert, forwarder without attempt metadata, never-abandon policy. Tested with built-in dispatchers.
3. `smd-event-store`: `EventStore.inbox(source)`: insert-if-absent, per-group cursor, combined subscription. Tested with built-in dispatchers.
4. `core/smd-jms`: `JmsConfig`, `JmsDestination`, `JmsEventMedium` (sink, source, wire format). Artemis integration tests, including the outbox and inbox round trip.
5. Decide open question 9; if accepted, add the outage signal and the group pause.
6. Starter: `SMDJmsAutoConfiguration` and properties, `docs/jms.md`, and a second application in the ticket-service example.
