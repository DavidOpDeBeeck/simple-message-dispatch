# Deadlines

A deadline is a command that SMD sends later, unless you cancel it first. Use it for timeouts and reminders: escalate a ticket that nobody answered in 48 hours, expire a reservation
after 15 minutes, remind a customer after four hours.

```java
var escalation = deadlines.schedule(new EscalateTicketCommand(ticketId), ofHours(48));
// later, when the customer replies
deadlines.cancel(escalation);
```

`DeadlineScheduler` lives in `smd-api`. The durable implementation, `DeadlineStore`, ships with `smd-event-store` and stores deadlines in PostgreSQL 15 or newer.

## The API

```java
public interface DeadlineScheduler {

    DeadlineId schedule(Command<?> command, Instant dueAt);

    DeadlineId schedule(Command<?> command, Duration delay);

    boolean cancel(DeadlineId deadlineId);
}
```

- `schedule` returns a generated `DeadlineId`. Keep it, for example on the ticket, if you may want to cancel the deadline later.
- A `Duration` is added to the current time of the application's `TimeProvider`. A negative delay is rejected; an `Instant` in the past fires on the next poll.
- `cancel` returns `true` when it removed a deadline that had not fired yet, and `false` when the deadline already fired, was already cancelled, or never existed.
- Any `Command<R>` can be scheduled. Its result is discarded.

Schedule from a handler and keep the ID with the state it belongs to:

```java
@CommandHandler
public UUID handle(OpenTicketCommand command) {
    var ticketId = UUID.randomUUID();
    var escalation = deadlines.schedule(new EscalateTicketCommand(ticketId), ofHours(48));
    tickets.save(Ticket.open(ticketId, command.title(), escalation));
    return ticketId;
}

@CommandHandler
public void handle(AnswerTicketCommand command) {
    var ticket = tickets.find(command.ticketId()).orElseThrow();
    ticket.escalation().ifPresent(deadlines::cancel);
    tickets.save(ticket.answer(command.answer()));
}
```

The fired command is handled by its normal `@CommandHandler`. Check the state it acts on, because a deadline can still fire if a cancel was missed:

```java
@CommandHandler
public void handle(EscalateTicketCommand command) {
    tickets.find(command.ticketId())
        .filter(Ticket::awaitingCustomer)
        .ifPresent(ticket -> tickets.save(ticket.escalate()));
}
```

## Spring Boot Setup

The starter includes deadline support. Add the PostgreSQL dependencies from [Event Store](event-store.md#spring-boot-setup), copy
[deadline-store-schema.sql](../core/smd-event-store/src/main/resources/db/smd/deadline-store-schema.sql) into a versioned migration, and enable deadlines:

```yaml
smd:
  deadlines:
    enabled: true
```

Then inject `DeadlineScheduler` wherever you schedule or cancel. With deadlines disabled there is no `DeadlineScheduler` bean, so a component that needs one fails at startup.

| Property                     |       Default | Meaning                                                        |
|------------------------------|--------------:|----------------------------------------------------------------|
| `scheduling.enabled`         |        `true` | Fire due deadlines from this application                       |
| `scheduling.initial-delay`   |         `10s` | Wait before the first poll                                     |
| `scheduling.polling-delay`   |          `5s` | Delay between completed polls                                  |
| `processing.max-retries`     |           `3` | Retries after the first failed attempt                         |
| `processing.batch-size`      |         `100` | Maximum deadlines fired per poll                               |
| `processing.retry-backoff.*` | `EXPONENTIAL` | Same strategies as [Event Store](event-store.md#configuration) |

All properties live below `smd.deadlines`. A deadline fires up to one polling delay after it is due; deadlines are not a sub-second timer.

The default serializer stores the command's class name. Commands can stay stored for days, so give them stable names before you deploy, as with events:

```java
@Bean
CommandTypeResolver commandTypeResolver() {
    return new StaticCommandTypeResolver(Map.of(
        "ticket.escalate.v1", EscalateTicketCommand.class
    ));
}
```

## Transactions and Delivery

- **Scheduling and cancelling join the caller's transaction.** If the request that scheduled a deadline rolls back, the deadline is gone too. Without a transaction, the call commits
  before it returns.
- **Each deadline fires in its own transaction**, on the store's polling thread. The command handler joins that transaction, so its database changes, the events it stores, and the removal
  of the deadline commit or roll back together.
- **Delivery is at least once.** A crash before commit fires the deadline again. Every attempt sends the same command message, whose `MessageId` equals the `DeadlineId`; use it as an
  idempotency key for external calls.
- **Several application instances can poll the same table.** Each due deadline is locked with `FOR UPDATE SKIP LOCKED` while it fires, so only one instance fires it.
- **Cancel and fire do not overlap.** A `cancel` of a deadline that is firing waits for that transaction. If the fire commits, `cancel` returns `false` and the handler's changes are visible;
  if it rolls back, `cancel` removes the deadline and returns `true`.
- **There is no ordering guarantee** between deadlines, even for the same ticket.

## Metadata

The fired command carries the metadata of the message that scheduled it: its principal, its properties, and its ID as the parent. The timestamp is the time of the attempt, and two
properties are added:

| Property     | Value                                                |
|--------------|------------------------------------------------------|
| `dueAt`      | The due time, ISO-8601 (`DeadlineStore.DUE_AT`)      |
| `errorCount` | Failed attempts so far (`DeadlineStore.ERROR_COUNT`) |

Because trace context is part of the metadata, the fired command's span joins the trace of the request that scheduled it. See [OpenTelemetry](opentelemetry.md).

## Failures

A failing command rolls back its attempt and is retried with backoff. After `max-retries` retries the deadline is abandoned: it stays in `smd_deadline_store` with status `ABANDONED` and the
latest error, and never fires again. Inspect a deadline with:

```java
deadlineStore.deadlineState(deadlineId)
```

`DeadlineState` reports the due time, the next attempt, the status (`PENDING`, `FAILED`, or `ABANDONED`), the error count, and the latest error.

## Testing

`@EnableSMDStubs` replaces the injected `DeadlineScheduler` with `DeadlineSchedulerStub`, which never fires on its own. Advance time and fire due deadlines explicitly:

```java
smd.stubTimestamp(openedAt);
var ticketId = smd.send(new OpenTicketCommand("Cannot sign in"));

smd.stubTimestamp(openedAt.plus(ofHours(48)));
smd.fireDueDeadlines();
```

`smd.getDeadlines()` returns the pending deadlines, ordered by due time. Without Spring, construct `new DeadlineSchedulerStub(timeProvider)` and call `fireDue(commandGateway)`. See
[Testing](testing.md).

## Without Spring

```java
var deadlineStore = new DeadlineStore(DeadlineStoreConfig.withDefaults()
    .transactionProvider(transactionProvider)
    .principalProvider(principalProvider)
    .timeProvider(timeProvider)
    .deadlineStorage(new PostgresDeadlineStorage(connectionProvider))
    .deadlineSerializer(new JacksonDeadlineSerializer(mapper, commandTypeResolver))
    .build());

// Build the command bus with handlers that use deadlineStore, then:
deadlineStore.start(commandBus);
```

`start` begins polling; scheduling and cancelling work before it. Use the same `PrincipalProvider` and `TimeProvider` as the command bus, and register its
`TransactionalCommandBusInterceptor` so the handler joins the firing transaction. Close the store during shutdown. `InMemoryDeadlineStorage` is available for tests and demos; it is not
transactional and does not survive a restart.
