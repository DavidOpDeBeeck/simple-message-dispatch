# Durable Delivery

`smd-durable-delivery` stores each event in the publisher's transaction and delivers it to its subscribers until they succeed, in order per subject. Use it for work that must
happen for every committed event, such as calling an external system, within one application or between applications through a broker. It uses its own PostgreSQL 15+ tables and
is included in the Spring Boot starter.

A `DurableEventDispatcher` is an `EventMedium`. Its inlet stores events; its outlet delivers them to every subscriber: a processing group or a
[route](core-api.md#route-between-media) to another medium, such as a broker.

Unlike the [event store](event-store.md), a dispatcher keeps no history: an event leaves the database once every subscriber has handled it, and a new subscriber receives only
events stored after it first subscribed.

## Guarantees

| Concern      | Behavior                                                                                                       |
|--------------|----------------------------------------------------------------------------------------------------------------|
| Storing      | An event is stored only if the publisher's transaction commits; a failure to store fails the publisher         |
| Delivery     | At least once per subscriber; a duplicate from a broker is dropped while the original still waits for delivery |
| Order        | Per [`@SubjectId`](event-store.md#identify-event-subjects), in the order the events were stored                |
| Failures     | Retried with backoff, never abandoned; only events that share the failed event's lane wait                     |
| Transactions | A subscriber's handlers and the removal of the handled events commit together                                  |

Handlers must still be idempotent towards anything outside the database: a crash, a later failure in the same batch, or a redelivering broker can deliver an event again.

## Spring Boot Setup

Apply [durable-delivery-schema.sql](../core/smd-durable-delivery/src/main/resources/db/smd/durable-delivery-schema.sql) in a versioned migration, then enable durable delivery:

```yaml
smd:
  durable-delivery:
    enabled: true
```

The starter creates a `DurableDelivery` bean. Call `dispatcher(name)` on it to create a dispatcher.

### Deliver Within One Application

Give a processing group its own dispatcher as its medium:

```java
@Bean
ProcessingGroupsConfigurer ticketGroups(DurableDelivery durableDelivery) {
    return groups -> groups
            .processingGroup("customer-webhooks").medium(durableDelivery.dispatcher("ticket-service.customer-webhooks"))
            .anyProcessingGroup().sync();
}
```

The dispatcher stores only the events the group handles. Give each durable group its own dispatcher, so a failing group delays no other.

### Publish to a Broker

Publish selected events into an outgoing dispatcher and route it to the broker. `ticketTopic` stands for your broker medium, an `EventMedium` bean:

```java
@Bean
DurableEventDispatcher outgoingTicketEvents(DurableDelivery durableDelivery) {
    return durableDelivery.dispatcher("ticket-service.outgoing-ticket-events");
}

@Bean
EventBusSpecCustomizer publishTicketEventsDurably(DurableEventDispatcher outgoingTicketEvents) {
    return spec -> spec.sinks(outgoingTicketEvents.inlet().selecting(EventSelector.eventType(TicketClosedEvent.class)));
}

@Bean
EventRoute ticketEventsToBroker(DurableEventDispatcher outgoingTicketEvents, EventMedium ticketTopic) {
    return new EventRoute("ticket-service.ticket-events-to-broker", outgoingTicketEvents.outlet(), ticketTopic.inlet());
}
```

### Consume from a Broker

In the consuming application, route the broker into an incoming dispatcher and let processing groups read the dispatcher, not the broker:

```java
@Bean
DurableEventDispatcher incomingTicketEvents(DurableDelivery durableDelivery) {
    return durableDelivery.dispatcher("billing-service.incoming-ticket-events");
}

@Bean
EventRoute ticketEventsFromBroker(EventMedium ticketTopic, DurableEventDispatcher incomingTicketEvents) {
    return new EventRoute("billing-service.ticket-events-from-broker", ticketTopic.outlet(), incomingTicketEvents.inlet());
}

@Bean
ProcessingGroupsConfigurer billingProcessingGroups(DurableEventDispatcher incomingTicketEvents) {
    return groups -> groups
            .processingGroup("invoices").source(incomingTicketEvents.outlet())
            .processingGroup("payment-reminders").source(incomingTicketEvents.outlet());
}
```

Use `source(...)` here: `medium(...)` would also send the application's own publications into the dispatcher. A broker medium usually names its subscription after the route, so
keep inbound route names unique across applications.

SMD includes no broker medium. Any `EventSink` and `EventSource` work if they keep this contract:

- a sink returns from `send` only once the broker has stored the event;
- a source acknowledges a message only after the dispatcher's inlet returns, and redelivers when it throws;
- the broker keeps the order of messages with the same subject, for example through a Kafka key or an Artemis message group.

Configure the broker's redelivery to outlast a database outage: a dead-lettered message is an event missing from its subject. Applications that exchange events must resolve the
same [stable event type names](event-store.md#use-stable-event-type-names), and consumers must be deployed before producers publish a new type.

## Configuration

All properties live below `smd.durable-delivery`:

| Property                              |              Default | Meaning                                                |
|---------------------------------------|---------------------:|--------------------------------------------------------|
| `enabled`                             |              `false` | Create the `DurableDelivery` bean                      |
| `scheduling.thread-pool-size`         | available processors | Threads that deliver                                   |
| `scheduling.idle-backoff.strategy`    |        `EXPONENTIAL` | Delay between polls that find no work                  |
| `scheduling.idle-backoff.base-delay`  |              `200ms` | Delay after the first idle poll                        |
| `scheduling.idle-backoff.max-delay`   |                 `5s` | Longest wait before a new event is picked up when idle |
| `processing.batch-size`               |                 `10` | Most events delivered in one transaction               |
| `processing.lane-count`               |                 `16` | Lanes per subscriber; fixed once the dispatcher exists |
| `processing.retry-backoff.strategy`   |        `EXPONENTIAL` | Delay before retrying a failed event                   |
| `processing.retry-backoff.base-delay` |                 `1s` | Delay after the first failure                          |
| `processing.retry-backoff.max-delay`  |                 `5m` | Delay cap                                              |

Both backoffs also take `multiplier`, and `fixed-delay` or `increment` for the `FIXED` and `LINEAR` strategies.

Each subscriber's poll holds a thread for its whole batch, so give `thread-pool-size` at least one thread per durable subscriber, or a slow subscriber delays the others. A
failure rolls back its whole batch, so a smaller `batch-size` means fewer events delivered again.

Declare a `DurableEventDispatcherConfig` or `DispatcherStorage` bean to replace the starter's. Without an `EventSerializer` bean, the starter creates a `JacksonEventSerializer`.

## Order and Lanes

Each subscriber has `lane-count` lanes. An event's lane is chosen by its subject, or by its message ID when it has none, so a subject's events share a lane and are delivered in
order, one worker at a time. Instances of an application share the work by lane.

A failed event blocks the later events of its lane, whatever their subject, until it succeeds or is deleted. More lanes allow more parallel delivery and confine a failure to fewer
subjects.

Order holds from publisher to handler when the publisher serializes the transactions that publish for one subject, as an aggregate lock or version check does, and when no broker
in between reorders or dead-letters a subject's messages.

## Dispatchers and Subscribers

A dispatcher is identified by its name in the database, and every instance that uses that name shares its events. Prefix names with the application, such as
`ticket-service.customer-webhooks`.

A subscriber is identified by its processing group or route name and stays registered across restarts. From its first subscription on, every stored event is queued for it.

Delivery metadata holds the transient properties `smd.processingId`, `smd.errorCount`, and `smd.subjectId`, as in the event store. Messages a handler sends do not inherit them, and routes do not forward them.

## Without Spring

The PostgreSQL storage takes a `ConnectionProvider`. Durable delivery polls on the scheduler you pass and never closes it; close it on shutdown:

```java
var scheduler = Executors.newScheduledThreadPool(4);

var durableDelivery = new DurableDelivery(DurableEventDispatcherConfig.withDefaults()
        .dispatcherStorage(new PostgresDispatcherStorage(connectionProvider))
        .transactionProvider(transactionProvider)
        .eventSerializer(eventSerializer)
        .schedulingConfig(SchedulingConfig.withDefaults().scheduler(scheduler).build())
        .build());

var eventBus = EventBusSpec.withDefaults()
        .processingGroups(locator, groups -> groups
                .processingGroup("customer-webhooks").medium(durableDelivery.dispatcher("ticket-service.customer-webhooks"))
                .anyProcessingGroup().sync())
        .create();
```

## Operations

All state is in PostgreSQL:

| Table                       | Holds                                                                                       |
|-----------------------------|---------------------------------------------------------------------------------------------|
| `smd_dispatcher`            | Dispatchers and their lane count                                                            |
| `smd_dispatcher_subscriber` | Subscribers of each dispatcher                                                              |
| `smd_dispatcher_lane`       | Lanes of each subscriber                                                                    |
| `smd_dispatcher_queue`      | Events not yet handled by a subscriber, with `attempts`, `next_attempt_at` and `last_error` |

Repairs are database operations:

- **Retry a failed event now:** set its `next_attempt_at` to now.
- **Skip an event that can never be handled:** delete its row from `smd_dispatcher_queue`.
- **Remove a subscriber:** delete its rows from `smd_dispatcher_subscriber`, `smd_dispatcher_lane` and `smd_dispatcher_queue`; otherwise events keep queueing for it.
- **Rename a subscriber:** rename its rows in those three tables to keep its backlog.
- **Change `lane-count`:** a subscription with a different count than the stored one fails. Stop publishing, let `smd_dispatcher_queue` drain, stop the instances, delete the
  dispatcher's rows from `smd_dispatcher` and `smd_dispatcher_lane`, and start with the new count.
