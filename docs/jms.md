# JMS and ActiveMQ

`smd-jms` sends and receives SMD events through a JMS 3.1 (Jakarta Messaging) broker. It is verified against ActiveMQ Artemis.

`JmsEventMedium` is an ordinary `EventMedium`:
- its inlet, a `JmsEventSink`, sends each event to a topic or queue;
- its outlet, a `JmsEventSource`, delivers that destination's messages to processing groups.

On its own it is not transactional. Wrap it with the event store's [outbox and inbox](event-store.md#outbox-and-inbox) when events must leave the application with the transaction that published them, or must be handled with the store's ordering and retries.

## Add the Module

```kotlin
dependencies {
    implementation("app.dodb:smd-jms:0.0.12")
    implementation("org.springframework.boot:spring-boot-starter-artemis") // With Spring Boot
}
```

The Spring Boot starter does not include `smd-jms`. Adding the module and enabling it creates a `JmsConfig` bean from the application's `ConnectionFactory`, `EventSerializer`, and
`TransactionProvider`:

```yaml
spring:
  artemis:
    broker-url: tcp://broker:61616
    user: ticket-service
    password: ${ARTEMIS_PASSWORD}

smd:
  event-store:
    enabled: true
  jms:
    enabled: true
    subscriber-name: ticket-service
```

| Property                           | Default | Meaning                                                                     |
|------------------------------------|--------:|-----------------------------------------------------------------------------|
| `smd.jms.enabled`                  | `false` | Create the `JmsConfig` bean; startup fails without a `ConnectionFactory`     |
| `smd.jms.subscriber-name`          |         | Prefix of each processing group's durable subscription on a topic            |
| `smd.jms.outlet.concurrency`       |     `1` | Consumers per subscription in each application instance                     |
| `smd.jms.outlet.receive-timeout`   |    `1s` | Receive wait, and the delay before reconnecting after a failure              |

