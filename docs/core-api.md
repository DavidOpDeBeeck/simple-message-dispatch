# Core API and Manual Setup

Use `smd-api` when you are not using Spring Boot or want direct control over bus construction.

## Install

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-api:0.0.12")
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
- immutable `MetadataProperties`: string values by name

When a handler sends another message, SMD preserves the principal and the inherited properties, sets the current message as the parent, and generates a fresh timestamp. This context also crosses SMD's built-in
asynchronous dispatchers. The current message is the one the handler received: when a bus or processing group interceptor proceeds with changed metadata, the messages its handlers send
inherit that metadata.

`MetadataProperties` reads and builds properties without copying maps by hand:

```java
Optional<String> tenant = metadata.property("tenant");

var properties = MetadataProperties.of("tenant", "acme", "region", "eu")
        .with("Idempotency-Key", key)
        .without("region");

eventPublisher.publish(event, Metadata.withProperties(properties));
eventPublisher.publish(event, Metadata.withProperty("Idempotency-Key", key));
```

`with(...)` and `without(...)` return new properties, and `with(other)` lets the other side win on a clash. `asMap()` gives the plain map when an API needs one. In storage and on
the wire, properties stay a plain JSON object.

### Choose Inherited Properties

A `MetadataInheritance` decides which properties the sent message inherits. The default, `MetadataInheritance.excludingSmdProperties()`, inherits every property except those starting with
`smd.`: SMD keeps per-delivery values such as the event store's processing details under that prefix, and they describe the received message only.

Exclude your own per-message properties by prefix or by name, and keep SMD's rule by combining with `and(...)`:

```java
var inheritance = MetadataInheritance.excludingSmdProperties()
        .and(MetadataInheritance.excludingPrefix("request."))
        .and(MetadataInheritance.excludingProperties("Idempotency-Key"));

var eventBus = EventBusSpec.withDefaults()
        .metadataInheritance(inheritance)
        .processingGroups(locator)
        .create();
```

`MetadataInheritance` is a functional interface, so any `property -> boolean` predicate works. Set the same inheritance on the command, query, and event bus specs; each bus applies its own
to the messages it creates. A policy that inherits `smd.` properties lets one delivery's details reach the next message, so start from `excludingSmdProperties()`.

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

`withDefaults()` supplies system time and a simple principal. Use `withoutDefaults()` only when you provide both a `TimeProvider` and `PrincipalProvider`. Both start with
`MetadataInheritance.excludingSmdProperties()` until `metadataInheritance(...)` replaces it.

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

| Contract      | Role                                                                             |
|---------------|----------------------------------------------------------------------------------|
| `EventSink`   | Receives event messages through `send(message)` and names its `delivery()`       |
| `EventSource` | Registers subscribers through `subscribe(subscriber)` and names its `delivery()` |
| `EventMedium` | Pairs an `inlet()` sink with an `outlet()` source                                |
| `EventRoute`  | Connects a `source` to a `sink` under a `name`, so events flow between media     |

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

Use `.selecting(selector)` to filter a sink, source, or both directions of a medium. `EventSelector` supports assignable event types (`eventType(A.class, B.class)`), event packages and their subpackages
(`eventPackage(TicketEvent.class.getPackage())`), metadata-property presence or values, and custom lambdas.
Combine selectors with `and(...)`, `or(...)`, and `negate()`:

```java
EventSelector auditEvents = EventSelector.eventType(AuditEvent.class)
        .and(EventSelector.metadataProperty("audit", "true"));

var auditSink = externalAuditSink.selecting(auditEvents);
var auditSource = externalAuditSource.selecting(auditEvents);
```

Nonmatching messages are ignored. Matching messages and subscriber names are preserved; selector failures follow the underlying delivery's failure handling.

A sink defines what completion of `send()` means. A source owns delivery threads, ordering, retries, failures, and lifecycle. Custom sources must invoke subscribers inside
`MetadataFactory.runInScope(message, ...)` so nested messages inherit the received message's metadata.

`delivery()` returns an `EventDelivery` naming the medium a sink writes to or a source reads from: `sync`, `async-await`, and `async-fire-and-forget` for the built-in media,
`event-store` for the event store, and the delivery of the sink or source it selects from for a selecting one. Name a custom sink or source after the medium it uses, such
as its broker:

```java
@Override
public EventDelivery delivery() {
    return new EventDelivery("kafka");
}
```

Tracing records this name as `messaging.smd.delivery`.

### Route Between Media

