# Design: Event Store Outbox with ActiveMQ

Status: proposal, 2026-10-22. Nothing here is implemented or released. This proposal does not change released behavior.

## Summary

- **Broker and API.** Target **ActiveMQ Artemis** through **Jakarta Messaging 3.1** (`jakarta.jms`). Build a generic **`core/smd-jms`** module and test it against Artemis with Testcontainers.
  Artemis-specific behavior is limited to one string property, `_AMQ_DUPL_ID`, which other brokers ignore.
- **Outbound.** A relay processing group reads `eventStore.outlet()` and sends each event to a `JmsEventSink`. Events are stored in the publishing transaction, as they are today. The relay
  reuses the event store's token, per-subject ordering, retry and abandonment. One small core addition wires it up: `EventBusSpec.relay(processingGroup, source, sink)`.
- **Inbound.** A `JmsEventSource` consumes a topic through one shared durable subscription per processing group. For ordered or retried handling, the recommended shape is an **inbox**: relay the
  `JmsEventSource` into the local event store (`eventStore.inbox()`, which deduplicates by `MessageId`), and let durable groups read it as usual.
- **Ordering.** `@SubjectId` becomes `JMSXGroupID`. Per-subject order holds from store to handler while group ownership is stable and the broker redelivers without delay. A spike confirms both
  the guarantee and the ways it breaks.
- **Delivery.** At least once in both directions. The relay's database transaction owns token advancement, and the JMS send completes before that transaction commits. Duplicates are dropped by
  Artemis duplicate detection and by `MessageId` deduplication on the consumer.
- **Broker outage.** Today an outage would exhaust retries and **abandon** subjects for good. This proposal adds `EventDeliveryUnavailableException`, which pauses the whole group with
  backoff instead of failing subjects.
- **Wire format.** The body is `SerializedEvent.serializedPayload`. `JMSType` holds the stable event type name, `smd_metadata` holds the serialized metadata, and `smd_message_id` and
  `smd_subject_id` hold the identity. Trace context is also copied into top-level properties. CloudEvents structured mode is deferred.

## Context

SMD already has every outbox ingredient except the broker adapter:

| Outbox need                       | SMD today                                                                                                       |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------|
| Store events atomically with state | `EventStore.inlet()` defers `INSERT` into the publishing transaction (`TransactionProvider.defer`)              |
| Durable cursor per consumer        | `smd_token_store` row per processing group, claimed with a lock so one poller runs per group cluster-wide        |
| Per-key ordering                   | `smd_event_sequence_state` per (group, subject): store order, retry backoff, abandonment                         |
| Stable type names                  | `EventTypeResolver` / `StaticEventTypeResolver`                                                                 |
| Serialization                      | `EventSerializer` → `SerializedEvent` (payload bytes, metadata bytes, type, subject, message ID)                |
| Trace propagation                  | `traceparent` in metadata properties, extracted by `MessageTracing` on any thread, instance or application       |
| Publication / subscription split   | `EventSink`, `EventSource`, `EventMedium`, `.selecting(EventSelector)`                                          |

So the outbox is *a durable processing group whose only side effect is a broker send*. The design questions are about the relay's transaction boundaries, the broker's ordering, the wire format
and the inbound side.

## Which ActiveMQ

| Option                         | Message groups (`JMSXGroupID`) | Broker duplicate detection                            | Jakarta JMS 3            | Notes                                                                                     |
|--------------------------------|--------------------------------|-------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------------|
| **Artemis + Jakarta JMS 3.1**  | Yes, per queue/subscription    | Yes: `_AMQ_DUPL_ID`, persisted ID cache per address   | `artemis-jakarta-client` | Shared durable subscriptions (JMS 2+) map directly to processing groups. Spring Boot `spring-boot-starter-artemis`. |
| Artemis core client            | Yes                            | Yes                                                   | n/a                      | Vendor-locked API; buys nothing the relay needs                                            |
| ActiveMQ Classic 6 + Jakarta   | Yes                            | Producer audit by JMS message ID only (in memory, per producer) | Yes (6.x)        | No application-supplied dedup key; a relay restart is a new producer, so resends are not caught |
| AMQP 1.0 (Qpid JMS)            | Artemis maps it                | Via `_AMQ_DUPL_ID` application property               | Yes                      | Interesting later for non-Java consumers; same JMS API                                    |