The event store provides the `EventSerializer`. Without the event store, declare one: both applications must resolve the same [stable event type names](event-store.md#use-stable-event-type-names).

## Publish Through an Outbox

Which events leave the application is a decision, so SMD configures no destination. Declare the medium and send to it through an outbox:

```java
@Bean(destroyMethod = "close")
JmsEventMedium ticketEvents(JmsConfig jms) {
    return new JmsEventMedium(jms, JmsDestination.topic("tickets.events"));
}

@Bean
EventBusSpecCustomizer ticketEventsOutbox(EventStore eventStore, JmsEventMedium ticketEvents) {
    return spec -> spec.sinks(eventStore.outbox("ticket-events", ticketEvents.inlet())
        .selecting(EventSelector.eventType(TicketIntegrationEvent.class)));
}
```

Publishing stores selected events in the outbox inside the publishing transaction. The event store then forwards them to the broker in subject order.

## Consume Through an Inbox

```java
@Bean(destroyMethod = "close")
JmsEventMedium billingEvents(JmsConfig jms) {
    return new JmsEventMedium(jms, JmsDestination.topic("billing.events"));
}

@Bean
ProcessingGroupsConfigurer billingGroups(EventStore eventStore, JmsEventMedium billingEvents) {
    return groups -> groups.processingGroup("billing-projection").source(eventStore.inbox(billingEvents.outlet()));
}
```

The inbox stores each received message once and then delivers it like any durable group: in subject order, with retry, backoff, and abandonment.

To publish to and consume from the same destination, `.medium(eventStore.durable("ticket-sync", tickets))` combines an outbox inlet with an inbox outlet.

## Without Spring

```java
var jms = JmsConfig.withDefaults()
    .connectionFactory(connectionFactory)
    .eventSerializer(eventSerializer)
    .transactionProvider(transactionProvider)
    .subscriberName("ticket-service")
    .build();

var tickets = new JmsEventMedium(jms, JmsDestination.topic("tickets.events"));
var billing = new JmsEventMedium(jms, JmsDestination.topic("billing.events"));

var eventBus = EventBusSpec.withDefaults()
    .interceptors(new TransactionalEventBusInterceptor(transactionProvider))
    .sinks(eventStore.inlet(), eventStore.outbox("ticket-events", tickets.inlet()))
    .processingGroups(locator, groups -> groups
        .processingGroup("billing-projection").source(eventStore.inbox(billing.outlet()))
        .anyProcessingGroup().source(eventStore.outlet()))
    .create();
```

Close each `JmsEventMedium` during shutdown to stop its consumers and release its connection.

## Subscriptions

On a topic, each processing group subscribes with a shared durable subscription named `<subscriber-name>.<processing-group>`:

- every group gets its own copy of each message and its own progress;
- every instance of the application attaches to the same subscription, so instances compete for messages;
- messages that arrive before a group first subscribes are kept only if the subscription already exists, so create it ahead of the first deployment when that matters.

Artemis names the subscription's queue after the subscription name alone, so the name must be unique on the broker. Use a different `subscriber-name` for each application.

A queue has no copy per group, so only one processing group may subscribe to a queue source. A second group fails when it subscribes.

## Delivery

Both directions deliver at least once.

**Sending.** `JmsEventSink` sends a persistent message and returns once the broker has stored it. Behind an outbox, the event store's polling transaction is still open at that point:
- if that transaction fails afterwards, the event is sent again;
- Artemis drops the repeat through duplicate detection on `_AMQ_DUPL_ID`, and the inbox drops it permanently by message ID.

A send that fails throws `JmsDeliveryException`, naming the destination, message ID, and event type. Behind an outbox, the subject retries with backoff and is never abandoned.

**Receiving.** The outlet consumes in a transacted JMS session:
- it runs the subscriber in a new transaction, then acknowledges the message;
- a crash between the two redelivers the message, and it is never lost;
- when the subscriber fails, the outlet rolls the session back and the broker redelivers.

## Ordering

The sink sets `JMSXGroupID` to the event's subject, so Artemis delivers each subject to one consumer at a time, in order. Subjectless events share the group `smd-subjectless`.

Order holds per subject from outbox to handler, with these exceptions:

- **Redelivery delay.** A broker redelivery delay lets later messages of a subject overtake a rolled-back one. Keep `redelivery-delay` at `0` for SMD subscriptions.
- **Dead-lettering.** After `max-delivery-attempts`, Artemis moves a message to the dead-letter address and the subject's later messages continue. Behind an inbox this needs a failing insert, which normally means the database is down. Handler failures are retried by the event store instead.
- **Consumer changes.** While consumers join or leave, ordering across the reassignment is not verified.

Recommended address settings for SMD destinations:

```xml
<address-setting match="billing.events">
    <redelivery-delay>0</redelivery-delay>
    <max-delivery-attempts>5</max-delivery-attempts>
    <dead-letter-address>DLA</dead-letter-address>
    <auto-create-dead-letter-resources>true</auto-create-dead-letter-resources>
</address-setting>
```

## Wire Format

Each event is a `BytesMessage`:

| JMS                         | Content                                                                                       |
|-----------------------------|-----------------------------------------------------------------------------------------------|
| Body                        | Serialized payload, JSON with `JacksonEventSerializer`                                        |
| `JMSType`                   | Stable event type name                                                                         |
| `JMSXGroupID`               | Subject, or `smd-subjectless`                                                                  |
| `smd_wire_version`          | `1`; the outlet rejects other versions                                                         |
| `smd_message_id`            | `MessageId`                                                                                   |
| `_AMQ_DUPL_ID`              | `MessageId`, for Artemis duplicate detection; other brokers ignore it                          |
| `smd_subject_id`            | Subject; absent for subjectless events                                                         |
| `smd_metadata`              | Serialized metadata: principal, timestamp, parent message ID, and properties                   |
| `smd_timestamp`             | Metadata timestamp                                                                             |
| `traceparent`, `tracestate` | Copies of the trace context in the metadata, for broker tools                                 |

The outlet reads the event from `smd_metadata`, not from the copies. A message the outlet cannot read, such as one with an unknown event type, is rolled back until the broker dead-letters it.

The principal is deserialized with the type information `SMDJacksonModule` writes. Only consume from brokers and producers you trust.

`JmsEventMedium.DELIVERY` is `jms`. Behind an outbox or inbox, tracing records `jms` on the forward to the broker and `event-store` elsewhere; see
[Outbox and Inbox](event-store.md#outbox-and-inbox).
