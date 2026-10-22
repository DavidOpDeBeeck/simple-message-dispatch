# Durable Delivery

Durable delivery moves events between applications through a broker without losing them and without reordering a subject. It lives in `smd-event-store`, in the package
`app.dodb.smd.eventstore.durable`, and uses its own PostgreSQL tables. It does not need the event store's tables.

- An **outbox** stores each event in the publishing transaction and forwards it to a transport afterwards.
- An **inbox** stores each received event once, then delivers it to every processing group that subscribes to the inbox.

The broker sits between them. [`smd-jms`](jms.md) provides the transport for JMS brokers such as ActiveMQ Artemis.

```
publishing application                         consuming application
handler ─► outbox ─► relay ─► broker topic ─► inbox ─► processing group A
           (same tx)                                  └► processing group B
```

## Guarantees

| Concern               | Behavior                                                                                                       |
|-----------------------|----------------------------------------------------------------------------------------------------------------|
| Publishing            | An event reaches the outbox only if the publishing transaction commits                                       |
| Delivery              | At least once from outbox to broker; duplicates are dropped by message ID in the inbox                        |
| Inbox storage         | Each event is stored once per inbox, whatever the number of processing groups                                 |
| Order                 | Per subject and outbox, in publishing order, regardless of the order the broker delivers in                   |
| Events without subject | No order; each one is delivered on its own                                                                   |
| Handler failures      | Retried with backoff, never abandoned; later events of the same subject wait                                 |
| Unknown event types   | Stored as received; the subject waits until a deploy can resolve the type                                     |
| Handler transaction   | The handler's changes commit together with the delivery's bookkeeping                                         |

Handlers must still be idempotent towards anything outside the database, because a crash after a handler's external side effect and before its commit delivers the event again.

## How Ordering Works

The outbox gives every event with a subject a **position**: a counter per outbox name and subject, assigned in the publishing transaction. The publishing transaction keeps the counter row
locked until it ends, so positions follow commit order and a rolled-back publication leaves no gap.

The inbox keeps a cursor per processing group, outbox name, and subject, and delivers only the event at the cursor's next position. When the broker delivers position 3 before position 2,
the inbox stores both and delivers them as 2, 3.

A missing position holds its subject: later events of that subject wait, and other subjects continue. This happens when a message never reaches the inbox, for example because it was moved
to a broker dead-letter address. After `stall-warning-after`, the inbox logs a warning with the inbox, processing group, outbox, subject, and missing position.

Two rules follow:

- **Outbox names must be unique across every application that feeds an inbox.** The inbox orders per outbox name and subject. Use names such as `<service>.<topic>`. When a second message
  arrives at a position that another message already holds, the inbox drops it and logs an error naming both message IDs.
- **A subject's cursor starts at the first position the inbox stores for it.** This lets an inbox join a topic that already carries events. When a lower position of a brand-new subject
  arrives after a higher one was stored, the lower one is logged as an error and not delivered.

## Spring Boot Setup

Apply [durable-delivery-schema.sql](../core/smd-event-store/src/main/resources/db/smd/durable-delivery-schema.sql) in a versioned migration, then enable durable delivery:

```yaml
smd:
  durable-delivery:
    enabled: true
```