**Recommendation:** a generic `smd-jms` module on `jakarta.jms-api` 3.1, integration-tested only against Artemis. Everything the design relies on is standard JMS except
`_AMQ_DUPL_ID`. That is a plain string property, so the module sets it unconditionally and needs no Artemis dependency in `main`. An ActiveMQ-specific module would duplicate the JMS code for no
gain. Testing only Artemis keeps the support claim honest: "JMS 3.1; verified on Artemis". Other brokers work without broker-side deduplication.

## Module Placement

```
core/smd-jms                     app.dodb.smd.jms         JmsConfig, JmsDestination, JmsSubscription, JmsEventSink, JmsEventSource
  api(project(":smd-api"))
  api(project(":smd-event-store"))   // EventSerializer, SerializedEvent; see open question 5
  api("jakarta.jms:jakarta.jms-api:3.1.0")
  integrationTestImplementation("org.apache.activemq:artemis-jakarta-client")
  integrationTestImplementation("org.testcontainers:testcontainers-activemq")

framework/smd-spring-boot-starter   app.dodb.smd.spring.jms  SMDJmsAutoConfiguration, SMDJmsProperties
  compileOnly(project(":smd-jms"))   // @ConditionalOnClass(JmsEventSink.class); apps opt in by adding smd-jms
```

Core stays free of Spring. The starter does not pull JMS into every application. Apps opt in by adding `app.dodb:smd-jms` and `spring-boot-starter-artemis`.

