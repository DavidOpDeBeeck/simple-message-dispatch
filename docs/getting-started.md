# Getting Started with Spring Boot

This guide adds SMD to an existing Spring Boot 4 application and builds one runnable ticket flow. You will send a command, read the result with a query, and handle the published event. See
[Core API](core-api.md) if you are not using Spring.

SMD requires Java 25. Each public type below belongs in its own file.

## 1. Add the Dependency

Add the SMD starter and Spring MVC to `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-spring-boot-starter:0.0.11")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

The starter already exposes the core API and event-store module. Do not add them separately unless you are managing modules manually.

## 2. Define the Messages

A command requests a state change, a query reads state, and an event announces something that happened.

```java
public record OpenTicketCommand(String title, String description) implements Command<UUID> {

    public OpenTicketCommand {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("OpenTicketCommand title must contain text");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("OpenTicketCommand description must contain text");
        }
    }
}
```

```java
public record GetTicketQuery(UUID ticketId) implements Query<Optional<TicketDTO>> {

    public GetTicketQuery {
        requireNonNull(ticketId, "GetTicketQuery ticketId must not be null");
    }
}
```

```java
public record TicketDTO(UUID ticketId, String title, String description) {
}
```

```java
public record TicketOpenedEvent(@SubjectId UUID ticketId, String title, String description) implements Event {

    public TicketOpenedEvent {
        requireNonNull(ticketId, "TicketOpenedEvent ticketId must not be null");
        requireNonNull(title, "TicketOpenedEvent title must not be null");
        requireNonNull(description, "TicketOpenedEvent description must not be null");
    }
}
```

The subject ID is used if this event is later routed through the event store. Events for the same ticket then retain their relative order.

## 3. Add the Ticket State and Repository

Keep storage behind a small application contract. This guide uses an in-memory adapter so the first flow can run without a database.

```java
public class Ticket {

    private final UUID ticketId;
    private final String title;
    private final String description;
    private final List<TicketOpenedEvent> uncommittedEvents = new ArrayList<>();

    public Ticket(UUID ticketId, String title, String description) {
        this.ticketId = requireNonNull(ticketId, "Ticket ticketId must not be null");
        this.title = requireNonNull(title, "Ticket title must not be null");
        this.description = requireNonNull(description, "Ticket description must not be null");
        uncommittedEvents.add(new TicketOpenedEvent(ticketId, title, description));
    }

    public UUID ticketId() {
        return ticketId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<TicketOpenedEvent> consumeEvents() {
        var events = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return events;
    }
}
```

```java
public interface TicketRepository {

    void save(Ticket ticket);

    Optional<Ticket> find(UUID ticketId);
}
```

```java
@Repository
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();

    @Override
    public void save(Ticket ticket) {
        tickets.put(ticket.ticketId(), ticket);
    }

    @Override
    public Optional<Ticket> find(UUID ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }
}
```

## 4. Add Command and Query Handlers

Handlers are Spring beans with public annotated methods. Keep command and query behavior in separate handlers.

```java
@Component
public class TicketCommandHandler {

    private final TicketRepository tickets;
    private final EventPublisher events;

    public TicketCommandHandler(TicketRepository tickets, EventPublisher events) {
        this.tickets = tickets;
        this.events = events;
    }

    @CommandHandler
    public UUID handle(OpenTicketCommand command) {
        var ticket = new Ticket(UUID.randomUUID(), command.title(), command.description());

        tickets.save(ticket);
        ticket.consumeEvents().forEach(events::publish);

        return ticket.ticketId();
    }
}
```

```java
@Component
public class TicketQueryHandler {

    private final TicketRepository tickets;

    public TicketQueryHandler(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @QueryHandler
    public Optional<TicketDTO> handle(GetTicketQuery query) {
        return tickets.find(query.ticketId())
            .map(ticket -> new TicketDTO(ticket.ticketId(), ticket.title(), ticket.description()));
    }
}
```

SMD requires exactly one handler for each command or query type. The handler return type must match the message's generic result type.

## 5. Add an Event Handler

Every event handler belongs to a processing group. An event can have matching handlers in several groups.

```java
@Component
@ProcessingGroup("notifications")
public class TicketNotificationEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketNotificationEventHandler.class);

    @EventHandler
    public void handle(TicketOpenedEvent event) {
        LOGGER.info("Ticket opened notification: ticketId={}, title={}", event.ticketId(), event.title());
    }
}
```

Without custom configuration, Spring Boot delivers events synchronously. This handler finishes before `publish` returns, and a failure is propagated to the publisher.

## 6. Enable SMD

Add `@EnableSMD` to the application and point it at the package containing the handlers:

```java
@EnableSMD(packages = "com.example.tickets")
@SpringBootApplication
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
```

Handler classes must also be discoverable Spring beans, such as `@Component`, `@Repository`, or explicitly declared `@Bean` instances.

## 7. Send Commands and Queries

Entry points depend on the narrow gateway needed for each message type.

```java
@RestController
@RequestMapping("/tickets")
public class TicketController {

    private final CommandGateway commands;
    private final QueryGateway queries;

    public TicketController(CommandGateway commands, QueryGateway queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    public TicketDTO open(@Valid @RequestBody OpenTicketRequest request) {
        var ticketId = commands.send(new OpenTicketCommand(request.title(), request.description()));
        return queries.send(new GetTicketQuery(ticketId)).orElseThrow();
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDTO> get(@PathVariable UUID ticketId) {
        return ResponseEntity.of(queries.send(new GetTicketQuery(ticketId)));
    }

    public record OpenTicketRequest(@NotBlank String title, @NotBlank String description) {
    }
}
```

## 8. Run and Verify the Flow

Start the application:

```bash
./gradlew bootRun
```

Open a ticket:

```bash
curl --fail-with-body -sS \
  -X POST http://localhost:8080/tickets \
  -H 'Content-Type: application/json' \
  -d '{"title":"Cannot sign in","description":"Password reset does not arrive"}'
```

The response contains the generated `ticketId`, title, and description. The application also logs `Ticket opened notification`, proving that the event handler completed before the response was
returned.

## Important Handler Rules

- Handler methods must be public.
- A handler has exactly one command, query, or event payload parameter.
- Command and query return types must match their message's generic result type.
- Event handlers return `void` and require `@ProcessingGroup` on the method or class.
- Handler classes must be discoverable Spring beans.

Next, add a focused handler-flow test with [Testing](testing.md), choose delivery and transaction behavior in [Spring Boot](spring-boot.md), or make events durable with [Event Store](event-store.md).
The runnable [ticket service](../examples/ticket-service/README.md) demonstrates the complete lifecycle with JDBC, projections, metadata, and three delivery modes.
