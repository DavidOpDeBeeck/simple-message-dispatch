# Ticket Service

This Spring Boot application demonstrates SMD with one event-sourced support-ticket aggregate and a complete lifecycle:

```text
OpenTicket -> AssignTicket -> ResolveTicket
```

It uses Java 25, Spring Boot 4, PostgreSQL 18, Flyway, and JDBC. The application shows commands, queries, events, message metadata and lineage, three event-delivery modes, persisted projections, and
the PostgreSQL event store in one runnable example.

## Message flow

HTTP requests enter through `TicketController`, which sends commands and queries through the narrow SMD gateways. `Ticket` is an `@Aggregate`: it has no table of its own. For each command, SMD
loads the ticket's event stream from the event store, replays it through the `@EventSourcingHandler` methods, and calls the aggregate's `@CommandHandler`. The events the handler applies are appended
to the stream with the next aggregate version and published to the processing groups.

```text
HTTP -> CommandGateway -> Ticket (replayed from its stream)
                                      |
                                      `-> applied events -> smd_event_store (aggregate_type = ticket, aggregate_id = ticket ID, aggregate_version = n)
                                            |-> ticket-view
                                            |-> notifications
                                            `-> ticket-activity
```

`OpenTicketCommand` has no `@TargetAggregateId`, so it starts a new stream. `AssignTicketCommand` and `ResolveTicketCommand` target an existing ticket: an unknown ticket fails with
`AggregateNotFoundException` (404), and a concurrent change of the same version fails with `ConcurrentAggregateChangeException` (409).

The controller adds the required `X-Customer-Id` header to message metadata. Nested events inherit that property and receive a parent message ID. `MessageLoggingInterceptor` logs the message type,
message ID, parent ID, customer ID, and duration, while the durable activity projection stores the customer ID with each event.

## Event delivery

[`DrivenAdapterConfiguration`](src/main/java/app/dodb/smd/ticket/drivenadapter/DrivenAdapterConfiguration.java) assigns a different delivery policy to each processing group:

| Processing group  | Delivery               | Observable behavior                                                                                   |
|-------------------|------------------------|-------------------------------------------------------------------------------------------------------|
| `ticket-view`     | Synchronous            | Updates the queryable ticket view on the publishing thread before the command returns                 |
| `notifications`   | Async-await            | Logs notifications on virtual threads and waits for every matching handler                            |
| `ticket-activity` | PostgreSQL event store | Persists events in the command transaction, then builds the ordered activity timeline through polling |

The appended events and the synchronous ticket view are committed together with the command. An invalid state transition rolls them back. Activity processing uses the event message ID as
an idempotency key, and the explicit event-type mapping keeps stored names stable across Java class changes.

The ticket view is therefore available immediately after a successful command. Activity is eventual and can briefly lag behind the command response; the local configuration polls the event store every
100 ms.

## Project layout

| Package          | Responsibility                                                     |
|------------------|--------------------------------------------------------------------|
| `domain`         | The event-sourced `Ticket` aggregate and its lifecycle transitions |
| `drivingport`    | Commands, queries, events, and API transfer objects                |
| `usecase`        | Query handlers                                                     |
| `drivenport`     | Repository contracts used by the handlers                          |
| `drivenadapter`  | JDBC repositories, projections, and processing-group configuration |
| `drivingadapter` | REST endpoints                                                     |
| `infrastructure` | Cross-cutting message logging                                      |

This keeps the domain and use cases independent of the REST and JDBC adapters while leaving the SMD wiring explicit.

## Run the application

You need Java 25 and Docker with the Compose plugin.

The example uses event-sourced aggregates, which are not released yet. Publish SMD from the repository root before running or testing the example:

```bash
./gradlew publishToMavenLocal
```

From this directory, start PostgreSQL and the application:

```bash
docker compose up -d
./gradlew bootRun
```

## Complete API flow

Every endpoint requires `X-Customer-Id`. IntelliJ users can run the same lifecycle from [`tickets.http`](tickets.http).

Open a ticket:

```bash
curl --fail-with-body -sS \
  -X POST http://localhost:8080/tickets \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: customer-42' \
  -d '{"title":"Cannot sign in","description":"Password reset does not arrive"}'
```

The response is the immediately projected ticket and includes its generated `ticketId`. Store that value, then assign and resolve the ticket:

```bash
TICKET_ID='<ticket UUID>'

curl --fail-with-body -sS \
  -X POST "http://localhost:8080/tickets/${TICKET_ID}/assign" \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: customer-42' \
  -d '{"assignee":"agent-7"}'

curl --fail-with-body -sS \
  -X POST "http://localhost:8080/tickets/${TICKET_ID}/resolve" \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: customer-42' \
  -d '{"resolution":"Reset mail was unblocked"}'
```

Read the current ticket view:

```bash
curl --fail-with-body -sS \
  "http://localhost:8080/tickets/${TICKET_ID}" \
  -H 'X-Customer-Id: customer-42'
```

Read the eventual activity projection:

```bash
curl --fail-with-body -sS \
  "http://localhost:8080/tickets/${TICKET_ID}/activity" \
  -H 'X-Customer-Id: customer-42'
```

After the complete lifecycle, activity contains `TicketOpened`, `TicketAssigned`, and `TicketResolved` entries in order.

## Tests

Run every example check from this directory:

```bash
./gradlew check
```

The verification tasks can also be run separately:

| Task              | Coverage                                                                                               |
|-------------------|--------------------------------------------------------------------------------------------------------|
| `test`            | Ticket aggregate against an in-memory event store; query handlers with `@EnableSMDStubs`               |
| `integrationTest` | JDBC adapters and all three processing groups against PostgreSQL 18 with Testcontainers                |
| `acceptanceTest`  | Complete REST lifecycle, immediate ticket view, and metadata-backed activity                           |