Core changes outside the new module, each its own slice (see [Implementation Slices](#implementation-slices)):

| Module             | Addition                                                                                   | Why                                                               |
|--------------------|--------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `smd-api`          | `EventBusSpec.relay(String processingGroup, EventSource source, EventSink sink)`           | Bus-owned relay with lifecycle, validation and interceptors       |
| `smd-api`          | `EventDeliveryUnavailableException(EventDelivery, String, Throwable)`                      | Lets a sink say "the medium is down", rather than "this event failed" |
| `smd-event-store`  | Group pause on `EventDeliveryUnavailableException`                                         | A broker outage must not abandon subjects                         |
| `smd-event-store`  | `EventStore.inbox()` backed by `EventStorage.storeIfAbsent(SerializedEvent)`               | Inbound deduplication by `MessageId` without sequence gaps        |
| `smd-event-store`  | Public names for attempt metadata (`processingId`, `sequenceNumber`, `subjectId`, `errorCount`) | The relay must not leak local delivery metadata to other applications |

## Shape

### Outbound: relay group from the event-store outlet into a JMS sink

```java
var jms = JmsConfig.withDefaults()
        .connectionFactory(connectionFactory)          // jakarta.jms.ConnectionFactory
        .eventSerializer(eventSerializer)              // the event store's serializer: same stable type names
        .transactionProvider(transactionProvider)      // used by JmsEventSource only
        .build();

var ticketEvents = new JmsEventSink(jms, JmsDestination.topic("tickets.events"))
        .selecting(EventSelector.eventType(TicketIntegrationEvent.class));

var eventBus = EventBusSpec.withDefaults()
        .interceptors(tracing, new TransactionalEventBusInterceptor(transactionProvider))
        .sinkInterceptors(tracing)
        .processingGroupInterceptors(tracing)
        .sinks(eventStore.inlet())
        .relay("tickets-events-relay", eventStore.outlet(), ticketEvents)
        .processingGroups(locator, groups -> groups.anyProcessingGroup().source(eventStore.outlet()))
        .create();
```

`relay(processingGroup, source, sink)`:

- Subscribes an `EventSubscriber` named `processingGroup` to `source` when the bus is created. It lives for the bus's lifetime, like other bus-created subscriptions.
- For each delivered event, runs the processing-group interceptors (`ProcessingGroupContext` with that group and source), then the sink interceptors, then `sink.send(message)`. Tracing therefore
  records `process … group tickets-events-relay, event-store` → `send … delivery jms` with no extra code.
- Failures propagate to the source, which owns retries. For the event store, that means its per-subject retry and abandonment.
- Validation in `create()`: the name must be non-blank, unique among relays, and must not collide with a discovered processing group. Errors name the conflicting group.

Composition with the existing configuration is unchanged. `source` and `sink` are ordinary `EventSource` and `EventSink` values, so `.selecting(...)` applies on either side.
`JmsEventSink` can still be registered directly with `.sinks(...)`, but that is a dual write outside the publishing transaction. The docs must say that the relay is the outbox and the direct
sink is not.

#### Why a relay and not a handler or an `EventMedium`

- **Annotated handler group.** Handlers receive the payload plus selected context, not the `EventMessage`. Rebuilding the message identity, subject and metadata from parameters is lossy. The
  group would also be discovered and configured like application handlers, which invites `anyProcessingGroup()` to swallow it.
- **Unmanaged `eventStore.outlet().subscribe(relaySubscriber)`.** This works with zero core changes and is how the first spike of the real module can start. But it bypasses the
  processing-group and sink interceptors (so no tracing), and it leaves lifecycle and name validation to the application.
- **`EventMedium` (store inlet + JMS outlet).** A medium couples publishing and consuming in one application. The outbox's consumers are mostly *other* applications, and a medium hides the fact
  that two hops with different guarantees are involved. A `JmsEventSink` + `JmsEventSource` pair could implement `EventMedium` later as a convenience. It is not the primary shape.

### Inbound: `JmsEventSource`

```java
var billingEvents = new JmsEventSource(jms, JmsSubscription.sharedDurable(
        JmsDestination.topic("billing.events"),
        "ticket-service"));   // subscription name per group: "ticket-service.<processingGroup>"

// Simple: handlers consume directly from the broker (idempotent, broker-owned redelivery).
groups.processingGroup("billing-notifications").source(billingEvents);

// Recommended for ordered or retried handling: inbox into the local event store.
spec.relay("billing-inbox", billingEvents, eventStore.inbox());
groups.processingGroup("billing-projection").source(eventStore.outlet().selecting(EventSelector.eventType(BillingEvent.class)));
```

- Each `subscribe(subscriber)` opens its own shared durable subscription named `<prefix>.<processingGroup>`. Every group gets its own copy and its own progress, matching processing-group
  isolation. Every application instance attaches consumers to the same subscription, so instances compete and message groups keep each subject on one consumer.
- `JmsSubscription.queue(JmsDestination.queue(name))` is the point-to-point variant. A queue has no per-group copies, so a second `subscribe` on a queue source is rejected:
  `"JMS queue 'x' already feeds processing group 'a'; cannot also subscribe 'b'"`.
- `delivery()` returns `new EventDelivery("jms")`.

### Spring Boot

The connection comes from Spring Boot's Artemis support. SMD adds a small property block, and relays and sources stay explicit Java assembly:

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
    inbound:
      concurrency: 1          # consumers per subscription per instance
      receive-timeout: 1s
```

```java
@Bean
EventBusSpecCustomizer ticketEventsRelay(EventStore eventStore, JmsConfig jms) {
    return spec -> spec.relay(
            "tickets-events-relay",
            eventStore.outlet(),
            new JmsEventSink(jms, JmsDestination.topic("tickets.events"))
                    .selecting(EventSelector.eventType(TicketIntegrationEvent.class)));
}

@Bean(destroyMethod = "close")
JmsEventSource billingEvents(JmsConfig jms) {
    return new JmsEventSource(jms, JmsSubscription.sharedDurable(JmsDestination.topic("billing.events"), "ticket-service"));
}

@Bean
EventBusSpecCustomizer billingInbox(JmsEventSource billingEvents, EventStore eventStore) {
    return spec -> spec.relay("billing-inbox", billingEvents, eventStore.inbox());
}
```

`SMDJmsAutoConfiguration` is `@ConditionalOnClass(JmsEventSink.class)`, `@ConditionalOnBean(ConnectionFactory.class)` and `smd.jms.enabled=true`. It builds only the `JmsConfig` bean from the
`ConnectionFactory`, the `EventSerializer` and the `TransactionProvider`. It deliberately autoconfigures **no relay**: which events leave the application is a decision, not a default. See the echo
risk in [Inbox](#inbox-recommended).

## Ordering

Mapping: `JMSXGroupID = subjectId`. The string is identical to the event store's subject identity, so a subject is one ordered sequence in the store, on the wire and in the consumer.

Subjectless events share one global sequence per group in the event store. To keep that meaning, the relay sets `JMSXGroupID = "smd-subjectless"`, which pins them to one consumer. Leaving the
group unset would spread them round-robin. See open question 1. Collision with a real subject named `smd-subjectless` only over-serializes and never reorders.

What **is** guaranteed, end to end, for one subject on one subscription:

1. **Store → relay.** The relay group processes events in store order per subject (`EventSequenceProcessor`). One poller runs per group cluster-wide (token row lock). A failing subject backs off
   without blocking other subjects and without letting its own later events pass.
2. **Relay → broker.** Each send is synchronous and persistent, so the broker has the message before the relay moves to the subject's next event.
3. **Broker → consumer.** Artemis delivers one group, in FIFO order, to one consumer of a queue or shared subscription. *Spike: 6 groups × 40 messages, 3 competing consumers on one shared durable
   subscription. Each group landed on exactly one consumer, in order.*
4. **Consumer failure.** A transacted rollback with `redelivery-delay = 0` puts the message back at the head of the group. *Spike: rolled back message 1 while messages 2–5 arrived; committed order
   1, 2, 3, 4, 5.*
5. **Inbox.** Arrival order per subject becomes local store order, so local groups keep it with the store's retry semantics.

What is **not** guaranteed:

- **Order across subjects**, or across subscriptions or destinations.
- **Order with a redelivery delay.** *Spike: with `redelivery-delay = 2s`, rolling back message 1 while 2–5 arrive commits 2, 3, 4, 5, 1. With all five already queued, the outcome was
  timing-dependent (1, 2, 3, 4, 5 once, and 5, 1, 2, 3, 4 once).* Ordered subscriptions must use `redelivery-delay = 0`. SMD cannot enforce this, so the docs must state it.
- **Order after dead-lettering.** After `max-delivery-attempts`, Artemis moves the message to the DLA and the subject's *later* messages continue. This differs from SMD abandonment, which skips
  the subject's later events. The inbox avoids the gap because inbox inserts almost never fail. Real handler failures then get SMD's per-subject semantics.
- **Order during consumer membership changes.** Not verified here (deploys, crashes, scaling). Artemis reassigns a group when its consumer closes, and messages that were in flight or prefetched
  are redelivered. See open question 7.
- **Events the store skips.** An abandoned subject's later events are never relayed, so consumers see a truncated stream, logged on the producer side. A missing store position skipped after
  `gap-timeout` is never relayed either.
- **Splitting one subject's events across relays.** Two relay groups with disjoint *type* selectors each preserve their own order, but not the subject's order across types. Scale out by
  destination, never by splitting one subject's events.
- **Clustered Artemis.** Grouping in a cluster needs grouping handlers. The design assumes one live broker (with HA backup).

## Delivery Semantics

### Outbound: who owns what

The relay runs inside the event store's batch transaction (`EventStoreTokenProcessor.processBatchInNewTransaction`). That database transaction owns the token, sequence state and abandonment. The
JMS send is **not** part of it: it uses a non-transacted session with a synchronous persistent send, so `send()` returns after the broker has stored the message. No XA.

| Situation                                        | Result                                                                                                        |
|--------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| Send succeeds, DB commit succeeds                | Delivered once                                                                                                |
| Send succeeds, DB commit fails or process dies   | Token not advanced; resent on a later poll → **duplicate**, dropped by `_AMQ_DUPL_ID` and/or consumer dedup     |
| Later event in the batch fails                   | Batch rolls back; SMD replays the successful prefix in a smaller transaction → **duplicates** of that prefix   |
| Send fails or times out (outcome unknown)        | Treated as unavailable (below); resent later → possible duplicate                                              |
| Serialization or metadata mapping fails          | Ordinary subject failure: retry with backoff, then abandon (logged; visible in `EventSequenceState`)           |

Delivery is never lost silently, but there are two loud exceptions: an abandoned subject (logged at `ERROR`) and a store gap skipped after `gap-timeout`.

The order is deliberate. **JMS completes before the DB commits.** The reverse order (commit DB, then send) loses messages on a crash between the two. That is the dual-write problem the outbox
exists to remove.

A later throughput option: one transacted JMS session per batch, committed through `TransactionProvider.defer` (deferred work runs before the SMD transaction commits). This keeps "JMS before DB"
and amortizes the broker round trip. See open question 2.

### Broker down

Today, any exception from the relay is a subject failure. During an outage every subject fails in turn, and with the defaults (3 retries, backoff 1s/5s/25s) subjects are **abandoned after
about 30 seconds**. Their later events would never be relayed. That is not acceptable for an outbox.

Proposal:

- `JmsEventSink` throws `EventDeliveryUnavailableException(delivery, message, cause)` for any `JMSException` or `JMSRuntimeException` from connecting or sending. In practice JMS send failures are
  broker or transport conditions, including address-full with the `FAIL` policy. Failures before JMS (serialization, metadata mapping) stay ordinary exceptions.
- `EventStoreTokenProcessor` treats that exception as a **group** condition. It rolls back as today, replays the successful prefix, records **no** subject failure, and pauses that subscription
  in memory. The pause uses the configured `RetryBackoffStrategy` with an unbounded attempt counter, capped by `max-delay`. It logs once at `WARN` when pausing and once at `INFO` when resuming.
  Nothing is abandoned, nothing is written to `smd_event_sequence_state`, and order is preserved across all subjects.
- The exception lives in `smd-api` (`app.dodb.smd.api.event.delivery`), not in `smd-jms`. Any handler or sink fronting a remote dependency (HTTP, SMTP) can use it. Handler exceptions are preserved:
  a handler that throws it gets the same group pause.

### Deduplication

- **Broker.** The relay sets `_AMQ_DUPL_ID = messageId`. Artemis keeps a persisted ID cache per address (`id-cache-size`, default 20,000), so resends after a crash or a prefix replay are dropped.
  *Spike: resends with the same ID from a new connection were dropped on a queue and on a topic with a durable subscription.*
- **Consumer.** This is still required: the ID cache is finite, DLQ re-sends and manual replays bypass it, and other brokers ignore the property. `MessageId` is preserved end to end. The inbox
  deduplicates permanently through `smd_event_store.message_id`. Direct handlers use the documented `ON CONFLICT (event_id) DO NOTHING` pattern.

## Wire Format

A `jakarta.jms.BytesMessage`, `DeliveryMode.PERSISTENT`, no expiry, default priority.

| SMD                                       | JMS                                         | Notes                                                                                                                                       |
|-------------------------------------------|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Payload                                   | Body (bytes)                                | `SerializedEvent.serializedPayload()`; JSON with `JacksonEventSerializer`                                                                   |
| Event type                                | `JMSType`                                   | Stable name from `EventTypeResolver`, such as `ticket.opened.v1`; usable in JMS selectors. `ClassNameEventTypeResolver` works but couples consumers to Java class names |
| `MessageId`                               | `smd_message_id`                            | UUID string. `JMSMessageID` is assigned by the provider and cannot carry it                                                                 |
| `MessageId`                               | `_AMQ_DUPL_ID`                              | Artemis duplicate detection; ignored elsewhere                                                                                             |
| Subject                                   | `smd_subject_id`                            | Absent for subjectless events. Inbound reads this, not `JMSXGroupID`                                                                       |
| Subject                                   | `JMSXGroupID`                               | Subject, or `smd-subjectless`                                                                                                              |
| Metadata (principal, timestamp, parent, properties) | `smd_metadata`                    | `SerializedEvent.serializedMetadata()` as a UTF-8 string: the same bytes the event store stores. Authoritative on inbound                  |
| Trace context                             | `traceparent`, `tracestate` (when present)  | Copies of the metadata properties, for broker tooling and OTel JMS instrumentation. Inbound ignores them in favor of `smd_metadata`        |
| Format version                            | `smd_wire_version` = `1`                    | Lets inbound reject formats it does not know                                                                                              |
| Event-store attempt metadata              | *not sent*                                  | `processingId`, `sequenceNumber`, `subjectId` and `errorCount` describe the *relay's* local attempt. The sink strips them so consumers never mistake them for their own |

Why one `smd_metadata` property instead of one JMS property per metadata entry: it reuses `EventSerializer` unchanged and round-trips the principal and parent exactly. It also avoids JMS
identifier rules: metadata keys like `x-request-id` are not legal JMS property names. The cost is that metadata properties cannot be used in broker selectors. Event type and subject can.

Inbound rebuilds a `SerializedEvent` from `smd_message_id`, `smd_subject_id`, `JMSType`, the body, `smd_metadata` and the metadata timestamp, then calls `EventSerializer.deserialize`. The two
applications must agree on the stable type names. `EventTypeResolutionException` on inbound is a poison message (below).

**CloudEvents.** The CloudEvents spec has no JMS binding. Its AMQP binding names properties `cloudEvents:id`, which are not legal JMS identifiers, and the Kafka-style `ce_` prefix is not a
standard for JMS. A conformant option is **structured mode**: the body is a JSON CloudEvent with `content-type: application/cloudevents+json`, using `id = messageId`, `type = event type`,
`subject = subjectId`, `time = timestamp`, `source = configured URI`, and SMD metadata as extension attributes. It would be an opt-in wire format for non-SMD consumers, not the default. Deferred
until someone needs it.

**Principal trust.** `SMDJacksonModule` serializes `Principal` with Jackson class type info. Inside one application's own database that is fine. From a broker it is input from another trust
domain: it names a Java class to instantiate and claims an identity. See open question 3. The minimum before release is a polymorphic type allow-list for inbound deserialization.

## Inbound

### Direct `JmsEventSource`

Per consumer thread, using a `SESSION_TRANSACTED` `JMSContext`:

1. `receive(timeout)`. Reject unknown `smd_wire_version`.
2. Deserialize to `EventMessage`.
3. `transactionProvider.doInNewTransaction(() -> MetadataFactory.runInScope(message, () -> subscriber.on(message)))`
4. On success: `context.commit()` (acknowledge). On any exception: `context.rollback()`.

| Situation                                  | Result                                                                                                          |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| Handler and DB commit succeed, JMS commit succeeds | Processed once                                                                                           |
| DB commit succeeds, JMS commit fails or process dies | Broker redelivers → **duplicate**; handlers must be idempotent (same rule as event-store delivery)      |
| Handler fails                              | DB rolls back, JMS rolls back, broker redelivers (`JMSXDeliveryCount` + 1)                                     |
| Fails `max-delivery-attempts` times        | Broker moves it to the dead-letter address; **later messages of the subject continue**                          |
| Deserialization fails (unknown type)       | Same as handler failure: rolls back until dead-lettered                                                         |

The DB commits before the JMS acknowledgement, never the reverse. Acknowledging first loses the message on a crash.

The direct source does not retry in process. Retry policy belongs to the broker (`redelivery-delay`, `max-delivery-attempts`, dead-letter address), configured in one place. The catch: ordered
subscriptions need `redelivery-delay = 0`, which turns retries into a tight loop. So the direct source is for idempotent, order-tolerant handlers that rarely fail.

Concurrency: `concurrency(n)` consumers per subscription per instance, each on its own thread and context (default `1`). Message groups keep a subject on one consumer. Java 25 removes the
`synchronized` pinning that used to make blocking `receive` costly on virtual threads.

Metadata: like the event store adds attempt metadata, the source could add `deliveryCount` from `JMSXDeliveryCount` (open question 8).

### Inbox (recommended)

`spec.relay("billing-inbox", billingEvents, eventStore.inbox())` turns each received message into a local insert:

- `inbox()` runs `INSERT … SELECT … WHERE NOT EXISTS (… message_id = ?)` in the source's transaction, then the JMS commit. A duplicate is a no-op, so redeliveries and re-sends are absorbed
  permanently. It deliberately avoids `ON CONFLICT DO NOTHING`: PostgreSQL evaluates `nextval` before the conflict check, so every duplicate would burn a `sequence_number` and pause every
  durable group for `gap-timeout`. A concurrent race for the same ID still raises a unique violation. That rolls back and redelivers, and the retry is then a no-op.
- The inbox insert fails only when the database is down, and then rollback and redelivery is the right behavior. Real handler failures happen later in local durable groups, with SMD's
  per-subject retry, backoff and abandonment instead of the broker's DLQ.
- Arrival order per subject becomes local store order.
- **Echo risk.** Inbound events are now in the local store, so *every* durable group sees them, including outbound relays. An unselective outbound relay would republish foreign events. They
  carry the original `MessageId`, so every inbox, including the origin's, drops them and nothing loops forever. But they do leak. Outbound relays must select explicitly, which is why Spring
  autoconfigures none.

### Poison messages and DLQ

- Rely on Artemis address settings: `max-delivery-attempts` (default 10), `dead-letter-address` and optional `auto-create-dead-letter-resources`. Document a recommended `broker.xml` snippet
  for SMD subscriptions:
  ```xml
  <address-setting match="billing.events">
      <redelivery-delay>0</redelivery-delay>
      <max-delivery-attempts>5</max-delivery-attempts>
      <dead-letter-address>DLA</dead-letter-address>
      <auto-create-dead-letter-resources>true</auto-create-dead-letter-resources>
  </address-setting>
  ```
- Log each rollback with group, subscription, `smd_message_id`, `JMSType` and `JMSXDeliveryCount`. The final attempt is logged at `ERROR`.
- Moving a message from the DLA back is a broker operation. Inbox and handler deduplication make it safe.

## Observability

No new tracing code. Because the relay is bus-owned, the trace across two applications reads:

```text
publish TicketOpened                         event                                  (app A, request thread)
├─ send TicketOpened                         delivery event-store
└─ process TicketOpened                      group tickets-events-relay, event-store  (app A, polling thread)
   └─ send TicketOpened                      delivery jms
      … broker …
      process TicketOpened                   group billing-inbox, jms               (app B, consumer thread)
      ├─ send TicketOpened                   delivery event-store
      └─ process TicketOpened                group billing-projection, event-store  (app B, polling thread)
```

The relay's `process` span replaces `traceparent` in the message it sends, so app B's spans continue the relay's span and link back through lineage. `messaging.message.id` is the same in both
applications. The existing [OpenTelemetry](../opentelemetry.md) rules already cover this, and nothing about it is ActiveMQ-specific.

## Testing Plan

Unit tests (`smd-api`, `smd-event-store`, `smd-jms`) with small fakes:

- `relay_eventFromSource_sendsToSinkWithinInterceptors`, `relay_nameCollidesWithProcessingGroup_failsOnCreate`, `relay_sinkFails_failurePropagatesToSource`
- `poll_sinkUnavailable_pausesGroupWithoutFailingSubject`, `poll_sinkUnavailableThenAvailable_resumesInStoreOrder`
- `send_eventMessage_mapsWireFormat` (property-by-property), `send_stripsEventStoreAttemptMetadata`, `receive_unknownWireVersion_rollsBack`
- `subscribe_queueAlreadySubscribed_failsWithBothGroups`

Integration tests (`smd-jms`, `integrationTest`, Testcontainers PostgreSQL + `ArtemisContainer`):

- `relay_storedEvents_arriveInSubjectOrderAcrossCompetingConsumers`
- `relay_dbCommitFailsAfterSend_duplicateDroppedByBroker` (fail the commit through a `TransactionProvider` decorator)
- `relay_brokerStoppedThenStarted_noSubjectAbandonedAndAllDelivered` (`ArtemisContainer` stop/start, or Toxiproxy)
- `inbox_redeliveredMessage_storedOnce`, `inbox_duplicateId_doesNotCreateSequenceGap`
- `source_handlerFails_redeliveredThenDeadLettered`, `source_dbCommitsThenAckLost_redeliveredAndDeduplicated`
- `roundTrip_traceparentAndLineage_preservedAcrossApplications` (two buses, one broker, `opentelemetry-sdk-testing`)

Spring integration test (`smd-spring-boot-starter`): `SMDJmsAutoConfiguration` creates `JmsConfig` only with `smd.jms.enabled` and a `ConnectionFactory`, creates no relay, and closes sources on
shutdown.

Use bounded Awaitility waits and the event store's polling delay set low. The spike's `ArtemisContainer` setup (`apache/activemq-artemis:2.42.0`, address settings through
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

Stable over three consecutive runs.

## Prior Art

| System                              | Outbox capture                                                      | Ordering key                                    | Dedup                                                    | Compared with this design                                                                                          |
|-------------------------------------|---------------------------------------------------------------------|-------------------------------------------------|----------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| Debezium outbox (Outbox Event Router) | CDC from the WAL into Kafka; separate Kafka Connect deployment      | `aggregateid` → Kafka key → partition           | Consumer-side, by event ID header                        | Lower latency and no polling, but heavy operations and Kafka-centric. SMD's table is plain, so CDC stays possible later |
| Spring Modulith event externalization | Event publication registry table; `@Externalized` events sent after commit by Spring's async listener; incomplete publications resubmitted | Routing key from `@Externalized("target::#{…}")`; no ordering guarantee on resubmission | Consumer-side                                            | Same "registry is the outbox" idea with JMS/AMQP/Kafka adapters. No per-key retry state or abandonment; failures leave publications incomplete |
| Axon Framework (Kafka/AMQP extensions) | Event store + tracking processor running a publisher              | Sequencing policy (aggregate ID) → partition    | Consumer-side via event identifier                       | Closest analog: a tracking processor as relay, matching the `relay` group. Axon couples it to its own processor config; SMD keeps it a source → sink pair |
| Eventuate Tram                      | `message` table + CDC service (WAL tailing or polling) to Kafka, ActiveMQ, RabbitMQ, Redis | Partition by aggregate/destination key       | Idempotent consumer table (`received_messages`)          | Same inbox and outbox split, but relay runs as a separate CDC service. SMD's inbox reuses `smd_event_store.message_id` instead of a second table |

What SMD adds: per-subject retry, backoff and abandonment on *both* sides of the broker; group pause for broker outages; and one trace across the hop without a broker-specific tracer.

## Rejected Alternatives

- **Send to JMS in the publishing transaction, or after commit.** Before commit, a rollback leaves a sent message. After commit, a crash loses it. Both are the dual write the outbox replaces.
- **XA / two-phase commit between PostgreSQL and Artemis.** Heavy, needs a transaction manager, and SMD core does not own the transaction technology. Idempotent at-least-once is simpler and
  observable.
- **Artemis core API or an ActiveMQ-only module.** Vendor lock-in for one property.
- **ActiveMQ Classic as the reference broker.** No persistent, application-keyed duplicate detection.
- **`EventMedium` as the outbox shape.** See [Shape](#why-a-relay-and-not-a-handler-or-an-eventmedium).
- **One JMS property per metadata entry.** Metadata keys are not legal JMS identifiers in general, and the principal would need a second serialization path.
- **In-process retry in `JmsEventSource`.** It duplicates the broker's redelivery policy, blocks the consumer and every group it owns, and still ends in the DLQ. The inbox is the answer for
  rich retry.
- **Autoconfigured relay from properties.** Selecting what leaves the application is a deliberate decision, and an unselective relay republishes inbox events.
- **Count broker outages as subject retries with a large `max-retries`.** Every subject would accumulate failure state during an outage, and the store-wide setting would also apply to ordinary
  handler groups.

## Open Questions

1. **Subjectless grouping.** Should the relay pin subjectless events to one group (`smd-subjectless`, mirrors the store's global sequence, serializes them on one consumer) or leave them ungrouped
   (parallel, unordered)? The proposal is to pin them, for consistency.
2. **Relay throughput.** One poller per group cluster-wide, one synchronous send per event. Measure before adding batched transacted JMS sessions committed before the DB, or more relay groups
   partitioned by destination.
3. **Inbound principal.** Should the inbound principal be trusted, replaced by a configured principal, or restricted to an allow-list of `Principal` types? It currently arrives as Jackson class
   type info from another trust domain.
4. **Delivery name.** Generic `jms`, or a configurable name (such as `activemq`) per sink and source for clearer traces? A configurable name adds API surface.
5. **Serialization placement.** `smd-jms` depends on `smd-event-store` only for `EventSerializer` and `SerializedEvent`. Should these move to `smd-api` or a small `smd-serialization` module
   before 0.1? That would be a package move and breaking.
6. **Gap-timeout latency.** Any rolled-back publishing transaction leaves a sequence gap that stalls the relay (and every durable group) for up to `gap-timeout` (default 5m). This is existing
   behavior, but an outbox makes it externally visible as broker latency.
7. **Order across consumer reassignment.** Needs a spike: stop a consumer mid-group with prefetched and in-flight messages, then check whether the new owner can see a later message before the
   redelivered one. Consider `consumerWindowSize = 0` for ordered subscriptions.
8. **Inbound attempt metadata.** Should `JmsEventSource` add `deliveryCount` (from `JMSXDeliveryCount`) like the store adds `errorCount`?
9. **Consumption start.** Bus-created subscriptions start when the bus is created. Should `JmsEventSource` wait for an explicit start (Spring `SmartLifecycle`) so messages are not handled while
   the context is still starting? The event store uses `initial-delay` for this.
10. **Clustered brokers.** Message grouping in an Artemis cluster needs `LOCAL`/`REMOTE` grouping handlers. Is single live plus backup enough for the first release?

## Implementation Slices

Each slice is reviewable and releasable on its own:

1. `smd-api`: `EventBusSpec.relay(...)` with validation, interceptors and tests against the built-in dispatchers.
2. `smd-api` + `smd-event-store`: `EventDeliveryUnavailableException` and the group pause, with tests, and docs in `event-store.md`.
3. `smd-event-store`: public attempt-metadata names, `EventStorage.storeIfAbsent` (PostgreSQL and in-memory) and `EventStore.inbox()`, with a gap-free duplicate test.
4. `core/smd-jms`: `JmsConfig`, `JmsDestination`, `JmsEventSink` and wire format, with an Artemis relay integration test.
5. `core/smd-jms`: `JmsSubscription`, `JmsEventSource` and the inbox round trip, with poison and DLQ tests.
6. Starter: `SMDJmsAutoConfiguration` and properties, `docs/jms.md`, and a ticket-service example with a second application.
