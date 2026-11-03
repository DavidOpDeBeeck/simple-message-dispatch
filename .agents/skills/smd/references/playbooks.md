# SMD: Playbooks

## Contents

- [11.1 Start a new application](#111-start-a-new-application)
- [11.2 Add a command to an aggregate](#112-add-a-command-to-an-aggregate)
- [11.3 Add a plain command handler](#113-add-a-plain-command-handler)
- [11.4 Add a query or projection](#114-add-a-query-or-projection)
- [11.5 Add an external side effect](#115-add-an-external-side-effect)
- [11.6 Add a policy that reacts with a command](#116-add-a-policy-that-reacts-with-a-command)
- [11.7 Publish to or consume from a broker](#117-publish-to-or-consume-from-a-broker)
- [11.8 Rename or reshape an event](#118-rename-or-reshape-an-event)
- [11.9 Rename or remove a processing group](#119-rename-or-remove-a-processing-group)

## 11. Playbooks

Each playbook lists the steps in order. Verify with the tests of [12](testing.md#12-testing), then go through [15](../SKILL.md#15-checklist).

### 11.1 Start a new application

1. Add the dependencies of [2](setup.md#2-setup), and the PostgreSQL dependencies when you use aggregates or durable groups.
2. Create the packages and configuration classes of [3](structure.md#3-application-structure).
3. Copy the SMD schemas into Flyway migrations and enable the features in `application.yml`.
4. Add a `PrincipalProvider` in `infrastructure` ([9.1](metadata.md#91-principal)).
5. Add a `StaticEventTypeResolver` in `DrivenAdapterConfiguration` before the first event is stored.
6. Map SMD exceptions in a `@RestControllerAdvice` in `drivingadapter`: `AggregateNotFoundException` to `404`, `ConcurrentAggregateChangeException` and rejected
   transitions to `409`, `IllegalArgumentException` from message validation to `400`.

### 11.2 Add a command to an aggregate

1. Add the record in `drivingport.command` with `@TargetAggregateId` on the aggregate ID, unless it creates the aggregate.
2. Add the event record in `drivingport.event`: add it to the subject's sealed interface and map its type name in the `StaticEventTypeResolver`.
3. Add the `@AggregateCommandHandler` that validates and applies the event, and the `@EventSourcingHandler` that changes state.
4. Add `AggregateTestFixture` tests: the event applied, the result, and each rejection.
5. Update the projections that must show the change, then their integration tests.
6. Add the driving adapter endpoint that sends the command and queries the result.

### 11.3 Add a plain command handler

1. Add the command record in `drivingport.command`.
2. Add or extend a driven port in `drivenport` and its adapter in `drivenadapter`.
3. Add the `@CommandHandler` in `usecase`. Publish an event through `EventPublisher` when other parts care about the change.
4. Test it with `@EnableSMDStubs` and an in-memory fake of the port.

### 11.4 Add a query or projection

1. Add the query in `drivingport.query` and the DTO in `drivingport.transferobject`.
2. Add a reader method to a driven port in `drivenport`.
3. When a new read model is needed, add a processing group: a constant and a delivery in `DrivenAdapterConfiguration` ([4.3](decisions.md#43-event-delivery)), a migration for its
   table, and a class in `drivenadapter.<name>` that handles the events and implements the port. Make it idempotent unless it is `sync()`.
4. Add the `@QueryHandler` in `usecase`.
5. Test the query handler with `@EnableSMDStubs` and the projection with an integration test.

A new group on the event store outlet first processes every event ever stored. A new group on a durable dispatcher receives only events stored after its first start.

### 11.5 Add an external side effect

1. Add a driven port for the external system in `drivenport`, or in the adapter's package when only that adapter uses it.
2. Add a processing group in `DrivenAdapterConfiguration` with its own durable dispatcher.
3. Add the handler in `drivenadapter.<name>`. Pass the event's `MessageId` to the external system as the idempotency key. Let failures propagate.
4. Integration-test it with a fake of the port: the calls happen, in order per subject, and a failure is retried before later events of the subject.

### 11.6 Add a policy that reacts with a command

1. Add a processing group constant and a durable dispatcher in `UseCaseConfiguration`. Use `sync()` only when the second change must commit atomically with the first.
2. Add the `@EventHandler` in `usecase` that decides and sends the command through `CommandGateway`.
3. Make the command idempotent ([8.3](event-handlers.md#83-idempotency)).

### 11.7 Publish to or consume from a broker

SMD includes no broker medium. Write an `EventMedium` for the broker in `drivenadapter` whose sink returns only after the broker stored the event, whose source
acknowledges only after the subscriber returns and redelivers when it throws, and which keeps the order of messages with the same subject, for example through a Kafka key.

Publishing application:

```java
@Bean
DurableEventDispatcher outgoingTicketEvents(DurableDelivery durableDelivery) {
    return durableDelivery.dispatcher("ticket-service.outgoing-ticket-events");
}

@Bean
EventBusSpecCustomizer publishTicketEventsDurably(DurableEventDispatcher outgoingTicketEvents) {
    return spec -> spec.sinks(outgoingTicketEvents.inlet().selecting(EventSelector.eventType(TicketResolvedEvent.class)));
}

@Bean
EventRoute ticketEventsToBroker(DurableEventDispatcher outgoingTicketEvents, EventMedium ticketTopic) {
    return new EventRoute("ticket-service.ticket-events-to-broker", outgoingTicketEvents.outlet(), ticketTopic.inlet());
}
```

Consuming application: route the broker into an incoming dispatcher and read the dispatcher, never the broker:

```java
@Bean
DurableEventDispatcher incomingTicketEvents(DurableDelivery durableDelivery) {
    return durableDelivery.dispatcher("billing-service.incoming-ticket-events");
}

@Bean
EventRoute ticketEventsFromBroker(EventMedium ticketTopic, DurableEventDispatcher incomingTicketEvents) {
    return new EventRoute("billing-service.ticket-events-from-broker", ticketTopic.outlet(), incomingTicketEvents.inlet());
}
```

```java
groups.processingGroup(INVOICES).source(incomingTicketEvents.outlet())
```

- Use `.source(...)` for consuming groups: `.medium(...)` would also send the application's own events into the incoming dispatcher.
- Route names must be unique across applications and must not equal a group name.
- Both applications need the same event type names and records for the exchanged events. Share them as a published contract, not by copying classes ad hoc.

### 11.8 Rename or reshape an event

Follow [10](stored-events.md#10-stored-events-type-names-and-evolution). To rename only the class, keep the type name. Add a test that deserializes JSON stored under the old type name
through the application's `EventSerializer` bean and asserts the upcast record.

### 11.9 Rename or remove a processing group

- On a `sync()` or `async()` group, renaming has no effect on stored state.
- On the event store outlet, the new name starts at the first event and replays everything. Make the handlers idempotent first. The old name's progress stays in
  `smd_token_store` and `smd_event_sequence_state`.
- On a durable dispatcher, the old subscriber keeps queueing events until you delete its rows from `smd_dispatcher_subscriber`, `smd_dispatcher_lane`, and
  `smd_dispatcher_queue`. To keep its backlog, rename it in those three tables instead. Do this in a migration together with the code change.
