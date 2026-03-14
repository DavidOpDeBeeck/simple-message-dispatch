# Simple Message Dispatch (SMD)

> **EXPERIMENTAL**: This library is in early development and **subject to breaking changes**. The version will remain **non-final (v0.0.x)** until it is stable.

**Simple Message Dispatch (SMD)** is a lightweight Java library designed to support **CQRS** and **event-driven** applications. It is **framework agnostic** by design, with dedicated support for *
*Spring Boot** provided via an integration module.

## Artifacts

| Artifact                       | Description                                |
|--------------------------------|--------------------------------------------|
| `smd-api`                      | Framework agnostic building blocks         |
| `smd-event-store`              | Event store with JDBC storage and polling  |
| `smd-test`                     | Utilities for unit tests                   |
| `smd-spring-boot-starter`      | Spring Boot integration                    |
| `smd-spring-boot-starter-test` | Spring Boot test support                   |

## Gradle Setup

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Core
    implementation("app.dodb:smd-api:0.0.x")
    testImplementation("app.dodb:smd-test:0.0.x")

    // Event store (optional)
    implementation("app.dodb:smd-event-store:0.0.x")

    // Spring Boot (optional)
    implementation("app.dodb:smd-spring-boot-starter:0.0.x")
    testImplementation("app.dodb:smd-spring-boot-starter-test:0.0.x")
}
```

## Example Usage

The examples below walk through building a simple account service. Each section builds on the previous one.

| #  | Topic                                                                            | Section     |
|----|----------------------------------------------------------------------------------|-------------|
| 1  | [Define your messages](#1-define-your-messages)                                  | Core        |
| 2  | [Write handlers](#2-write-handlers)                                              | Core        |
| 3  | [Wire the buses (framework-agnostic)](#3-wire-the-buses-framework-agnostic)      | Core        |
| 4  | [Bus interceptors](#4-bus-interceptors)                                          | Core        |
| 5  | [Enable autoconfiguration](#5-spring-boot--enable-autoconfiguration)             | Spring Boot |
| 6  | [Register handlers as beans](#6-spring-boot--register-handlers-as-beans)         | Spring Boot |
| 7  | [Dispatch messages](#7-spring-boot--dispatch-messages)                           | Spring Boot |
| 8  | [Configure event channels](#8-spring-boot--configure-event-channels)             | Spring Boot |
| 9  | [Framework-agnostic setup](#9-event-store--framework-agnostic-setup)             | Event Store |
| 10 | [Spring Boot setup](#10-event-store--spring-boot-setup)                          | Event Store |
| 11 | [Unit tests with stubs](#11-testing--unit-tests-with-stubs)                      | Testing     |
| 12 | [Spring Boot integration tests](#12-testing--spring-boot-integration-tests)      | Testing     |

### 1. Define your messages

SMD has three message types. Commands and queries declare their return type via a generic parameter. Events have no return type.

```java
// A command that creates an account and returns its ID
public record CreateAccount(String name) implements Command<UUID> {
}

// A query that fetches an account balance
public record GetAccountBalance(UUID accountId) implements Query<Integer> {
}

// An event that signals an account was created
public record AccountCreated(UUID accountId, String name) implements Event {
}
```

### 2. Write handlers

Each handler is a plain class with an annotated method. SMD resolves the message type from the method parameter.

**Command handler** — receives a command, returns a result:

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

**Event handler** — reacts to an event, returns nothing. The `@ProcessingGroup` annotation groups handlers for dispatch:

```java
@ProcessingGroup
public class AccountProjection {

    @EventHandler
    public void on(AccountCreated event) {
        // update a read model, send a notification, etc.
    }
}
```

**Query handler** — receives a query, returns a result:

```java
public class GetAccountBalanceHandler {

    @QueryHandler
    public int handle(GetAccountBalance query) {
        // look up the balance
        return 100;
    }
}
```

#### Handler method parameters

Besides the payload (the `Command`, `Query`, or `Event` itself), handler methods can declare additional parameters to receive context from the message. SMD resolves each parameter by type:

| Parameter type                   | Resolved value                                          |
|----------------------------------|---------------------------------------------------------|
| `Command<R>` / `Query<R>` / `Event` | The message payload                                 |
| `MessageId`                      | The unique ID of the message                            |
| `Metadata`                       | The full metadata record                                |
| `Principal`                      | The principal from metadata                             |
| `Instant`                        | The timestamp from metadata                             |
| `@MetadataValue("key") String`   | A single value from the metadata additional properties  |

Parameters can be declared in any order. A few examples:

```java
@CommandHandler
public UUID handle(CreateAccount command, Metadata metadata, MessageId messageId) { ... }

@EventHandler
public void on(AccountCreated event, Principal principal, Instant timestamp) { ... }

@QueryHandler
public int handle(GetAccountBalance query, @MetadataValue("tenantId") String tenantId) { ... }
```

### 3. Wire the buses (framework-agnostic)

Without Spring Boot, you assemble buses yourself using the spec builders. SMD discovers handler classes via package scanning and uses an `ObjectCreator` to instantiate them. The built-in
`ConstructorBasedObjectCreator` works for handlers with a no-arg constructor. For handlers that need dependencies, provide your own implementation.

```java
var packages = List.of("com.example.app");
var objectCreator = new MyCustomObjectCreator(); // e.g. using a DI container

