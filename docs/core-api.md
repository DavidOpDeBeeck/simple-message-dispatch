# Core API and Manual Setup

Use `smd-api` when you are not using Spring Boot or want direct control over bus construction.

## Install

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-api:0.0.11")
}
```

SMD requires Java 25.

## Messages and Gateways

| Message      | Purpose                                | Dispatch interface |
|--------------|----------------------------------------|--------------------|
| `Command<R>` | Change state and return `R`            | `CommandGateway`   |
| `Query<R>`   | Read state and return `R`              | `QueryGateway`     |
| `Event`      | Notify every matching processing group | `EventPublisher`   |

Application code normally depends on the gateway interfaces. `CommandBus`, `QueryBus`, and `EventBus` are their default implementations.

```java
UUID ticketId = commandGateway.send(new OpenTicketCommand("Cannot sign in", "Password reset does not arrive"));
Optional<TicketDTO> ticket = queryGateway.send(new GetTicketQuery(ticketId));
eventPublisher.publish(new TicketOpenedEvent(ticketId, "Cannot sign in", "Password reset does not arrive"));
```

## Handlers

Annotate public methods with `@CommandHandler`, `@QueryHandler`, or `@EventHandler`. Each method takes exactly one matching payload and may also request message context:

| Parameter                      | Value                                      |
|--------------------------------|--------------------------------------------|
| `MessageId`                    | Current message identifier                 |
| `Metadata`                     | Complete message metadata                  |
| `Principal`                    | `Metadata.principal()`                     |
| `Instant`                      | `Metadata.timestamp()`                     |
| `@MetadataValue("key") String` | A metadata property, or `null` when absent |

```java

@CommandHandler
public UUID handle(
        OpenTicketCommand command,
        MessageId messageId,
        Principal principal,
        @MetadataValue("tenantId") String tenantId) {
    // ...
}
```

Context types other than `@MetadataValue` may appear at most once. Multiple metadata-property parameters are allowed.

Command and query return types must exactly match `Command<R>` or `Query<R>`. Event handlers return `void` and require `@ProcessingGroup` on the method or class. A method-level processing group
overrides the class-level value. `@ProcessingGroup` without a value uses `default`.

Within one processing group, handlers run in ascending `@EventHandler(order = ...)` order and stop on failure. Order between groups is not guaranteed.

## Metadata and Message Lineage

Every message carries:

- a `Principal`
- an `Instant` timestamp
- the parent message's `MessageId`, when dispatch is nested
- immutable `Map<String, String>` properties

When a handler sends another message, SMD preserves the principal and properties, sets the current message as the parent, and generates a fresh timestamp. This context also crosses SMD's built-in
asynchronous dispatchers.

The gateways also accept `CommandMessage`, `QueryMessage`, and `EventMessage` when you need to supply metadata explicitly. Supplied metadata replaces provider-generated metadata, so include every
principal, timestamp, parent, and property value that the receiving handler needs.

## Construct the Buses

Package locators discover annotated handlers. The simplest object creator instantiates each handler through an accessible no-argument constructor:

```java
var packages = List.of("com.example.tickets");
var objectCreator = new ConstructorBasedObjectCreator();

var commandBus = CommandBusSpec.withDefaults()
        .commandHandlers(new PackageBasedCommandHandlerLocator(packages, objectCreator))
        .create();

var queryBus = QueryBusSpec.withDefaults()
        .queryHandlers(new PackageBasedQueryHandlerLocator(packages, objectCreator))
        .create();

var eventBus = EventBusSpec.withDefaults()
        .processingGroups(new PackageBasedProcessingGroupLocator(packages, objectCreator))
        .create();
```

`withDefaults()` supplies system time and a simple principal. Use `withoutDefaults()` only when you provide both a `TimeProvider` and `PrincipalProvider`.

`ConstructorBasedObjectCreator` cannot inject dependencies. Implement `ObjectCreator` and delegate to your dependency-injection container or application factory when handlers need repositories,
gateways, or services.

## Configure Event Delivery

The default `processingGroups(locator)` configuration is synchronous. Configure groups explicitly when they need different behavior:

```java
var eventBus = EventBusSpec.withDefaults()
        .processingGroups(locator, groups -> {
            groups.processingGroup("notifications").async().await();
            groups.processingGroup("ticket-view").sync();
            groups.anyProcessingGroup().sync();
        })
        .create();
