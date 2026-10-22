# JMS and ActiveMQ

`smd-jms` sends and receives SMD events through a JMS 3.1 (Jakarta Messaging) broker. It is verified against ActiveMQ Artemis.

`JmsEventMedium` pairs a `JmsEventSink` that sends to one topic or queue with a `JmsEventSource` that receives from it. It works in two ways:

- **Behind [durable delivery](durable-delivery.md):** the sink is an outbox's `TransportSink` and the source is an inbox's `TransportSource`. Use this when events must leave the
  application with the transaction that published them and reach every handler once, in subject order.
- **Directly:** `inlet()` is an `EventSink` and `outlet()` an `EventSource`. Sending is not part of the publishing transaction, and each processing group reads its own subscription in
  arrival order.

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
    user: billing-service
    password: ${ARTEMIS_PASSWORD}

smd:
  durable-delivery:
    enabled: true
  jms:
    enabled: true
    subscriber-name: billing-service
```

| Property                         | Default | Meaning                                                                  |
|----------------------------------|--------:|--------------------------------------------------------------------------|
| `smd.jms.enabled`                | `false` | Create the `JmsConfig` bean; startup fails without a `ConnectionFactory` |
| `smd.jms.subscriber-name`        |         | Prefix of each durable subscription on a topic                           |
| `smd.jms.outlet.concurrency`     |     `1` | Consumers per subscription in each application instance                  |
| `smd.jms.outlet.receive-timeout` |    `1s` | Receive wait, and the delay before reconnecting after a failure          |

## Exchange Events Between Applications

Declare one medium per topic. The publishing application sends to its topic through an outbox:

```java
@Bean(destroyMethod = "close")
JmsEventMedium ticketEvents(JmsConfig jms) {
    return new JmsEventMedium(jms, JmsDestination.topic("tickets.events"));
}

@Bean
EventBusSpecCustomizer ticketOutbox(DurableDelivery delivery, JmsEventMedium ticketEvents) {
    var outbox = delivery.outbox("tickets.events", ticketEvents.inlet());
    return spec -> spec.sinks(outbox);
}
```

The consuming application reads every topic it needs through one inbox:

```java
@Bean
EventSource billingInbox(DurableDelivery delivery, JmsEventMedium ticketEvents, JmsEventMedium paymentEvents) {
    return delivery.inbox("billing", ticketEvents.outlet(), paymentEvents.outlet());
}
```

See [Durable Delivery](durable-delivery.md) for processing groups, ordering, and failure behavior.

## Subscriptions

On a topic, each subscriber opens a shared durable subscription named `<subscriber-name>.<subscriber>.<topic>`. The subscriber is the inbox name behind durable delivery, or the
processing group when a group reads `outlet()` directly.

- An inbox has one subscription per topic, whatever the number of processing groups.
- Every instance of the application attaches to the same subscription, so instances compete for messages.
- Messages that arrive before the subscription first exists are not kept. Create the subscription ahead of the first deployment when that matters.
- Artemis names the subscription's queue after the subscription name alone, so use a different `subscriber-name` for each application.

A queue has no copy per subscriber, so only one inbox or processing group may read a queue. A second one fails when it subscribes.

## Delivery

**Sending.** `JmsEventSink` sends a persistent message and returns once the broker has stored it. A failed send throws `JmsDeliveryException` naming the destination, message ID, and event
type. Behind an outbox, the event is retried and its subject's later events wait.

**Receiving.** The source consumes in a transacted JMS session and acknowledges a message only after the inbox stored it, or after a direct processing group handled it in a new transaction.
A crash in between redelivers the message; it is never lost. A message the source cannot read is rolled back until the broker moves it to its dead-letter address.

Behind an inbox, order does not depend on the broker: the inbox orders by outbox position. A direct processing group sees the broker's order, which is per subject through `JMSXGroupID`
as long as no redelivery delay is configured.

## Wire Format

Each event is a `BytesMessage`:

| JMS                         | Content                                                                                       |
|-----------------------------|-----------------------------------------------------------------------------------------------|
| Body                        | Serialized payload, JSON with `JacksonEventSerializer`                                        |
| `JMSType`                   | Stable event type name                                                                        |
| `JMSXGroupID`               | Subject, or `smd-subjectless`                                                                 |
| `smd_wire_version`          | `1`; the source rejects other versions                                                        |
| `smd_message_id`            | `MessageId`                                                                                   |
| `_AMQ_DUPL_ID`              | `MessageId`, for Artemis duplicate detection; other brokers ignore it                         |
| `smd_subject_id`            | Subject; absent for subjectless events                                                        |
| `smd_outbox`                | Name of the outbox that sent the event; absent when sent directly or without a subject        |
| `smd_position`              | The event's position within its outbox and subject; present with `smd_outbox`                 |
| `smd_metadata`              | Serialized metadata: principal, timestamp, parent message ID, and properties                  |
| `smd_timestamp`             | Metadata timestamp                                                                            |
| `traceparent`, `tracestate` | Copies of the trace context in the metadata, for broker tools; only on direct sends           |

An inbox requires `smd_outbox` and `smd_position` on every event with a subject, so publish to an inbox's topics only through an outbox. A message with a subject and no position is
rolled back until it is dead-lettered.

The principal is deserialized with the type information `SMDJacksonModule` writes. Only consume from brokers and producers you trust.

`JmsEventMedium.DELIVERY` is `jms`.
