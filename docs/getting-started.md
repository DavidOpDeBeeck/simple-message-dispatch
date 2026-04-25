# Getting Started

## Choose Your Path

Use the lightest setup that fits your application:

- Start with `smd-api` if you want framework-agnostic buses and handler discovery
- Add `smd-spring-boot-starter` if your application already uses Spring Boot
- Add `smd-event-store` when event delivery must be persisted and polled from storage
- Add `smd-test` or `smd-spring-boot-starter-test` for testing support

## Install

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.dodb:smd-api:0.0.8")
}
```

Optional modules:

```kotlin
implementation("app.dodb:smd-event-store:0.0.8")
implementation("app.dodb:smd-spring-boot-starter:0.0.8")
testImplementation("app.dodb:smd-test:0.0.8")
testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.8")
```

## Messages

SMD defines three message types:

```java
public record CreateAccount(String name) implements Command<UUID> {
}

public record GetAccountBalance(UUID accountId) implements Query<Integer> {
}

public record AccountCreated(UUID accountId, String name) implements Event {
}
```

## Metadata

Every message carries `Metadata`:

- `Principal principal`
- `Instant timestamp`
- `MessageId parentMessageId`
- `Map<String, String> properties`

```java
var metadata = new Metadata(principal, Instant.now(), parentMessageId, Map.of(
        "tenantId", "acme",
        "correlationId", "checkout-123"
));
```

Metadata properties are immutable. When a handler dispatches another message, SMD copies the parent metadata, preserves the lineage through `parentMessageId`, and refreshes the timestamp.

## Handlers

Handlers are plain classes with annotated methods:

- `@CommandHandler`
- `@QueryHandler`
- `@EventHandler`

Annotated handler methods must be public.

## First Example

```java
public class CreateAccountHandler {

    private final EventPublisher eventPublisher;

    public CreateAccountHandler(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @CommandHandler
    public UUID handle(CreateAccount command) {
        var id = UUID.randomUUID();
        eventPublisher.publish(new AccountCreated(id, command.name()));
        return id;
    }
}
```

Event handlers belong to a processing group through `@ProcessingGroup`.

You can put `@ProcessingGroup` on the class or on an individual handler method. Method-level annotation wins over the class-level one. `@ProcessingGroup` without a value means the `default` processing
group.

```java

@ProcessingGroup
public class AccountProjection {

    @EventHandler
    public void on(AccountCreated event) {
        // update a read model
    }
}

public class AuditHandlers {

    @EventHandler
    @ProcessingGroup("audit")
    public void on(AccountCreated event) {
        // handled in the "audit" processing group
    }
}
```

## Handler Parameters

Besides the message payload, SMD can inject message context into handler parameters.

| Parameter type                      | Resolved value                     |
|-------------------------------------|------------------------------------|
| `Command<R>` / `Query<R>` / `Event` | Message payload                    |
| `MessageId`                         | Message identifier                 |
| `Metadata`                          | Full metadata                      |
| `Principal`                         | Metadata principal                 |
| `Instant`                           | Metadata timestamp                 |
| `@MetadataValue("key") String`      | Value from `Metadata.properties()` |

```java

@CommandHandler
public UUID handle(CreateAccount command, Metadata metadata, MessageId messageId) { ...}

@EventHandler
public void on(AccountCreated event, Principal principal, Instant timestamp) { ...}
```

## Interceptors

Each bus supports an interceptor chain:

- `CommandBusInterceptor`
- `QueryBusInterceptor`
- `EventInterceptor`

`EventInterceptor` is the single event interception model for both publishing and channel delivery.

```java
public class LoggingCommandInterceptor implements CommandBusInterceptor {

    @Override
    public <R, C extends Command<R>> R intercept(CommandMessage<R, C> message, CommandBusInterceptorChain<R, C> chain) {
        return chain.proceed(message);
    }
}
```

## Wiring The Buses

Use this setup when you want SMD without Spring Boot and are happy wiring the buses yourself.

SMD provides builder-style specs for each bus:

- `CommandBusSpec`
- `QueryBusSpec`
- `EventBusSpec`

```java
var packages = List.of("com.example.app");
var objectCreator = new MyObjectCreator();

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

## Object Creation

Handler instances are created through `ObjectCreator`.

- `ConstructorBasedObjectCreator` only works for handlers with a no-arg constructor
- provide your own implementation when handlers need constructor-injected or container-managed dependencies

For example, this works with `ConstructorBasedObjectCreator`:

```java
public class AuditHandler {

    @EventHandler
    @ProcessingGroup("audit")
    public void on(AccountCreated event) {
        // no injected dependencies required
    }
}
```

If your handlers depend on repositories, gateways, or services through their constructor, plug in a custom `ObjectCreator` that delegates to your own container or factory.

## Bus Interceptors

Add interceptors during bus creation:

```java
var commandBus = CommandBusSpec.withDefaults()
        .commandHandlers(locator)
        .interceptors(new LoggingCommandInterceptor())
        .create();
```

Transactional interceptors are available when you provide a `TransactionProvider`:

- `TransactionalCommandBusInterceptor`
- `TransactionalQueryBusInterceptor`
- `TransactionalEventInterceptor`

## Processing Groups And Event Channels

Processing groups determine how event handlers are dispatched.

- synchronous on the publishing thread
- asynchronous while awaiting completion
- asynchronous fire-and-forget
- custom `EventChannel`
- disabled explicitly

By default, `processingGroups(locator)` uses synchronous delivery. Use a custom configurer when you need different behavior:

```java
var eventBus = EventBusSpec.withDefaults()
        .processingGroups(locator, spec -> {
            spec.processingGroup("notifications").async().await();
            spec.processingGroup("analytics").async().fireAndForget();
            spec.processingGroup("audit").disabled();
            spec.anyProcessingGroup().sync();
        })
        .create();
```

When you define custom processing-group configuration, every discovered group must either:

- receive a specific channel
- be covered by `anyProcessingGroup()`
- be disabled explicitly

Otherwise bus creation fails instead of silently skipping the group.

You can also attach a custom `EventChannel` with `.channel(myChannel)`.

## Dispatching Messages

```java
UUID accountId = commandBus.send(new CreateAccount("Alice"));
int balance = queryBus.send(new GetAccountBalance(accountId));
eventBus.publish(new AccountCreated(accountId, "Alice"));
```

## Where To Go Next

- Use [Spring Boot Guide](spring-boot.md) for `@EnableSMD`
- Use [Event Store Guide](event-store.md) if events must be stored and replayed
- Use [Testing Guide](testing.md) for lightweight tests without Spring Boot
- Use [Spring Boot Testing Guide](spring-boot-testing.md) for Spring Boot test support
