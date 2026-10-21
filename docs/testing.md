# Testing

SMD provides Spring test-scope support for complete handler flows and framework-independent stubs for direct unit tests.

## Test a Spring Handler Flow

Add the Spring test module alongside Spring Boot's test starter:

```kotlin
dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.12")
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

The test buses use the context's interceptor beans, including `MessageTracing` when the context has an `OpenTelemetry` bean. Messages then carry trace context such as
`traceparent` in their metadata properties: assert on the properties your code sets rather than the whole map, or set `smd.opentelemetry.enabled=false` for the test.

## Test a Handler Directly

Use `smd-test` when package discovery, interceptors, and the Spring context are irrelevant:

```kotlin
dependencies {
    testImplementation("app.dodb:smd-test:0.0.12")
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
- `EventSinkStub`
- `EventSourceStub`
- `EventSubscriberStub`
- `PrincipalProviderStub`
- `TimeProviderStub`
- `NoOpTransactionProvider`

`EventSinkStub` and `EventSourceStub` capture messages in send order and name the delivery `testing`. `getEventMessages()` returns an immutable snapshot; `reset()` clears captures but preserves source
subscriptions.
`EventSourceStub.send(message)` also delivers synchronously in subscription order within the message's metadata scope, stopping on the first subscriber failure.
Close the returned `EventSubscription` to unsubscribe.

Command and query stubs match messages by `equals`, so records make convenient test messages:

```java
var queries = new QueryGatewayStub();
var query = new GetTicketQuery(ticketId);
var ticket = new TicketDTO(ticketId, "Cannot sign in", "Password reset does not arrive");
queries.stubQuery(query, Optional.of(ticket));

assertThat(queries.send(query)).contains(ticket);
```

`NoOpTransactionProvider` executes transactions and deferred work immediately. Use it only when real transaction boundaries are irrelevant to the test.

## Test Stored Event Upcasting

Construction-time validation checks the upcasters against the resolver, but it cannot know which type names exist in your store. Keep one stored JSON sample per historical type name, for example
`src/test/resources/stored-events/ticket.created.v1.json`, copied from `serialized_payload` of a real row. Deserialize every sample through the application's real mapper, resolver, and
upcasters, and assert the current event:

```java
class StoredEventFixturesTest {

    private static final byte[] METADATA = "{\"properties\": {}}".getBytes(UTF_8);

    // Use the same resolver and upcasters as the application, or inject its EventSerializer bean.
    private final EventSerializer serializer = new JacksonEventSerializer(
        JsonMapper.builder().addModule(new SMDJacksonModule()).build(),
        TicketEventTypes.resolver(),
        TicketEventTypes.upcasters()
    );

    @ParameterizedTest
    @MethodSource("storedEvents")
    void deserialize_storedEvent_readsCurrentEvent(String eventType, Event expected) throws IOException {
        var payload = getClass().getResourceAsStream("/stored-events/" + eventType + ".json").readAllBytes();
        var stored = new SerializedEvent(MessageId.generate(), 1L, Optional.of(TICKET_ID.toString()), eventType, payload, METADATA, Instant.EPOCH);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized.payload()).isEqualTo(expected);
    }

    static Stream<Arguments> storedEvents() {
        return Stream.of(
            arguments("ticket.created.v1", new TicketOpenedEvent(TICKET_ID, "Cannot sign in", "NORMAL")),
            arguments("ticket.opened.v1", new TicketOpenedEvent(TICKET_ID, "Cannot sign in", "NORMAL")),
            arguments("ticket.opened.v2", new TicketOpenedEvent(TICKET_ID, "Cannot sign in", "HIGH"))
        );
    }
}
```

Add a sample before removing an event class or renaming a type, and never edit a sample afterwards: it represents rows that are already stored. Include the current type names as well, so a
class change that breaks reading existing rows also fails the test.

## Choose the Test Boundary

- Use `@EnableSMDStubs` for a focused handler flow with real discovery, binding, metadata, and application repositories.
- Test a handler directly when only its decisions and immediate collaborators matter.
- Use real integration infrastructure for transaction boundaries, asynchronous delivery, serialization, JDBC, or PostgreSQL event processing.
- Use an acceptance test for the complete external entry point and its observable projections.

The runnable [ticket service](../examples/ticket-service/README.md#tests) contains examples of all three application-level boundaries.
