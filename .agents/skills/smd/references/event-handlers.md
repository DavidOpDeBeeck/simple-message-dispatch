# SMD: Event Handlers and Processing Groups

## Contents

- [8.1 Handler parameters](#81-handler-parameters)
- [8.2 Templates](#82-templates)
- [8.3 Idempotency](#83-idempotency)

## 8. Event Handlers and Processing Groups

### 8.1 Handler parameters

Every handler takes exactly one message parameter and may add, at most once each:

| Parameter                         | Value                                                                       |
|-----------------------------------|-----------------------------------------------------------------------------|
| `MessageId`                       | This message's ID. Use it as an idempotency key for events                  |
| `Metadata`                        | All metadata                                                                |
| `Principal`                       | Who acted                                                                   |
| `Instant`                         | When the message was created                                                |
| `@MetadataValue("key") String`    | A property or transient property, `null` when absent. Several are allowed   |

Every `String` parameter must carry `@MetadataValue`. Aggregate command handlers also take `AggregateEvents<E>` and collaborators.

### 8.2 Templates

A synchronous projection in `drivenadapter.ticketview`. It is both the event handler and the driven-port implementation that query handlers read through:

```java
@Repository
@ProcessingGroup(DrivenAdapterConfiguration.TICKET_VIEW)
public class JdbcTicketViewRepository implements TicketViewRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventHandler
    public void on(TicketOpenedEvent event, Instant timestamp) {
        jdbc.update("""
                INSERT INTO ticket_view (ticket_id, title, description, status, opened_at)
                VALUES (?, ?, ?, 'OPEN', ?)
                """,
            event.ticketId(), event.title(), event.description(), Timestamp.from(timestamp));
    }

    @Override
    public Optional<TicketDTO> find(UUID ticketId) {
        // SELECT ... mapped to TicketDTO
    }
}
```

The query handler in `usecase`:

```java
@Component
public class TicketQueryHandler {

    private final TicketViewRepository views;

    public TicketQueryHandler(TicketViewRepository views) {
        this.views = views;
    }

    @QueryHandler
    public Optional<TicketDTO> handle(GetTicketQuery query) {
        return views.find(query.ticketId());
    }
}
```

An external side effect on a durable dispatcher, in `drivenadapter.customerwebhooks`:

```java
@Component
@ProcessingGroup(DrivenAdapterConfiguration.CUSTOMER_WEBHOOKS)
public class CustomerWebhookEventHandler {

    private final CustomerWebhookSender webhookSender;

    public CustomerWebhookEventHandler(CustomerWebhookSender webhookSender) {
        this.webhookSender = webhookSender;
    }

    @EventHandler
    public void on(TicketEvent event, MessageId eventId, @MetadataValue("customerId") String customerId) {
        webhookSender.send(new CustomerWebhook(eventId, customerId, event));
    }
}
```

A policy in `usecase` that sends a follow-up command:

```java
@Component
@ProcessingGroup(UseCaseConfiguration.TICKET_ESCALATION)
public class TicketEscalationPolicy {

    private final CommandGateway commandGateway;

    public TicketEscalationPolicy(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @EventHandler
    public void on(TicketOpenedEvent event) {
        if (event.title().contains("outage")) {
            commandGateway.send(new EscalateTicketCommand(event.ticketId()));
        }
    }
}
```

The command the policy sends inherits the principal and properties of the event and records it as its parent. To act as someone else, such as a system user, change
the principal: `commandGateway.send(command, metadata -> metadata.withPrincipal(systemPrincipal))`.

### 8.3 Idempotency

A durable group can receive an event again after a crash, a rollback of a later event in the same batch, or a redelivering broker. Make every durable handler safe to
repeat:

- **Database writes**: key them on the event's `MessageId` or on a natural key, and keep them in the delivery's transaction:
  `INSERT ... ON CONFLICT (event_id) DO NOTHING`.
- **External calls**: pass the event's `MessageId` as the idempotency or delivery key, so the receiver can drop duplicates. A network failure can leave the outcome
  unknown; the retry must be safe.
- **Follow-up commands**: derive the target or created ID from the event, for example `UUID.nameUUIDFromBytes(("escalation:" + eventId.value()).getBytes(UTF_8))`, and
  check the current state before sending, so a repeat sends nothing. A repeated command that fails, such as a second creation, is retried forever on a durable
  dispatcher.
- Let failures propagate. The durable medium retries; do not add your own retry loop.
