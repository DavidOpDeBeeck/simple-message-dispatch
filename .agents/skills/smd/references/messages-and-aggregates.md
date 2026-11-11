# SMD: Messages and Event-Sourced Aggregates

## Contents

- [6. Messages](#6-messages)
- [7. Event-Sourced Aggregates](#7-event-sourced-aggregates)

## 6. Messages

```java
public record OpenTicketCommand(String title, String description) implements Command<UUID> {

    public OpenTicketCommand {
        title = Require.text(title, "title", "OpenTicketCommand");
        description = Require.text(description, "description", "OpenTicketCommand");
    }
}
```

```java
public record AssignTicketCommand(@TargetAggregateId UUID ticketId, String assignee) implements Command<UUID> {

    public AssignTicketCommand {
        requireNonNull(ticketId);
        assignee = Require.text(assignee, "assignee", "AssignTicketCommand");
    }
}
```

```java
public record GetTicketQuery(UUID ticketId) implements Query<Optional<TicketDTO>> {

    public GetTicketQuery {
        requireNonNull(ticketId);
    }
}
```

```java
public sealed interface TicketEvent extends Event permits TicketOpenedEvent, TicketAssignedEvent, TicketResolvedEvent {

    UUID ticketId();
}
```

```java
public record TicketOpenedEvent(@SubjectId("ticket") UUID ticketId, String title, String description) implements TicketEvent {

    public TicketOpenedEvent {
        requireNonNull(ticketId);
        requireNonNull(title);
        requireNonNull(description);
    }
}
```

- `Require.text` is a small helper of your own in `drivingport` that rejects blank strings with a message naming the field and the message type.
- A sealed interface per subject lets one handler take every event of that subject, such as `on(TicketEvent event)`. Event handlers also receive subtypes of their
  parameter type.
- `@SubjectId("<type>")` on the ID gives the event a subject: durable media keep the events of one subject in order. Use the aggregate's `@Aggregate` name as the type
  for events about that aggregate. Aggregates set the subject themselves, but declaring it keeps the subject when the event is published outside the aggregate, for example
  in a test. A declared subject that differs from the aggregate fails publication.
- Use stable domain values for subject IDs. On the event store, the events without a subject share one sequence per group, so one failing subjectless event holds
  back every other subjectless event of that group. Give events a subject whenever they are about something.

## 7. Event-Sourced Aggregates

```java
@Aggregate("ticket")
public class Ticket {

    @AggregateId
    private UUID ticketId;
    private TicketStatus status;

    private Ticket() {
    }

    @AggregateCommandHandler
    public Ticket(OpenTicketCommand command, AggregateEvents<TicketEvent> events) {
        events.apply(new TicketOpenedEvent(UUID.randomUUID(), command.title(), command.description()));
    }

    @AggregateCommandHandler
    public UUID handle(AssignTicketCommand command, AggregateEvents<TicketEvent> events) {
        if (status != TicketStatus.OPEN) {
            throw new InvalidTicketTransitionException(ticketId, status, "assign", TicketStatus.OPEN);
        }
        events.apply(new TicketAssignedEvent(ticketId, command.assignee()));
        return ticketId;
    }

    @EventSourcingHandler
    void on(TicketOpenedEvent event) {
        ticketId = event.ticketId();
        status = TicketStatus.OPEN;
    }

    @EventSourcingHandler
    void on(TicketAssignedEvent event) {
        status = TicketStatus.ASSIGNED;
    }
}
```

How it runs: for each command, SMD loads the aggregate's events, replays them through the `@EventSourcingHandler`s, calls the `@AggregateCommandHandler`, and publishes the
applied events with the next versions, all in the command's transaction. The event store stores them; other groups receive them like any other event.

Rules:

- `@Aggregate("<name>")`: the name is stored with every event and is the subject type of its events. Never change it.
- **Creating** command: an `@AggregateCommandHandler` constructor. It applies at least one event, an `@EventSourcingHandler` assigns the `@AggregateId` from it, the command
  has no `@TargetAggregateId`, and its result type is the `@AggregateId` type.
- **Changing** command: an `@AggregateCommandHandler` method on a command with a `@TargetAggregateId` of the `@AggregateId` type. An unknown ID fails with
  `AggregateNotFoundException`.
- Decide in command handlers, change state only in `@EventSourcingHandler`s. Replay runs only the event sourcing handlers, so state set elsewhere is lost.
- `@EventSourcingHandler`s have no side effects and never throw for valid events: they run on every command and must accept every event ever stored.
- Validate and throw before applying events. A thrown exception appends nothing.
- Keep a private no-argument constructor for replay.
- Use `@AggregateCommandHandler`, never `@CommandHandler`, inside an aggregate.
- Command handlers may take collaborators as extra parameters, resolved as Spring beans, such as a driven port that checks something. Never write through a collaborator:
  the aggregate's events are its only output.
- A command sees the changes made to its aggregate earlier in the same transaction, such as by a `sync()` policy reacting to the creation event.
- A concurrent change by another transaction fails with `ConcurrentAggregateChangeException` and rolls back. SMD does not retry it; map it to `409 Conflict` or send the
  command again.
- The ID can come from the command instead of `UUID.randomUUID()` when a retried caller must create the aggregate only once, such as a policy on a durable dispatcher.
  A second creation with the same ID fails with `ConcurrentAggregateChangeException` at commit, so the caller checks first whether the aggregate exists, for example
  in a read model, and skips the command. Otherwise a durable delivery retries the failing creation forever.
- An event without a matching `@EventSourcingHandler` is ignored during replay. Add one for every event that changes state the aggregate decides on.
- To change an aggregate outside a command handler, use `AggregateRepository`, obtained from `EventSourcedAggregates.repository(Ticket.class)`. Prefer sending a command.
