# Design Proposal: Deadlines

Status: implemented after 0.0.12; see [Deadlines](../deadlines.md) for the user guide. This note keeps the reasoning. Where the implementation differs from the first
sketch, the note has been updated: a flat `DeadlineStoreConfig`, no `scheduled_at` column, a separate `deadline-store-schema.sql`, and `void` handlers for `Command<Void>`.

A deadline is a command that SMD sends at a due time unless the deadline is cancelled first. Scheduling returns a generated ID, and the ID cancels it:

```java
var escalation = deadlines.schedule(new EscalateTicketCommand(ticketId), timestamp.plus(ofHours(48)));
// later, when the customer replies
deadlines.cancel(escalation);
```

This note proposes the smallest port that supports that, a PostgreSQL implementation beside the event store, and the semantics each implementation must keep. A separate session is designing
sagas on top of this port; a saga cancels its deadlines by the IDs it keeps in its state. Domain keys and scopes are deferred; see [Deferred: Keys and Scopes](#deferred-keys-and-scopes).

## Summary

| Question             | Proposal                                                                                                  |
|----------------------|-----------------------------------------------------------------------------------------------------------|
| What is scheduled    | Commands only, any `Command<R>`; the result is discarded                                                  |
| When                 | `Instant` or `Duration`; a `Duration` is added to `TimeProvider.now()` when you schedule                   |
| Identity             | A generated `DeadlineId`, returned by `schedule` and equal to the fired command's `MessageId`             |
| Cancel               | `cancel(id)`                                                                                              |
| Port                 | `DeadlineScheduler` in `smd-api`, package `app.dodb.smd.api.deadline`                                    |
| Durable store        | `DeadlineStore` in `smd-event-store`, package `app.dodb.smd.eventstore.deadline`, table `smd_deadline_store` |
| In memory            | `InMemoryDeadlineStorage` behind the same `DeadlineStore`, and `DeadlineSchedulerStub` in `smd-test`      |
| Scheduling tx        | Written in the caller's transaction; rolls back with it                                                   |
| Firing tx            | One new transaction per deadline: claim the row, send the command, delete the row, commit                 |
| Delivery             | At least once; the fired command keeps one `MessageId` across attempts                                    |
| Cluster              | `FOR UPDATE SKIP LOCKED` on the deadline row; the row lock, held until commit, is the claim              |
| Cancel versus fire   | Cancel blocks on a deadline that is being fired and then reports whether it removed it                    |
| Context              | Principal, properties and parent of the scheduling message; a fresh timestamp; the polling thread         |
| Tracing              | The stored `traceparent` links the fired `send` span to the scheduling invocation                         |
| Failures             | Existing `max-retries` and `RetryBackoffStrategy`; abandoned deadlines stay in the table for inspection  |
| Tests                | `DeadlineSchedulerStub` captures deadlines and fires due ones only when the test calls `fireDue`          |

## Public Contract

### The Port

```java
package app.dodb.smd.api.deadline;

public interface DeadlineScheduler {

    DeadlineId schedule(Command<?> command, Instant dueAt);

    DeadlineId schedule(Command<?> command, Duration delay);

    boolean cancel(DeadlineId id);
}
```

```java
public record DeadlineId(UUID value) {

    public static DeadlineId generate() {
        return new DeadlineId(UUID.randomUUID());
    }

    public DeadlineId {
        requireNonNull(value);
    }
}
```

Usage in a handler, keeping the ID on the ticket so a later command can cancel it:

```java
@CommandHandler
public UUID handle(OpenTicketCommand command, Instant timestamp) {
    var ticketId = UUID.randomUUID();
    var escalation = deadlines.schedule(new EscalateTicketCommand(ticketId), timestamp.plus(ofHours(48)));
    tickets.save(Ticket.open(ticketId, command.title(), command.description(), escalation));
    events.publish(new TicketOpenedEvent(ticketId, command.title(), command.description()));
    return ticketId;
}

@CommandHandler
public void handle(AnswerTicketCommand command) {
    var ticket = tickets.find(command.ticketId()).orElseThrow();
    ticket.escalation().ifPresent(deadlines::cancel);
    tickets.save(ticket.answer(command.answer()));
}
```

The fired command's handler re-checks the state it acts on, because a deadline can fire after the business reason went away if a cancel was missed:

```java
@CommandHandler
public void handle(EscalateTicketCommand command) {
    tickets.find(command.ticketId())
        .filter(Ticket::awaitingCustomer)
        .ifPresent(ticket -> tickets.save(ticket.escalate()));
}
```

### Decisions

**Commands only.** A deadline asks for something to be done later, which is a command. Firing goes through the normal `CommandGateway`, so interceptors, transactions, tracing, handler
discovery and metadata binding all apply unchanged, and no fourth message kind or handler annotation is needed. An event that "becomes true" in 48 hours is better modelled as a command whose
handler checks the state and publishes `TicketEscalatedEvent` if it still applies. Events can be added later as `schedule(Event, dueAt)` on the same table with a `message_kind` column; see
[Open Questions](#open-questions). `Command<R>` with any `R` is accepted; the result is logged at debug and discarded.

**A generated ID is the only identity.** `schedule` returns a `DeadlineId`, so every deadline can be cancelled. The ID identifies one scheduled deadline and is also the `MessageId` value of
the command it fires, so a fired handler and the scheduler see the same identifier. A caller that wants to cancel later stores the ID in its own state, for example on the ticket row or in a
saga's state. Rescheduling is cancel plus schedule in one transaction.

Scheduling is not idempotent: two calls schedule two deadlines. With the event store that is harmless, because a message is only redelivered after a rollback, which also rolled back the
schedule. With a non-transactional source, such as a broker consumer without XA, a redelivered message schedules a second deadline; the fired handler's state check is then what keeps the
duplicate harmless.

**Instant and Duration.** `schedule(command, Duration)` computes `timeProvider.now().plus(delay)` at the call, using the same `TimeProvider` as metadata. A negative delay fails:
`IllegalArgumentException("Deadline delay must not be negative: command=app.dodb.tickets.EscalateTicketCommand, delay=PT-1H")`. An `Instant` in the past is allowed and fires on the next poll,
which makes "recompute the due date and schedule it" safe without a special case. In at-least-once handlers, prefer the `Instant` form derived from the message timestamp so a redelivered
message schedules the same due time.

**`cancel` returns whether it removed a deadline that had not fired.** `false` means the ID had no pending deadline: it never existed, was cancelled already, or fired and committed (see
[Cancel versus Fire](#cancel-versus-fire)). Cancelling twice is safe.

**Validation happens at the call.** `DeadlineStore.schedule` serializes the command and resolves its type name immediately, so an unserializable command or a missing type mapping fails in the
handler that scheduled it, not 48 hours later.

### Module Placement

| Module                                   | Contents                                                                                                                                                    |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core/smd-api`                           | `DeadlineScheduler`, `DeadlineId`                                                                                                                           |
| `core/smd-event-store`                   | `DeadlineStore`, `DeadlineStoreConfig`, `DeadlineStorage`, `DeadlineState`, `PostgresDeadlineStorage`, `InMemoryDeadlineStorage`, serializer, schema table |
| `core/smd-test`                          | `DeadlineSchedulerStub`                                                                                                                                     |
| `framework/smd-spring-boot-starter`      | `SMDDeadlineAutoConfiguration`, `smd.deadlines.*` properties                                                                                               |
| `framework/smd-spring-boot-starter-test` | Stub replacement of the injected `DeadlineScheduler`; `SMDTestExtension` deadline methods                                                                  |

The durable implementation belongs next to the event store because it reuses `ConnectionProvider`, `TransactionProvider`, Jackson with `SMDJacksonModule`, `RetryBackoffStrategy`, the
retry configuration shape, the scheduler lifecycle and the schema file. Its package is separate so it does not reach into event-store internals. If the module name starts to mislead, both can
move to an `smd-store` module later without changing the `smd-api` port.

### Durable Implementation

```java
package app.dodb.smd.eventstore.deadline;

public class DeadlineStore implements DeadlineScheduler, AutoCloseable {

    public DeadlineStore(DeadlineStoreConfig config) { ... }

    // DeadlineScheduler methods write in the caller's transaction and work before start.

    public void start(CommandGateway commandGateway) { ... }   // begins polling and firing

    public Optional<DeadlineState> deadlineState(DeadlineId id) { ... }

    @Override
    public void close() { ... }   // stops polling; an active fire finishes
}
```

Passing the gateway to `start` instead of the constructor breaks the cycle between handlers that need a `DeadlineScheduler` and a `CommandBus` built from those handlers. `start` fails if
called twice.

```java
var deadlineStore = new DeadlineStore(DeadlineStoreConfig.withDefaults()
    .transactionProvider(transactionProvider)
    .principalProvider(principalProvider)
    .timeProvider(timeProvider)
    .deadlineStorage(new PostgresDeadlineStorage(connectionProvider))
    .deadlineSerializer(new JacksonDeadlineSerializer(mapper, new StaticCommandTypeResolver(Map.of(
        "ticket.escalate.v1", EscalateTicketCommand.class
    ))))
    .build());

var commandBus = CommandBusSpec.withDefaults()
    .commandHandlers(new PackageBasedCommandHandlerLocator(packages, objectCreatorThatInjects(deadlineStore)))
    .interceptors(new TransactionalCommandBusInterceptor(transactionProvider))
    .create();

deadlineStore.start(commandBus);
```

`DeadlineStoreConfig` is one flat builder with `withDefaults()` and `withoutDefaults()`: scheduler, initial delay, polling delay, batch size, max retries and
`RetryBackoffStrategy`. It has no gap timeout. Defaults: polling delay `5s`, batch size `100`, max retries `3`, exponential backoff `1s × 5` capped at `5m`, the same as the event store. A
deadline therefore fires up to one polling delay after it is due; this is not a sub-second timer.

Commands need a stable type name for the same reason events do, and more urgently: deadlines stay stored across deployments. Following the command/query/event symmetry rule, this adds
`CommandTypeResolver`, `ClassNameCommandTypeResolver` and `StaticCommandTypeResolver` beside the event ones rather than generalising the released `EventTypeResolver`.

The storage port is internal to the store's contract with its database, like `EventStorage`:

```java
public interface DeadlineStorage {

    void store(SerializedDeadline deadline);

    boolean delete(DeadlineId id);

    Optional<ClaimedDeadline> claimDue(Instant now);       // locked until the claiming transaction ends

    Optional<ClaimedDeadline> claim(DeadlineId id);        // for recording a failure

    Optional<DeadlineState> deadlineState(DeadlineId id);
}

public interface ClaimedDeadline {

    SerializedDeadline deadline();

    int errorCount();

    void markFired();                                       // deletes the row

    void markFailed(Exception exception, int errorCount, Instant nextAttemptAt);

    void markAbandoned(Exception exception);
}
```

`DeadlineState` is a non-locking diagnostic snapshot like `EventSequenceState`: ID, command type, due time, next attempt, status (`PENDING`, `FAILED`, `ABANDONED`), error count, latest error.

## Schema Sketch

```sql
CREATE TABLE IF NOT EXISTS smd_deadline_store
(
    deadline_id         UUID PRIMARY KEY,
    command_type        VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    due_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(32)              NOT NULL,
    error_count         INTEGER                  NOT NULL DEFAULT 0,
    last_error_message  TEXT,
    last_error_at       TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS smd_deadline_store_next_attempt
    ON smd_deadline_store (next_attempt_at)
    WHERE status <> 'ABANDONED';
```

- `deadline_id` identifies the deadline and is also the fired command's `MessageId`.
- `next_attempt_at` starts at `due_at` and moves forward with backoff, so one partial index answers "what is due now" for both first attempts and retries.
- A fired deadline is deleted. Only abandoned deadlines accumulate; see [Open Questions](#open-questions) for retention.

Statements:

```sql
-- schedule (in the caller's transaction)
INSERT INTO smd_deadline_store (deadline_id, command_type, serialized_payload, serialized_metadata,
                                due_at, next_attempt_at, status, error_count)
VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0);

-- cancel (in the caller's transaction)
DELETE FROM smd_deadline_store WHERE deadline_id = ? AND status <> 'ABANDONED';

-- claim (in the firing transaction); ? is TimeProvider.now()
SELECT * FROM smd_deadline_store
WHERE status <> 'ABANDONED' AND next_attempt_at <= ?
ORDER BY next_attempt_at
LIMIT 1
FOR UPDATE SKIP LOCKED;

-- fired (in the firing transaction); row count must be 1
DELETE FROM smd_deadline_store WHERE deadline_id = ?;
```

Should `cancel` also remove an abandoned deadline? This sketch leaves abandoned rows for the operator and makes `cancel` report only pending deadlines. That is listed as an open question.

## Transactions

| State change                       | Owning transaction                                                          |
|------------------------------------|-----------------------------------------------------------------------------|
| `schedule`, `cancel`               | The caller's, joined with `doInTransaction`; one is created if absent       |
| Claiming a due deadline            | A new firing transaction on the polling thread                              |
| The fired command's handler work   | The same firing transaction, joined by `TransactionalCommandBusInterceptor` |
| Events the handler stores          | The same firing transaction, through the event-store sink's deferred write  |
| Deleting the fired deadline        | The same firing transaction, after the command returns                      |
| Recording a failure or abandonment | A second new transaction after the firing transaction rolled back           |

**Scheduling.** `schedule` writes immediately inside `transactionProvider.doInTransaction`, not through `defer`. The event-store sink defers to commit so sequence numbers are allocated late;
deadlines have no sequence, and writing immediately surfaces serialization failures at the call. If the sender's transaction rolls back, the schedule and any cancel roll back with it.
Without a surrounding transaction the call commits before returning, like `eventStore.send` today.

**Firing.** Each deadline fires in its own new transaction: claim one due row, deserialize, send the command through the gateway, delete the row, commit. A batch of `batch-size` claims
runs per poll, one transaction each. One transaction per deadline means a failing deadline never rolls back another one, so the event store's replay-the-prefix logic is not needed.

Because the command handler joins the firing transaction, the handler's database changes, the events it stores and the deadline's removal commit or roll back together. Within one database,
a successful fire's effects are applied once.

**Partial failure.**

| Failure                                                | Outcome                                                                                                                    |
|--------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| The command handler throws                             | Firing transaction rolls back, including deadlines the handler scheduled; the failure is recorded; the deadline is retried |
| Commit fails, for example a deferred event-store write | Same as a handler failure: the store catches the exception from `doInNewTransaction`, not only from the handler           |
| Deserialization or type resolution fails               | Recorded as a failure and retried, then abandoned (see [Open Questions](#open-questions))                                  |
| The node crashes before commit                         | PostgreSQL releases the row lock; the deadline is still due and another poll fires it again                                |
| The node crashes after commit                          | Fired once; nothing to redo                                                                                                 |
| The failure record's transaction fails                 | Logged; the deadline is attempted again on the next poll without the error count incremented                               |
| The handler fires async event groups or external calls | Not rolled back; the same caveat as asynchronous delivery today                                                            |

## Delivery

**At least once.** A deadline is retried until it fires or is abandoned. Duplicates come from crashes before commit and from the window between a rollback and the failure record, where another
node can claim the row and try again. Handlers that call external systems must be idempotent.

**A stable message ID.** The `CommandMessage` is created once, when scheduling, with the `DeadlineId`'s value as its `MessageId`. Every attempt sends that same message through
`CommandGateway.send(CommandMessage)`, which keeps the ID, so handlers can use `MessageId` as an idempotency key exactly as event-store handlers use the event ID.

**Concurrent claims.** Every node polls with `FOR UPDATE SKIP LOCKED LIMIT 1`. A row locked by another node's firing transaction or by an application transaction that is cancelling it is
skipped. The lock held until commit is the claim: no `picked` flag, owner column or heartbeat is needed, because a dead node's transaction ends and releases the row. The cost is that a firing
transaction stays open for the duration of the command handler, which is the normal case for a command.

**Ordering.** There is none to rely on. A node fires due deadlines in `next_attempt_at` order, but several nodes and pool threads fire concurrently, two deadlines for one ticket or saga
can run at the same time, and a retry can run after a later deadline. Handlers that touch shared state rely on their own locking or optimistic concurrency and the retry it triggers.

### Cancel versus Fire

The firing transaction holds the row lock from claim to commit, and `cancel` is a `DELETE` of that row, so PostgreSQL serializes them:

| Interleaving                                   | Result                                                                                              |
|------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| Cancel commits before the poll claims          | The row is gone; the deadline never fires                                                           |
| Cancel's transaction is open when the poll runs | The poll skips the locked row; if cancel rolls back, a later poll fires it                         |
| The poll claimed first                         | Cancel waits; after the fire commits, cancel deletes nothing and returns `false`                    |
| The poll claimed first and the fire rolls back | Cancel then deletes the row and returns `true`; the failure record finds no row and records nothing |

So `cancel` never returns `true` for a deadline whose effects committed, and a caller that gets `false` can see the fired command's effects.

The wait is bounded by the fired command's transaction. If the cancelling transaction already holds a lock the fired handler needs, such as the ticket row, PostgreSQL detects the deadlock
and aborts one side. An aborted fire is an ordinary failure and is retried; an aborted cancel fails the user's request. Handlers that both lock an aggregate and cancel its deadline should
cancel first. This is documented, not prevented.

## Context at Fire Time

**Thread.** The store's scheduler thread runs the poll, the command dispatch and the handler, synchronously, inside the firing transaction. `close()` stops future polls, lets an active fire
finish, and shuts the scheduler down as `EventStoreSource.close()` does; interruption is restored.

**Metadata.** `schedule` builds the command message with
`metadataFactory.createScope().run(metadata -> new CommandMessage<>(new MessageId(deadlineId.value()), command, metadata), ...)`, the same scope `CommandBus.send` uses, so the stored metadata
is exactly what an immediate send would have carried:

- the principal and properties of the message being handled when `schedule` was called, or the `PrincipalProvider`'s principal outside a handler;
- the scheduling message's ID as `parentMessageId`;
- the schedule-time timestamp.

At fire time the store sends the stored message with these changes, merged with `Metadata.and`:

| Metadata              | Value at fire time                          |
|-----------------------|---------------------------------------------|
| `timestamp`           | `TimeProvider.now()` of the attempt         |
| `dueAt` property      | The due time, ISO-8601                      |
| `errorCount` property | Failures already recorded for this deadline |

Handlers read them with `@MetadataValue`. The deadline ID needs no property: it is the command's `MessageId`. As with event-store delivery metadata, nested messages inherit these properties.

The scheduling principal is restored, consistent with event-store delivery, which also handles an event as its publisher's principal however late it runs. Lineage is the API: the escalation
is attributed to the action that caused it. This matters for authorization: a fired command whose handler checks the principal's permissions can fail if the user lost them, and then retries
and is abandoned. See [Open Questions](#open-questions).

**Tracing.** The stored metadata contains the `traceparent` of the invocation that scheduled the deadline, because a handler's message carries its invocation's trace context. When the fired
`send <command>` span starts on the polling thread there is no current span, so the existing `MessageTracer` rule makes the carried scheduling invocation its parent and its link. The
deadline's handling therefore joins the trace of the request that scheduled it, as event-store handling joins the trace of its publication. No change to `smd-opentelemetry` is needed and
`smd-event-store` gains no OpenTelemetry dependency.

The drawback is trace length: a 48-hour deadline produces a 48-hour trace, which some backends display or retain poorly. The alternative is a deadline interceptor port that
`MessageTracing` implements to open a `process <command>` consumer span as a new trace root with a link to the scheduling invocation; the command's `send` would then be its child. That adds an
interceptor type and a root-span rule, so it is an [open question](#open-questions) rather than part of the first slice.

## Failure Handling

The store reuses the event store's concepts and configuration shape:

- `max-retries` counts attempts after the first; with the default `3`, a deadline is abandoned after four failed attempts.
- After failure `n`, `next_attempt_at = now + retryBackoffStrategy.calculateDelay(n - 1)`, with the same `FIXED`, `LINEAR` and `EXPONENTIAL` strategies.
- Recording a failure claims the row by `deadline_id` with `SKIP LOCKED`. If the row is gone (cancelled) or locked, nothing is recorded.
- An abandoned deadline keeps its row with status `ABANDONED` and the latest error, and is never claimed again. It is logged once at error with deadline ID, command type and attempts.
- A failed attempt is logged at warn with deadline ID, command type and `retry=n/max`.

There is no subject sequencing to block: a failing deadline delays only itself.

## Spring Boot Wiring

```yaml
smd:
  deadlines:
    enabled: true
    scheduling:
      polling-delay: 5s
    processing:
      max-retries: 3
      batch-size: 100
      retry-backoff:
        strategy: EXPONENTIAL
```

- `SMDDeadlineAutoConfiguration` is conditional on `smd.deadlines.enabled=true`, independent of `smd.event-store.enabled`. The `ConnectionProvider` bean moves to a shared JDBC
  configuration both use.
- It declares `DeadlineStorage`, `CommandTypeResolver`, `DeadlineSerializer` (Jackson 3 with `SMDJacksonModule`), `DeadlineStoreConfig` and `DeadlineStore` (`destroyMethod = "close"`),
  each `@ConditionalOnMissingBean`, and exposes it as the `DeadlineScheduler`.
- A `SmartLifecycle` bean calls `deadlineStore.start(commandGateway)` after the context is refreshed, which also resolves the handler-gateway cycle. `smd.deadlines.scheduling.enabled=false`
  leaves it out, so an instance can schedule without firing.
- With deadlines disabled there is no `DeadlineScheduler` bean, so a handler that injects one fails at startup rather than silently not scheduling.
- The `smd_deadline_store` table is in a separate `deadline-store-schema.sql` and, like the event-store schema, is not applied automatically.
- `@EnableSMDStubs` replaces an injected `DeadlineScheduler` with the test-scoped stub.

## Testing

### Framework-Independent Stub

```java
public class DeadlineSchedulerStub implements DeadlineScheduler {

    public DeadlineSchedulerStub(TimeProvider timeProvider) { ... }

    public List<ScheduledDeadline> getDeadlines() { ... }           // pending, immutable, ordered by due time then schedule order

    public List<ScheduledDeadline> fireDue(CommandGateway gateway) { ... }   // sends due commands in order, removes them, returns the fired ones

    public void reset() { ... }
}

public record ScheduledDeadline(DeadlineId deadlineId, Instant dueAt, Command<?> command) {
}
```

It follows the rules for `smd-test`: captured deadlines are an ordered snapshot, nothing fires implicitly, and `reset()` clears them. Cancel behaves as in the real store. The stub generates
IDs randomly, so tests compare the ID a handler returned or stored rather than a fixed value. `fireDue` sends on the test thread, so a test advances the `TimeProviderStub` and then fires
explicitly, with no sleeps. Deadlines scheduled by the commands that `fireDue` sends wait for the next `fireDue` call, so one call fires one round.

Direct handler test:

```java
private final TimeProviderStub time = new TimeProviderStub(new SystemTimeProvider());
private final DeadlineSchedulerStub deadlines = new DeadlineSchedulerStub(time);
private final TicketCommandHandler handler = new TicketCommandHandler(tickets, events, deadlines);

@Test
void handle_openTicketCommand_schedulesEscalationIn48Hours() {
    var openedAt = Instant.parse("2026-01-01T00:00:00Z");

    var ticketId = handler.handle(new OpenTicketCommand("Cannot sign in", "Password reset does not arrive"), openedAt);

    var escalation = tickets.find(ticketId).orElseThrow().escalation().orElseThrow();
    assertThat(deadlines.getDeadlines()).containsExactly(
        new ScheduledDeadline(escalation, openedAt.plus(ofHours(48)), new EscalateTicketCommand(ticketId))
    );
}
```

Spring flow test, through real handlers:

```java
smd.stubTimestamp(openedAt);
var ticketId = smd.send(new OpenTicketCommand("Cannot sign in", "Password reset does not arrive"));

smd.stubTimestamp(openedAt.plus(ofHours(48)));
smd.fireDueDeadlines();

assertThat(tickets.find(ticketId)).get().extracting(Ticket::escalated).isEqualTo(true);
```

`SMDTestExtension` gains `getDeadlines()` and `fireDueDeadlines()`; the stub's state is test-scoped and reset like the others. A small `TimeProviderStub.advance(Duration)` would make the
second step read better but is not required.

### Store Tests

- `DeadlineStore` with `InMemoryDeadlineStorage` and `NoOpTransactionProvider` for unit tests of validation, cancel, retry, backoff and abandonment. The store reads time from
  `TimeProvider` and the poll can be triggered directly in a package-private test seam, so these tests need no scheduler or sleeps. The event store reads `Instant.now()` for backoff and
  gap timeouts today; the deadline store must not copy that.
- PostgreSQL integration tests with Testcontainers for the claims that the in-memory storage cannot prove: two stores firing one deadline once; a cancel that blocks while a fire is held in
  its handler by a latch and then returns `false`; a fire that rolls back and lets the blocked cancel return `true`; rollback of a schedule with the sender's transaction; and a crash
  simulated by rolling back after the handler ran.

The in-memory storage is for tests and demos only: no transactions, one JVM, nothing survives a restart. It is never a default.

## Prior Art

| Library                | Borrow                                                                                                                                                     | Avoid                                                                                                                                                                     |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Axon `DeadlineManager` | A returned deadline ID for cancelling; metadata propagation; test fixtures that advance time                                                               | A separate deadline message kind routed back into the scheduling aggregate or saga by `@DeadlineHandler`; an in-memory `SimpleDeadlineManager` that loses deadlines on restart |
| db-scheduler           | `SELECT ... FOR UPDATE SKIP LOCKED` polling; a next execution time column; failure handlers with backoff and a maximum; scheduling with the application's `DataSource` and transaction | `picked`/`picked_by` plus heartbeats and dead-execution detection, needed because it runs tasks outside the claiming transaction; SMD keeps the claim and the work in one transaction instead |
| JobRunr                | Explicit job states and retries with backoff by default                                                                                                    | Jobs captured as serialized lambdas or method calls, which break on refactoring and bypass SMD's typed messages, interceptors and metadata                               |
| Quartz                 | Misfire policy as a named decision: SMD fires overdue deadlines as soon as possible                                                                        | About a dozen tables, a cluster-wide lock row, untyped `JobDataMap`, and a separate job and trigger model with its own thread pool and transaction handling              |

Axon (name within a scope, cancel all within a scope) and db-scheduler (task name plus instance as the primary key) both offer domain keys; that is the part deferred below.

## Deferred: Keys and Scopes

A domain key would let a later message cancel or reschedule a deadline without having stored its ID, and a scope would let a saga cancel all its deadlines in one call. They are left out
until a use case needs them: storing the ID covers both, at the cost of a column in the caller's state. They can be added without breaking this port:

- overloads `schedule(command, dueAt, key)` with replace-on-schedule, `cancel(key)` and `cancelAll(scope)`, plus `DeadlineKey(DeadlineScope scope, String name)` records;
- nullable `deadline_scope` and `deadline_name` columns with a plain `UNIQUE (deadline_scope, deadline_name)`, which PostgreSQL's default `NULL` handling keeps from colliding for
  unkeyed rows, and an `INSERT ... ON CONFLICT DO UPDATE` for replacement.

## Rejected Alternatives

- **Scheduled events.** See [Decisions](#decisions). Deferred, not ruled out.
- **Scheduling through `CommandGateway`.** New methods on a released interface would break every gateway implementation and stub and force a scheduler into the core `CommandBus`; a magic
  metadata property on `send` would return `null` for `R` and make every interceptor guess whether it is dispatching.
- **A `@DeadlineHandler` message kind routed by scope.** Requires serializing a scope descriptor and resolving the target instance, duplicating handler discovery. A command routed by the
  existing bus does the same job.
- **Storing deadlines as future events in `smd_event_store`.** The token advances only across contiguous processed positions, so a future event would block every processing group or need
  a second gap mechanism.
- **An in-JVM `ScheduledExecutorService` per deadline.** Lost on restart, not shared by a cluster, not transactional.
- **Wrapping Quartz, db-scheduler or JobRunr as the implementation.** Adds a dependency and a second scheduling model to the core. The port allows an adapter outside SMD.
- **Returning `void` from `schedule`.** Leaves a deadline that nothing can cancel.
- **A claim flag with heartbeats.** Would allow work outside the claiming transaction and then needs dead-execution recovery. Not needed while fired work is a command in a transaction.
- **Batching several deadlines into one firing transaction.** One failure would roll back the others and bring back prefix replay.
- **Firing automatically when `TimeProviderStub` changes.** Hides when and on which thread commands run in a test.
- **The database clock (`now()`) for due checks.** Removes clock skew between nodes but makes due checks untestable with `TimeProviderStub`.

## Open Questions

1. **Events as deadline payloads.** Is there a use case that a command with a state check does not cover? If so, add a `message_kind` column and `schedule(Event, dueAt)`.
2. **Principal at fire time.** Keep the scheduling principal (proposed), or let the store be configured with a system principal for fired deadlines while keeping the scheduler in the
   properties?
3. **A new trace root for fired deadlines.** Worth a deadline interceptor port that `MessageTracing` implements? This also gives applications a hook around firing.
4. **Deserialization failures.** Retry them like other failures, as the event store does, or abandon immediately because they are not transient?
5. **Cancelling abandoned deadlines.** Should `cancel` remove abandoned rows too, and is a retention or requeue operation needed, or are diagnostics enough?
6. **Explicit metadata.** Should `schedule` accept a `CommandMessage`, like the gateways do, for callers that supply metadata?
7. **Concurrent deadlines for one saga.** Should firing be serialized per saga or aggregate, so a saga instance never sees two of its deadlines concurrently? That needs a correlation column,
   which ties it to [keys and scopes](#deferred-keys-and-scopes).
8. **Clock skew.** The application clock is used for due checks. Is documenting that a node with a fast clock fires early enough?
9. **Module name.** Keep the durable store in `smd-event-store`, or introduce an `smd-store` module now?

## Implementation Slices

1. Port and stub: `DeadlineScheduler` and `DeadlineId` in `smd-api`; `DeadlineSchedulerStub` in `smd-test`.
2. `DeadlineStore` with `InMemoryDeadlineStorage`, command type resolvers and the serializer, with unit tests for metadata, retry and abandonment.
3. `PostgresDeadlineStorage`, schema and Testcontainers tests for claims, cancel-versus-fire and rollback.
4. Spring autoconfiguration, properties, stub replacement and `SMDTestExtension` methods, plus user documentation.

All four slices are implemented. `DeadlineIntegrationTest` in the Spring starter covers the PostgreSQL doubts directly: a cancel issued while a fire holds the row lock waits (observed in
`pg_stat_activity`) and returns `false` after the fire commits, and two stores polling one table fire each of 30 deadlines exactly once.