```

- `sync()` runs handlers on the publishing thread.
- `async().await()` uses virtual threads and waits for completion; handlers within a group remain sequential.
- `async().fireAndForget()` returns after submission; handler exceptions are logged and cannot be reported to the publisher.
- `source(source)` subscribes the group to incoming events without registering a publication destination.
- `medium(medium)` subscribes the group to the medium's outlet and registers its inlet as a publication destination.
- `disabled()` intentionally skips the group.

When you provide custom group configuration, every discovered group must be named explicitly, covered by `anyProcessingGroup()`, or disabled. Unknown group names, missing configuration, and
conflicting choices for the same group are rejected before any source is subscribed.

## Event Delivery

The contracts in `app.dodb.smd.api.event.delivery` separate publication from subscription:

| Contract      | Role                                                    |
|---------------|---------------------------------------------------------|
| `EventSink`   | Receives event messages through `send(message)`          |
| `EventSource` | Registers subscribers through `subscribe(subscriber)`   |
| `EventMedium` | Pairs an `inlet()` sink with an `outlet()` source         |

Application code publishes through `EventPublisher`, which creates message envelopes, establishes metadata, and runs publication interceptors. The bus sends to sinks in registration order;
if a sink throws, the bus skips the remaining sinks. Reusing the same sink does not duplicate publication.

Register outbound destinations independently of processing-group inputs:

```java
var eventBus = EventBusSpec.withDefaults()
        .sinks(auditSink)
        .processingGroups(locator, groups -> groups
                .processingGroup("billing").source(incomingEvents)
                .anyProcessingGroup().sync())
        .create();
```

Publishing sends to `auditSink` and the synchronous groups. Billing receives only messages from `incomingEvents`.

The built-in media are `SynchronousEventDispatcher`, `ConcurrentEventDispatcher`, and `FireAndForgetEventDispatcher`; `.sync()` and `.async()` configure them.

Use `.selecting(selector)` to filter a sink, source, or both directions of a medium. `EventSelector` supports assignable event types, metadata-property presence or values, and custom lambdas.
Combine selectors with `and(...)`, `or(...)`, and `negate()`:

```java
EventSelector auditEvents = EventSelector.eventType(AuditEvent.class)
        .and(EventSelector.metadataProperty("audit", "true"));

var auditSink = externalAuditSink.selecting(auditEvents);
var auditSource = externalAuditSource.selecting(auditEvents);
```

Nonmatching messages are ignored. Matching messages and processing-group names are preserved; selector failures follow the underlying delivery's failure handling.

A sink defines what completion of `send()` means. A source owns delivery threads, ordering, retries, failures, and lifecycle. Custom sources must invoke subscribers inside
`MetadataFactory.runInScope(message, ...)` so nested messages inherit the received message's metadata.

### Subscriptions

`subscribe(...)` returns a closeable `EventSubscription`:

```java
var dispatcher = new SynchronousEventDispatcher();
try (var subscription = dispatcher.subscribe(subscriber)) {
    dispatcher.send(eventMessage);
}
```

Closing a built-in subscription removes only that registration and is safe to repeat. Work already underway may finish. Bus-created subscriptions currently last for the application's lifetime;
manage handles directly for temporary subscriptions.

## Interceptors and Transactions

An interceptor runs around dispatch and must call `proceed` to continue the chain:

```java
public class LoggingCommandInterceptor implements CommandBusInterceptor {

    @Override
    public <R, C extends Command<R>> R intercept(
            CommandMessage<R, C> message,
            CommandBusInterceptorChain<R, C> chain) {
        System.out.println("Sending " + message.payload().getClass().getSimpleName());
        return chain.proceed(message);
    }
}
```

Register it during bus construction:

```java
var commandBus = CommandBusSpec.withDefaults()
        .commandHandlers(commandHandlerLocator)
        .interceptors(new LoggingCommandInterceptor())
        .create();
```

The interceptor types are `CommandBusInterceptor`, `QueryBusInterceptor`, and `EventInterceptor`. Transactional implementations are available when the application supplies a `TransactionProvider`:

- `TransactionalCommandBusInterceptor`
- `TransactionalQueryBusInterceptor`
- `TransactionalEventInterceptor`

See [Event Store](event-store.md) to attach durable event delivery without Spring.