The starter creates a `DurableDelivery` bean from the application's `DataSource`, `TransactionProvider`, and `EventSerializer`. The event store provides the `EventSerializer` when
`smd.event-store.enabled` is `true`; otherwise declare one. Both applications must resolve the same [stable event type names](event-store.md#use-stable-event-type-names).

| Property                                     | Default                | Meaning                                                                   |
|----------------------------------------------|-----------------------:|---------------------------------------------------------------------------|
| `smd.durable-delivery.enabled`               | `false`                | Create the `DurableDelivery` bean                                         |
| `smd.durable-delivery.polling-delay`         | `200ms`                | Delay between polls of each outbox and each inbox group without work     |
| `smd.durable-delivery.thread-pool-size`      | available processors   | Threads that send outbox entries and deliver inbox events                 |
| `smd.durable-delivery.stall-warning-after`   | `1m`                   | Wait on a missing position before warning, and the interval of the check  |

Failures are retried with exponential backoff from 1 second, doubling up to 5 minutes.

### Publish Through an Outbox

Which events leave the application is a decision, so SMD declares no outbox for you:

```java
@Bean
EventBusSpecCustomizer ticketOutbox(DurableDelivery delivery, JmsEventMedium ticketEvents) {
    var outbox = delivery.outbox("tickets.events", ticketEvents.inlet())
        .selecting(EventSelector.eventType(TicketClosedEvent.class));
    return spec -> spec.sinks(outbox);
}
```

### Consume Through an Inbox

Declare one inbox per application and list every topic it reads. Processing groups subscribe to the inbox, never to the broker:

```java
@Bean
EventSource billingInbox(DurableDelivery delivery, JmsEventMedium ticketEvents, JmsEventMedium paymentEvents) {
    return delivery.inbox("billing", ticketEvents.outlet(), paymentEvents.outlet());
}

@Bean
ProcessingGroupsConfigurer billingGroups(EventSource billingInbox) {
    return groups -> groups
        .processingGroup("invoices").source(billingInbox)
        .processingGroup("reminders").source(billingInbox);
}
```

The inbox starts receiving as soon as it is created. A processing group that subscribes for the first time receives every event the inbox has stored, including events that arrived
before it existed.

## Without Spring

```java
var delivery = new DurableDelivery(DurableDeliveryConfig.withDefaults()
    .connectionProvider(connectionProvider)
    .transactionProvider(transactionProvider)
    .eventSerializer(eventSerializer)
    .build());

EventSink outbox = delivery.outbox("tickets.events", tickets.inlet());
EventSource inbox = delivery.inbox("billing", tickets.outlet(), payments.outlet());
```

Close `DurableDelivery` during shutdown. It stops receiving, cancels polling, and shuts down its scheduler.

## Transports

An outbox sends to a `TransportSink` and an inbox reads from `TransportSource`s. Both exchange a `TransportEvent`: the event exactly as it was serialized, plus its `OutboxPosition` when it
has a subject. `JmsEventSink` and `JmsEventSource` implement them. A custom transport must:

- return from `send` only once the transport has stored the event;
- carry the serialized event and its position unchanged;
- acknowledge a received message only after `receive` returns, and redeliver it when `receive` throws.

The inbox retries a failing database write on the transport's thread instead of throwing, so the broker neither redelivers the message out of order nor dead-letters it. It throws only
when `DurableDelivery` closes.

## Delivery Metadata

Inbox handlers receive the original metadata plus `processingId`, `errorCount`, and `subjectId` when the event has one, the same keys as event-store handlers apart from `sequenceNumber`.

## Inspect and Repair

All state is in PostgreSQL:

| Table                  | Holds                                                                                     |
|------------------------|-------------------------------------------------------------------------------------------|
| `smd_outbox`           | Every published event; `sent_at` is set once sent, `attempts` and `last_error` on failure  |
| `smd_outbox_sequence`  | The last position per outbox and subject                                                  |
| `smd_inbox`            | Every received event, once per inbox                                                      |
| `smd_inbox_group`      | The processing groups registered on each inbox                                            |
| `smd_inbox_cursor`     | The next position per group, outbox, and subject                                          |
| `smd_inbox_pending`    | Events a group has not handled yet, with `attempts` and `last_error`                      |

Rows are kept indefinitely. Common repairs are database operations:

- **A subject waits for a position that will never arrive:** advance the cursor past it with
  `UPDATE smd_inbox_cursor SET next_position = <next available> WHERE inbox = ? AND processing_group = ? AND origin = ? AND subject_id = ?`.
- **A processing group was removed from the code:** delete its rows from `smd_inbox_group` and `smd_inbox_pending`; otherwise new events keep being queued for it.

## Schema

The schema needs PostgreSQL 15 or newer. Its tables are separate from the event store's, so it can be applied with or without the event store.
