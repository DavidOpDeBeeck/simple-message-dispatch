# Core API and Manual Setup

Use `smd-api` when you are not using Spring Boot or want direct control over bus construction.

## Install

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-api:0.0.10")
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

Within one processing group, matching event handlers run in ascending `@EventHandler(order = ...)` order. Different processing groups have independent delivery.

## Metadata and Message Lineage

Every message carries:

- a `Principal`
- an `Instant` timestamp
- the parent message's `MessageId`, when dispatch is nested
- immutable `Map<String, String>` properties

When a handler sends another message, SMD preserves the principal and properties, sets the current message as the parent, and generates a fresh timestamp. This context also crosses SMD's built-in
asynchronous event channels.

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
- `async().await()` uses virtual threads and waits for all handlers.
- `async().fireAndForget()` returns immediately; failures cannot be reported to the publisher.
- `source(source)` subscribes the group to incoming events without registering a publication destination.
- `channel(channel)` subscribes the group and registers the `EventChannel` as a publication destination.
- `disabled()` intentionally skips the group.

When you provide custom group configuration, every discovered group must be named explicitly, covered by `anyProcessingGroup()`, or disabled. Unknown group names, missing configuration, and
conflicting choices for the same group are rejected before any source is subscribed.

## Event Sinks and Sources

The contracts in `app.dodb.smd.api.event.channel` separate publication from subscription:

| Contract       | Responsibility                                                                                |
|----------------|-----------------------------------------------------------------------------------------------|
| `EventSink`    | Accept an existing `EventMessage<E>` through `send(message)`                                  |
| `EventSource`  | Deliver incoming messages to processing-group listeners registered with `subscribe(listener)` |
| `EventChannel` | Combine `EventSink` and `EventSource`                                                         |

Application code continues to use `EventPublisher`, which constructs message envelopes, establishes metadata, and runs publication interceptors. Sinks receive those envelopes, while sources deliver
incoming messages directly to their subscribed processing groups.

Register outbound destinations independently of processing-group inputs:

```java
var eventBus = EventBusSpec.withDefaults()
        .sinks(auditSink)
        .processingGroups(locator, groups -> groups
                .processingGroup("billing").source(incomingEvents)
                .anyProcessingGroup().sync())
        .create();
```

Publishing sends to `auditSink` and the synchronous groups. Billing receives only messages delivered by `incomingEvents`. Using `.source(channel)` does not add that channel to the bus's outbound
destinations.

Use `.channel(channel)` when one component should participate in both directions, or register its sink and source roles separately. The built-in synchronous and asynchronous channels implement both
contracts; `.sync()` and `.async()` connect them in both directions.

A sink defines what `send()` completion means, such as completed local handling, asynchronous submission, or deferred storage. A source owns its delivery threads, ordering, retries, failures, and
lifecycle. Custom sources must invoke listeners inside `MetadataFactory.runInScope(message, ...)` so nested messages inherit the received message's metadata and lineage.

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