var commandBus = CommandBusSpec.withDefaults()
    .commandHandlers(new PackageBasedCommandHandlerLocator(packages, objectCreator))
    .create();

var eventBus = EventBusSpec.withDefaults()
    .processingGroups(new PackageBasedProcessingGroupLocator(packages, objectCreator))
    .create();

var queryBus = QueryBusSpec.withDefaults()
    .queryHandlers(new PackageBasedQueryHandlerLocator(packages, objectCreator))
    .create();
```

By default, all processing groups use synchronous dispatch (handlers run on the publishing thread). You can configure this per group or set a default for all groups:

```java
var eventBus = EventBusSpec.withDefaults()
    .processingGroups(locator, spec -> {
        // "notifications" group runs async, publisher waits for completion
        spec.processingGroup("notifications").async().await();

        // "analytics" group runs async, publisher does not wait (fire-and-forget)
        spec.processingGroup("analytics").async().fireAndForget();

        // "audit" group is disabled and will not receive events
        spec.processingGroup("audit").disabled();

        // all other groups fall back to synchronous
        spec.anyProcessingGroup().sync();
    })
    .create();
```

You can also provide a fully custom `EventChannel` implementation via `.channel(myChannel)`.

Then send messages through the bus:

```java
UUID id = commandBus.send(new CreateAccount("Alice"));
int balance = queryBus.send(new GetAccountBalance(id));
```

### 4. Bus interceptors

Each bus supports an interceptor chain that wraps message handling. Interceptors receive the message and a chain to call `proceed()` on:

```java
public class LoggingCommandInterceptor implements CommandBusInterceptor {

    @Override
    public <R, C extends Command<R>> R intercept(CommandMessage<R, C> message, CommandBusInterceptorChain<R, C> chain) {
        System.out.println("Handling " + message.payload().getClass().getSimpleName());
        return chain.proceed(message);
    }
}
```

The same pattern applies to `QueryBusInterceptor` and `EventBusInterceptor`.

**Framework-agnostic** — pass interceptors when building the bus:

```java
var commandBus = CommandBusSpec.withDefaults()
    .commandHandlers(locator)
    .interceptors(new LoggingCommandInterceptor())
    .create();
```

**Spring Boot** — register interceptors as beans and they are picked up automatically:

```java
@Bean
@Order(10)
public CommandBusInterceptor loggingInterceptor() {
    return new LoggingCommandInterceptor();
}
```

The transactional interceptors (`TransactionalCommandBusInterceptor`, `TransactionalQueryBusInterceptor`, `TransactionalEventBusInterceptor`) are registered at `HIGHEST_PRECEDENCE` by default, so custom interceptors run inside the transaction.

### 5. Spring Boot — Enable autoconfiguration

With the Spring Boot starter, a single annotation replaces all manual wiring:

```java
@EnableSMD
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 6. Spring Boot — Register handlers as beans

Annotate handler classes with `@Component` (or any Spring stereotype) and inject dependencies as usual:

```java
@Component
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

### 7. Spring Boot — Dispatch messages

Inject `CommandGateway`, `QueryGateway`, or `EventPublisher` wherever you need to send messages:

```java
@RestController
public class AccountController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    public AccountController(CommandGateway commandGateway, QueryGateway queryGateway) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
    }

    @PostMapping("/accounts")
    public UUID createAccount(@RequestBody String name) {
        return commandGateway.send(new CreateAccount(name));
    }

    @GetMapping("/accounts/{id}/balance")
    public int getBalance(@PathVariable UUID id) {
        return queryGateway.send(new GetAccountBalance(id));
    }
}
```

### 8. Spring Boot — Configure event channels

Section [3](#3-wire-the-buses-framework-agnostic) shows how to configure processing group channels in framework-agnostic mode. In Spring Boot, define a `ProcessingGroupsConfigurer` bean:

```java
@Bean
public ProcessingGroupsConfigurer processingGroupsConfigurer() {
    return spec -> {
        // "notifications" group runs async, publisher waits for completion
        spec.processingGroup("notifications").async().await();

        // "analytics" group runs async, publisher does not wait
        spec.processingGroup("analytics").async().fireAndForget();

        // "audit" group is disabled and will not receive events
        spec.processingGroup("audit").disabled();

        // all other groups fall back to synchronous
        spec.anyProcessingGroup().sync();
    };
}
```

Multiple `ProcessingGroupsConfigurer` beans are supported — they are applied in order. If no `ProcessingGroupsConfigurer` bean is defined, all processing groups default to synchronous dispatch.

When using the event store ([Section 10](#10-event-store--spring-boot-setup)), you must explicitly route processing groups to the `EventStoreChannel`:

```java
@Bean
public ProcessingGroupsConfigurer processingGroupsConfigurer(EventStoreChannel eventStoreChannel) {
    return spec -> spec
        .anyProcessingGroup().channel(eventStoreChannel);
}
```

### 9. Event Store — Framework-agnostic setup

This section covers manual setup — for Spring Boot, see [Section 10](#10-event-store--spring-boot-setup).

The `EventStoreChannel` persists events to a database within the current transaction, then polls and delivers them to `@ProcessingGroup` handlers. Each processing group tracks its own position via a
token, with gap detection and configurable retry backoff.

You need a `ConnectionProvider` (provides JDBC connections), a `TransactionProvider` (manages transactions), and an `ObjectMapper` (for serialization):

```java
var connectionProvider = new MyConnectionProvider(dataSource);
var transactionProvider = new MyTransactionProvider();

