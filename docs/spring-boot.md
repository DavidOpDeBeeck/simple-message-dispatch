# Spring Boot Guide

## When To Use This Path

Use `smd-spring-boot-starter` when your application already uses Spring Boot and you want SMD wired through autoconfiguration.

## Enable SMD

```java

@EnableSMD
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

If you do not pass `packages`, SMD scans from the package of the annotated class.

## Handler Beans

Register handlers as Spring beans. Annotated handler methods must be public:

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

## Injected Gateways And Publisher

The starter provides:

- `CommandGateway`
- `QueryGateway`
- `EventPublisher`

```java

@RestController
public class AccountController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    public AccountController(CommandGateway commandGateway, QueryGateway queryGateway) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
    }
}
```

## Interceptor Beans

Register interceptors as Spring beans and SMD picks them up automatically.

```java

@Bean
@Order(10)
public CommandBusInterceptor loggingInterceptor() {
    return new LoggingCommandInterceptor();
}
```

Transactional interceptors are registered by default at highest precedence:

- `TransactionalCommandBusInterceptor`
- `TransactionalQueryBusInterceptor`
- `TransactionalEventInterceptor`

## Processing Group Configuration

Use `ProcessingGroupsConfigurer` to choose event-delivery behavior:

```java

@Bean
public ProcessingGroupsConfigurer processingGroupsConfigurer() {
    return spec -> {
        spec.processingGroup("notifications").async().await();
        spec.processingGroup("analytics").async().fireAndForget();
        spec.anyProcessingGroup().sync();
    };
}
```

Multiple `ProcessingGroupsConfigurer` beans are supported and applied in order.

If you do not provide one, the starter uses synchronous delivery for all groups.

## Common Wiring Notes

- `ObjectCreator`, `PrincipalProvider`, `TimeProvider`, and `TransactionProvider` are overridable beans
- the event store is disabled until `smd.event-store.enabled=true`
- using the event store still requires explicit routing of processing groups to `EventStoreChannel`

## Related Docs

- [Getting Started](getting-started.md)
- [Event Store Guide](event-store.md)
- [Testing Guide](testing.md)
- [Spring Boot Testing Guide](spring-boot-testing.md)
