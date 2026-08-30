# Testing

SMD provides Spring test-scope support for complete handler flows and framework-independent stubs for direct unit tests.

## Test a Spring Handler Flow

Add the Spring test module alongside Spring Boot's test starter:

```kotlin
dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.10")
}
```

Keep the test context focused on the handlers and small fakes for their driven dependencies. For the ticket types from [Getting Started](getting-started.md), use this configuration:

```java
@EnableSMD
@Configuration
@ComponentScan
public class TicketUseCaseTestConfiguration {
}
```

The component scan includes the real handlers and a test-owned repository fake in a subpackage:

```java
@Component
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<UUID, Ticket> tickets = new HashMap<>();

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

`@EnableSMDStubs` replaces gateways injected into handlers and the event publisher with test-scoped stubs. `SMDTestExtension.send(...)` still dispatches the top-level message through its real
discovered handler:

```java
@EnableSMDStubs
@SpringBootTest(classes = TicketUseCaseTestConfiguration.class, webEnvironment = NONE)
class TicketCommandHandlerTest {

    @Autowired
    private SMDTestExtension smd;

    @Autowired
    private InMemoryTicketRepository tickets;

    @Test
    void send_openTicketCommand_createsTicketAndPublishesOpenedEvent() {
        var command = new OpenTicketCommand("Cannot sign in", "Password reset does not arrive");

        var ticketId = smd.send(command);

        assertThat(tickets.find(ticketId)).get()
            .extracting(Ticket::title, Ticket::description)
            .containsExactly(command.title(), command.description());
        assertThat(smd.getEvents()).containsExactly(
            new TicketOpenedEvent(ticketId, command.title(), command.description())
        );
    }
}
```

Use the extension to control nested gateway calls and message context:

```java
smd.stubCommand(command, response)
    .stubQuery(query, response)
    .stubPrincipal(principal)
    .stubTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
```

The extension exposes:

- `send(command)`, `send(query)`, and `send(event)` for top-level dispatch
- `stubCommand(...)` and `stubQuery(...)` for calls made through injected stub gateways
- `stubPrincipal(...)` and `stubTimestamp(...)` for deterministic metadata
- `getEvents()` for events sent through the injected publisher stub

Stub state is isolated per test and reset automatically by `SMDTestScopeLifecycleExtension`. State owned by application fakes or repositories remains the application's responsibility.

## Test a Handler Directly

Use `smd-test` when package discovery, interceptors, and the Spring context are irrelevant:

```kotlin
dependencies {
    testImplementation("app.dodb:smd-test:0.0.10")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
}
```

Construct the handler with small fakes and call it as a normal Java object:

```java
class TicketCommandHandlerDirectTest {

    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final EventPublisherStub events = new EventPublisherStub();
    private final TicketCommandHandler handler = new TicketCommandHandler(tickets, events);

    @Test
    void handle_openTicketCommand_createsTicketAndPublishesOpenedEvent() {
        var command = new OpenTicketCommand("Cannot sign in", "Password reset does not arrive");

        var ticketId = handler.handle(command);

        assertThat(tickets.find(ticketId)).isPresent();
        assertThat(events.getEvents()).containsExactly(
            new TicketOpenedEvent(ticketId, command.title(), command.description())
        );
    }
}
```

Available framework-independent utilities include:

- `CommandGatewayStub`
- `QueryGatewayStub`
- `EventPublisherStub`
- `EventChannelListenerStub`
- `PrincipalProviderStub`
- `TimeProviderStub`
- `NoOpTransactionProvider`

Command and query stubs match messages by `equals`, so records make convenient test messages:

```java
var queries = new QueryGatewayStub();
var query = new GetTicketQuery(ticketId);
var ticket = new TicketDTO(ticketId, "Cannot sign in", "Password reset does not arrive");
queries.stubQuery(query, Optional.of(ticket));

assertThat(queries.send(query)).contains(ticket);
```

`NoOpTransactionProvider` executes transactions and deferred work immediately. Use it only when real transaction boundaries are irrelevant to the test.

## Choose the Test Boundary

- Use `@EnableSMDStubs` for a focused handler flow with real discovery, binding, metadata, and application repositories.
- Test a handler directly when only its decisions and immediate collaborators matter.
- Use real integration infrastructure for transaction boundaries, asynchronous channels, serialization, JDBC, or PostgreSQL event processing.
- Use an acceptance test for the complete external entry point and its observable projections.

The runnable [ticket service](../examples/ticket-service/README.md#tests) contains examples of all three application-level boundaries.