A route moves events from a source into a sink without a handler: from an outbox to a broker, from a broker into an inbox, or from one topic to another. Declare routes on the
bus, next to its sinks and processing groups:

```java
var eventBus = EventBusSpec.withDefaults()
        .sinks(outgoing.inlet())
        .route("orders.relay", outgoing.outlet(), broker.inlet())
        .processingGroups(locator)
        .create();
```

The bus subscribes each route to its source under the route's name after it subscribes the processing groups, for the application's lifetime. A route sends on the source's
thread through the sink interceptors, so tracing records each hop as a `send` span with `messaging.smd.route`. A failing sink fails the source's delivery, and the source retries,
rejects, or logs it as it would for a failing processing group.

Sources name their subscriptions after the subscriber, a processing group or a route, so the bus rejects a route whose name another route or processing group already uses.
Filter a route with `.selecting(...)` on its source or its sink. A bus needs no processing groups to carry routes.

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

An interceptor runs around one component, receives the message or a context, and must call `proceed` exactly once to continue the chain:

```java
public class LoggingCommandInterceptor implements CommandBusInterceptor {

    @Override
    public <R, C extends Command<R>> R intercept(
            CommandMessage<R, C> commandMessage,
            CommandBusInterceptorChain<R, C> chain) {
        System.out.println("Sending " + commandMessage.payload().getClass().getSimpleName());
        return chain.proceed(commandMessage);
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

Each interceptor is named after the component it wraps:

| Interceptor                  | Package                           | Wraps                                   | Receives                 | Registered with                                       |
|------------------------------|-----------------------------------|-----------------------------------------|--------------------------|-------------------------------------------------------|
| `CommandBusInterceptor`      | `app.dodb.smd.api.command.bus`    | The command bus dispatching a command   | `CommandMessage`         | `.interceptors(...)` on `CommandBusSpec`              |
| `QueryBusInterceptor`        | `app.dodb.smd.api.query.bus`      | The query bus dispatching a query       | `QueryMessage`           | `.interceptors(...)` on `QueryBusSpec`                |
| `EventBusInterceptor`        | `app.dodb.smd.api.event.bus`      | The event bus publishing an event       | `EventMessage`           | `.interceptors(...)` on `EventBusSpec`                |
| `EventSinkInterceptor`       | `app.dodb.smd.api.event.delivery` | Each sink an event is sent to           | `EventSinkContext`       | `.sinkInterceptors(...)` on `EventBusSpec`            |
| `ProcessingGroupInterceptor` | `app.dodb.smd.api.event`          | Each processing group handling an event | `ProcessingGroupContext` | `.processingGroupInterceptors(...)` on `EventBusSpec` |

The first interceptor registered wraps the ones after it. A context holds the message and what the component knows about it. Proceed with a changed message, or with
`context.withEventMessage(message)`, to pass it on; the messages a handler sends inherit the metadata of the message it received. Do not swallow an exception thrown by
`proceed`: the caller, transaction, and source rely on it to see the failure.

- A command or query bus interceptor runs on the sending thread, around the handler; the handler receives the message the interceptors passed on.
- An `EventSinkContext` holds the sink and the event message. A sink interceptor runs on the publishing thread, inside the event bus interceptors, once for every registered
  sink, before the sink's `send`. A selecting sink applies its selector inside `send`, so a sink interceptor also sees the events that selector drops.
- A `ProcessingGroupContext` holds the processing group, the group's source, and the event message. A processing group interceptor runs on the thread that
  runs the group's handlers, whichever source delivers the event, and is not called for groups without a handler for the event:

```java
var eventBus = EventBusSpec.withDefaults()
        .processingGroups(processingGroupLocator)
        .processingGroupInterceptors(new ProcessingGroupInterceptor() {
            @Override
            public <E extends Event> void intercept(ProcessingGroupContext<E> context, ProcessingGroupInterceptorChain<E> chain) {
                System.out.println(context.processingGroup() + " handles " + context.eventMessage().payload().getClass().getSimpleName());
                chain.proceed(context);
            }
        })
        .create();
```

Transactional bus interceptors are available when the application supplies a `TransactionProvider`:

- `TransactionalCommandBusInterceptor`
- `TransactionalQueryBusInterceptor`
- `TransactionalEventBusInterceptor`

They run each dispatch in a transaction on the dispatching thread. Handlers on an asynchronous dispatcher run on worker threads, outside that transaction.

With Spring, declare interceptors as beans instead; see [Add Interceptors](spring-boot.md#add-interceptors).

See [Event Store](event-store.md) to attach durable event delivery without Spring.
