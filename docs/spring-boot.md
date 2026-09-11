# Spring Boot Configuration

The starter discovers handlers, constructs the buses, and exposes `CommandGateway`, `QueryGateway`, and `EventPublisher`. Start with [Getting Started](getting-started.md) if you have not sent a
message yet.

## Handler Discovery

Enable SMD on a configuration class:

```java
@EnableSMD
@SpringBootApplication
public class TicketApplication {
}
```

With no `packages` value, SMD scans from the annotated class's package. Specify one or more roots when needed:

```java
@EnableSMD(packages = {"com.example.tickets.usecase", "com.example.tickets.drivenadapter"})
```

Handler classes must be Spring beans. Annotated handler methods must be public.

## Inject the Messaging Interfaces

Depend on the narrow interface needed by each component:

```java

@RestController
public class TicketController {

    private final CommandGateway commands;
    private final QueryGateway queries;

    public TicketController(CommandGateway commands, QueryGateway queries) {
        this.commands = commands;
        this.queries = queries;
    }
}
```

Inject `EventPublisher` into code that publishes events.

## Configure Processing Groups

Without a `ProcessingGroupsConfigurer`, every group uses synchronous delivery. Add one when groups need different behavior:

```java
@Bean
ProcessingGroupsConfigurer processingGroupsConfigurer() {
    return groups -> {
        groups.processingGroup("notifications").async().await();
        groups.processingGroup("ticket-view").sync();
        groups.anyProcessingGroup().sync();
    };
}
```

| Configuration             | Execution              | Return and failure behavior                      | Transaction and durability                                         |
|---------------------------|------------------------|--------------------------------------------------|--------------------------------------------------------------------|
| `sync()`                  | Publishing thread      | Waits; handler failure reaches the publisher     | Participates in the publisher's thread-bound transaction           |
| `async().await()`         | Virtual worker threads | Waits; handler failures reach the publisher      | Does not inherit the publisher's thread-bound transaction          |
| `async().fireAndForget()` | Virtual worker threads | Returns immediately; handler failures are logged | Does not inherit the publisher's transaction and is not durable    |
| `medium(medium)`          | Defined by the medium  | Defined by the medium                            | Defined by the medium                                              |
| `source(source)`          | Defined by the source  | Delivers incoming events only                    | Does not register a publication destination                        |
| `disabled()`              | No execution           | Returns without invoking the group               | No state change                                                    |

Once custom routing is present, every discovered group must be configured, covered by `anyProcessingGroup()`, or disabled. Multiple configurer beans are applied in Spring order; do not configure the
same named group twice.

Use `.source(eventSource)` to attach an input to a group, or `.medium(eventMedium)` to also send published events to its inlet. See
[Event Delivery](core-api.md#event-delivery) for the underlying contracts.

Enabling the event store automatically registers `eventStore.inlet()` as a sink with the default publisher. Route durable groups explicitly through `.source(eventStore.outlet())` as shown in
[Event Store](event-store.md); enabling storage does not select processing groups.

## Customize Bus Specs

Declare `CommandBusSpecCustomizer`, `EventBusSpecCustomizer`, or `QueryBusSpecCustomizer` beans to adjust the corresponding autoconfigured spec before its bus is created:

```java
@Bean
EventBusSpecCustomizer auditEvents(EventSink auditSink) {
    return spec -> spec.sinks(auditSink);
}
```

Multiple customizers are applied in Spring order. Declaring an `EventSink` bean alone does not register it; use an `EventBusSpecCustomizer` as shown above. Customizers affect only the default
autoconfigured bus, not a user-provided `CommandGateway`, `EventPublisher`, or `QueryGateway`.

## Add Interceptors

Expose interceptors as beans. Spring ordering controls the chain:

```java
@Bean
@Order(10)
CommandBusInterceptor loggingInterceptor() {
    return new LoggingCommandInterceptor();
}
```

The starter registers transactional command, query, and event-publication interceptors at highest precedence. They use Spring transactions through `TransactionProvider`. Synchronous handlers execute
inside that transaction. Work moved to an asynchronous dispatcher does not inherit Spring's thread-bound transaction. With `async().await()`, a failure can roll back the publisher's transaction even
though worker-thread side effects cannot be rolled back with it. Use idempotent or independently transactional side effects when configuring asynchronous delivery.

## Override Infrastructure

Provide a bean of the same type to replace these defaults:

- `ObjectCreator`
- `PrincipalProvider`
- `TimeProvider`
- `TransactionProvider`
- `CommandGateway`, `QueryGateway`, or `EventPublisher`

The default `ObjectCreator` retrieves handlers from the Spring application context. A custom principal provider is the normal place to expose the authenticated application user as message metadata.

## Metadata in Handlers

Handlers may request the message context alongside their payload:

```java

@EventHandler
public void on(
        TicketOpenedEvent event,
        Principal principal,
        Instant timestamp,
        @MetadataValue("tenantId") String tenantId) {
    // ...
}
```

Nested dispatch preserves the principal and metadata properties, records the current message as the parent, and uses a fresh timestamp. See [Core API](core-api.md#metadata-and-message-lineage) for the
complete parameter list.

## Next Steps

- [Event Store](event-store.md) for durable PostgreSQL-backed event processing
- [Testing](testing.md) for Spring test-scope stubs
- [Core API](core-api.md) for manual construction and event delivery
