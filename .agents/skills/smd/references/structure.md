# SMD: Application Structure

## Contents

- [3.1 What goes where](#31-what-goes-where)
- [3.2 Dependency direction](#32-dependency-direction)
- [3.3 Configuration classes](#33-configuration-classes)

## 3. Application Structure

Use this layout. It is the layout of SMD's reference application, the ticket service, apart from the `ticket-escalation` policy, which only illustrates the
`usecase` layer:
https://github.com/DavidOpDeBeeck/simple-message-dispatch/tree/main/examples/ticket-service

```text
com.example.ticket
├── TicketApplication.java               imports every layer's configuration; no component scan of its own
├── domain/                              aggregates, domain exceptions, domain values
│   └── DomainConfiguration.java
├── drivingport/                         the application's public contract
│   ├── command/                         Command records
│   ├── query/                           Query records
│   ├── event/                           Event records and a sealed interface per subject
│   └── transferobject/                  DTO records returned by queries
├── usecase/                             query handlers, plain command handlers, policies (event handlers that send commands)
│   └── UseCaseConfiguration.java
├── drivenport/                          interfaces the domain and use cases need: repositories, external systems
├── drivenadapter/                       implementations of driven ports; projections; external side effects
│   ├── DrivenAdapterConfiguration.java
│   ├── ticketview/                      one subpackage per processing group or adapter
│   └── customerwebhooks/
├── drivingadapter/                      REST controllers, exception mapping, other entry points
│   └── DrivingAdapterConfiguration.java
└── infrastructure/                      cross-cutting: interceptors, PrincipalProvider, logging
    └── InfrastructureConfiguration.java
```

### 3.1 What goes where

| Code                                                              | Package                                  |
|-------------------------------------------------------------------|------------------------------------------|
| `Command`, `Query`, `Event` records                               | `drivingport.command`, `.query`, `.event` |
| Query results, and the values they expose such as a status enum   | `drivingport.transferobject`             |
| The `Principal` record and helpers that validate messages         | `drivingport`                            |
| `@Aggregate` classes and the exceptions they throw                | `domain`                                 |
| `@QueryHandler`                                                   | `usecase`                                |
| `@CommandHandler` for state that is not an aggregate              | `usecase`                                |
| `@EventHandler` that decides and sends commands (a policy)        | `usecase`                                |
| Repository and external-system interfaces                         | `drivenport`                             |
| JDBC repositories, projections (`@EventHandler` that write a read model) | `drivenadapter.<name>`            |
| `@EventHandler` that calls an external system                     | `drivenadapter.<name>`                   |
| Controllers sending commands and queries                          | `drivingadapter`                         |
| `@ExceptionHandler` mapping SMD and domain exceptions to HTTP     | `drivingadapter`                         |
| Interceptors, `PrincipalProvider`, `TimeProvider`                 | `infrastructure`                         |
| `EventTypeResolver` and `EventUpcaster` beans                     | `DrivenAdapterConfiguration`             |
| A processing group's name constant and its delivery               | The configuration class of the layer that holds its handlers |

### 3.2 Dependency direction

| Package          | May depend on                                                                                     |
|------------------|---------------------------------------------------------------------------------------------------|
| `drivingport`    | Nothing else in the application                                                                   |
| `domain`         | `drivingport`, `drivenport`                                                                       |
| `usecase`        | `drivingport`, `drivenport`                                                                       |
| `drivenport`     | `drivingport`                                                                                     |
| `drivenadapter`  | `drivenport`, `drivingport`, and its layer configuration's constants                              |
| `drivingadapter` | `drivingport`, and `domain` only for exception types to map                                        |
| `infrastructure` | Any layer, only for cross-cutting wiring                                                          |

Nothing depends on `drivingadapter` or `drivenadapter`. Use cases reach aggregates by sending commands, never by calling them.

### 3.3 Configuration classes

Every layer has one configuration class in its root package. Rules:

- Put `@EnableSMD` without `packages` on the configuration class of every layer that contains handlers, processing groups, or aggregates: usually `domain`, `usecase`,
  and `drivenadapter`. It scans that layer's package. SMD combines the packages of every `@EnableSMD`.
- Add `@ComponentScan` to every layer configuration except `domain`. Aggregates are not beans; add it to `domain` only when it has beans, such as a domain service.
- Annotate the application class with `@SpringBootConfiguration`, `@EnableAutoConfiguration`, and `@Import` of every layer configuration. Do not use
  `@SpringBootApplication`: its component scan would bypass the layer configurations, which tests load one layer at a time.
- Each layer configuration declares the processing groups of its own handlers: a `public static final String` constant per group name, and one `ProcessingGroupsConfigurer`
  bean that names each of them. Handlers refer to the constant: `@ProcessingGroup(DrivenAdapterConfiguration.TICKET_VIEW)`.
- Name the configurer bean after the layer and make it replaceable in tests:
  `@ConditionalOnMissingBean(name = "drivenAdapterProcessingGroups")`.
- Never call `anyProcessingGroup()` in production configuration. Two layers both calling it fail at startup, and it would give a new group a delivery nobody chose.
  Because every group is named, a new group without a configured delivery fails at startup: that is intended.

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
    DomainConfiguration.class,
    DrivenAdapterConfiguration.class,
    DrivingAdapterConfiguration.class,
    InfrastructureConfiguration.class,
    UseCaseConfiguration.class
})
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
```

```java
@EnableSMD
@Configuration
public class DomainConfiguration {
}
```

```java
@EnableSMD
@Configuration
@ComponentScan
public class UseCaseConfiguration {

    public static final String TICKET_ESCALATION = "ticket-escalation";

    @Bean
    @ConditionalOnMissingBean(name = "useCaseProcessingGroups")
    public ProcessingGroupsConfigurer useCaseProcessingGroups(DurableDelivery durableDelivery) {
        return groups -> groups
            .processingGroup(TICKET_ESCALATION).medium(durableDelivery.dispatcher("ticket-service." + TICKET_ESCALATION));
    }
}
```

```java
@EnableSMD
@Configuration
@ComponentScan
public class DrivenAdapterConfiguration {

    public static final String TICKET_VIEW = "ticket-view";
    public static final String TICKET_LOG = "ticket-log";
    public static final String TICKET_ACTIVITY = "ticket-activity";
    public static final String CUSTOMER_WEBHOOKS = "customer-webhooks";

    @Bean
    @ConditionalOnMissingBean(name = "drivenAdapterProcessingGroups")
    public ProcessingGroupsConfigurer drivenAdapterProcessingGroups(EventStore eventStore, DurableDelivery durableDelivery) {
        return groups -> groups
            .processingGroup(TICKET_VIEW).sync()
            .processingGroup(TICKET_LOG).async().await()
            .processingGroup(TICKET_ACTIVITY).source(eventStore.outlet())
            .processingGroup(CUSTOMER_WEBHOOKS).medium(durableDelivery.dispatcher("ticket-service." + CUSTOMER_WEBHOOKS));
    }

    @Bean
    public EventTypeResolver eventTypeResolver() {
        return new StaticEventTypeResolver(Map.of(
            "ticket.opened.v1", TicketOpenedEvent.class,
            "ticket.assigned.v1", TicketAssignedEvent.class,
            "ticket.resolved.v1", TicketResolvedEvent.class
        ));
    }
}
```

```java
@Configuration
@ComponentScan
public class DrivingAdapterConfiguration {
}
```

A layer without event handlers declares no configurer. When no layer declares one, SMD delivers every group synchronously.