var eventStoreChannel = new EventStoreChannel(
    EventStoreChannelConfig.withDefaults()
        .transactionProvider(transactionProvider)
        .eventStorage(new JdbcEventStorage(connectionProvider))
        .tokenStore(new JdbcTokenStore(connectionProvider))
        .eventSerializer(new JacksonEventSerializer(objectMapper))
        .build()
);
```

Then pass it as a custom channel when building the event bus:

```java
var eventBus = EventBusSpec.withDefaults()
    .processingGroups(locator, spec -> {
        spec.anyProcessingGroup().channel(eventStoreChannel);
    })
    .create();
```

Scheduling and processing behavior can be tuned via `EventStoreChannelConfig`:

```java
EventStoreChannelConfig.withDefaults()
    .transactionProvider(transactionProvider)
    .eventStorage(new JdbcEventStorage(connectionProvider))
    .tokenStore(new JdbcTokenStore(connectionProvider))
    .eventSerializer(new JacksonEventSerializer(objectMapper))
    .schedulingConfig(SchedulingConfig.withDefaults()
        .initialDelay(Duration.ofSeconds(10))
        .pollingDelay(Duration.ofSeconds(5))
        .build())
    .processingConfig(ProcessingConfig.withDefaults()
        .maxRetries(5)
        .batchSize(200)
        .gapTimeout(Duration.ofMinutes(10))
        .retryBackoffStrategy(RetryBackoffStrategy.exponential(
            Duration.ofSeconds(1), 5.0, Duration.ofMinutes(5)))
        .build())
    .build();
```

The DB schema is at `core/smd-event-store/src/main/resources/db/smd/event-store-schema.sql`.

#### Schema migration with Flyway

Copy the schema SQL into your Flyway migrations directory:

```
src/main/resources/db/migration/V1__smd_event_store.sql
```

Or reference it directly from the classpath — the schema file is included in the `smd-event-store` JAR:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations("classpath:db/migration", "classpath:db/smd")
    .load()
    .migrate();
```

In Spring Boot, add the location to `application.yml`:

```yaml
spring:
    flyway:
        locations:
            - classpath:db/migration
            - classpath:db/smd
```

#### Schema migration with Liquibase

Create a changeset that includes the schema SQL using `sqlFile`:

```xml
<!-- src/main/resources/db/changelog/db.changelog-master.xml -->
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="smd-event-store" author="smd">
        <sqlFile path="db/smd/event-store-schema.sql"
                 relativeToChangelogFile="false"/>
    </changeSet>
</databaseChangeLog>
```

The `event-store-schema.sql` uses `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`, so it is safe to re-run.

### 10. Event Store — Spring Boot setup

With the Spring Boot starter, the event store is configured entirely via `application.yml`. A `DataSource` bean is required:

```yaml
smd:
    event-store:
        enabled: true
```

All `@ProcessingGroup` handlers will automatically receive events from the store. You can further configure scheduling and processing:

```yaml
smd:
    event-store:
        enabled: true
        scheduling:
            enabled: true
            initial-delay: 10s
            polling-delay: 5s
            thread-pool-size: 1
        processing:
            max-retries: 3
            batch-size: 100
            gap-timeout: 5m
            retry-backoff:
                strategy: EXPONENTIAL  # FIXED | LINEAR | EXPONENTIAL
                initial-delay: 1s
                multiplier: 5.0
                max-delay: 5m
```

### 11. Testing — Unit tests with stubs

`smd-test` provides stub implementations of `CommandGateway`, `QueryGateway`, and `EventPublisher` so you can unit test handlers in isolation:

```java
class CreateAccountHandlerTest {

    private final EventPublisherStub eventPublisher = new EventPublisherStub();
    private final CreateAccountHandler handler = new CreateAccountHandler(eventPublisher);

    @Test
    void createsAccountAndPublishesEvent() {
        handler.handle(new CreateAccount("Alice"));

        assertThat(eventPublisher.getEvents())
            .hasSize(1)
            .first()
            .isInstanceOf(AccountCreated.class);
    }
}
```

### 12. Testing — Spring Boot integration tests

Add `@EnableSMDStubs` to your test class to auto-replace all gateways and publishers with stubs. Stubs are reset automatically between tests:

```java
@SpringBootTest
@EnableSMDStubs
class AccountControllerTest {

    @Autowired
    private CommandGatewayStub commandGateway;

    @Test
    void sendsCreateAccountCommand() {
        commandGateway.stubCommand(new CreateAccount("Alice"), UUID.randomUUID());

        // call your controller / service and assert the result
    }
}
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for details.
