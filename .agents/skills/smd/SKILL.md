---
name: smd
description: Builds Spring Boot applications on Simple Message Dispatch (SMD, app.dodb:smd-spring-boot-starter) - commands, queries, events, handlers, processing groups, event-sourced aggregates, event delivery, metadata and principals, stored event names and upcasters, SMD tests and SMD errors. Use before adding or changing any of these, before choosing how a processing group delivers its events, and to interpret an SMD error.
---

# SMD

How to build a Spring Boot application with SMD, split so only what a task needs is read. It describes the SMD
release of the tag it was copied from, with Java 25 and Spring Boot 4.

- **Precedence:** where the application's `AGENTS.md` or `CLAUDE.md` contradicts this skill, the application wins.
- **Version:** SMD is experimental and the `0.0.x` line can break between versions. When the application records which
  SMD tag this skill was copied from and its build uses another SMD version, say so before relying on this skill. To
  upgrade SMD, read the changelog, then replace this skill with the `.agents/skills/smd` of the new version's tag:
  https://github.com/DavidOpDeBeeck/simple-message-dispatch/blob/main/CHANGELOG.md

Read this file before your first change to SMD code, [3](references/structure.md#3-application-structure) for where
code goes and [4](references/decisions.md#4-decisions) before choosing a message, aggregate or event delivery. Find
the rest through the task index; read only the sections it points to.

| Task                                                   | Read                                                                                                                                                                                                                           |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Start a new application                                | [2](references/setup.md#2-setup), [3](references/structure.md#3-application-structure), [11.1](references/playbooks.md#111-start-a-new-application)                                                                            |
| Place code in packages and configuration classes       | [3](references/structure.md#3-application-structure)                                                                                                                                                                           |
| Add a command, query, or event                         | [4.1](references/decisions.md#41-command-query-or-event), [6](references/messages-and-aggregates.md#6-messages)                                                                                                                |
| Add a command to an aggregate                          | [7](references/messages-and-aggregates.md#7-event-sourced-aggregates), [10](references/stored-events.md#10-stored-events-type-names-and-evolution), [11.2](references/playbooks.md#112-add-a-command-to-an-aggregate)          |
| Change state with rules or history                     | [4.2](references/decisions.md#42-aggregate-or-plain-command-handler), [7](references/messages-and-aggregates.md#7-event-sourced-aggregates)                                                                                    |
| Change state without rules or history                  | [4.2](references/decisions.md#42-aggregate-or-plain-command-handler), [11.3](references/playbooks.md#113-add-a-plain-command-handler)                                                                                          |
| Add a query or a read model                            | [4.3](references/decisions.md#43-event-delivery), [8](references/event-handlers.md#8-event-handlers-and-processing-groups), [11.4](references/playbooks.md#114-add-a-query-or-projection)                                      |
| React to an event                                      | [4.3](references/decisions.md#43-event-delivery), [4.4](references/decisions.md#44-processing-groups), [8](references/event-handlers.md#8-event-handlers-and-processing-groups)                                                 |
| Call an external system                                | [4.3](references/decisions.md#43-event-delivery), [8.3](references/event-handlers.md#83-idempotency), [11.5](references/playbooks.md#115-add-an-external-side-effect)                                                          |
| Send a command in reaction to an event                 | [4.3](references/decisions.md#43-event-delivery), [8.3](references/event-handlers.md#83-idempotency), [11.6](references/playbooks.md#116-add-a-policy-that-reacts-with-a-command)                                              |
| Exchange events with another application               | [10](references/stored-events.md#10-stored-events-type-names-and-evolution), [11.7](references/playbooks.md#117-publish-to-or-consume-from-a-broker)                                                                           |
| Know who acted, or carry tenant or correlation context | [4.5](references/decisions.md#45-payload-principal-property-or-transient-property), [9](references/metadata.md#9-metadata)                                                                                                     |
| Rename or reshape an event                             | [10](references/stored-events.md#10-stored-events-type-names-and-evolution), [11.8](references/playbooks.md#118-rename-or-reshape-an-event)                                                                                    |
| Rename or remove a processing group                    | [4.4](references/decisions.md#44-processing-groups), [11.9](references/playbooks.md#119-rename-or-remove-a-processing-group)                                                                                                   |
| Write or fix a test                                    | [12](references/testing.md#12-testing)                                                                                                                                                                                         |
| Interpret an SMD error                                 | [13](references/errors.md#13-errors)                                                                                                                                                                                           |
| Review a design for mistakes                           | [14](references/decisions.md#14-anti-patterns)                                                                                                                                                                                 |
| Finish a change                                        | [15](#15-checklist)                                                                                                                                                                                                            |

## 1. Mental Model

SMD dispatches three kinds of message. Each is a Java record that implements one marker interface:

| Message      | Means                                  | Sent through     | Handled by                                           | Handlers per message |
|--------------|----------------------------------------|------------------|------------------------------------------------------|----------------------|
| `Command<R>` | "Change state", returns `R`            | `CommandGateway` | `@CommandHandler` method or `@AggregateCommandHandler` | Exactly one          |
| `Query<R>`   | "Read state", returns `R`              | `QueryGateway`   | `@QueryHandler` method                               | Exactly one          |
| `Event`      | "This happened", returns nothing       | `EventPublisher` | `@EventHandler` methods in processing groups         | Zero or more         |

The other concepts:

- **Gateway**: the interface application code depends on to send a message. Inject only the one you need.
- **Message envelope**: SMD wraps every payload in a message with a `MessageId` and `Metadata`: the principal (who acted), a timestamp, the parent message ID, and string
  properties.
- **Lineage**: a message sent while a handler runs inherits the principal and properties of the message being handled, and records it as its parent.
- **Processing group**: a named set of event handlers that receives events through one delivery. The delivery decides the thread, the transaction, durability, ordering,
  and retries. Choosing a group's delivery is the most important design decision in an SMD application ([4.3](references/decisions.md#43-event-delivery)).
- **Medium**: an event channel with an inlet (`EventSink`, receives published events) and an outlet (`EventSource`, delivers them to subscribers). Built in: synchronous,
  async-await, fire-and-forget, the PostgreSQL **event store**, and PostgreSQL **durable dispatchers**.
- **Route**: an `EventRoute` moves events from a source to a sink without a handler, for example from a durable dispatcher to a broker.
- **Subject**: what an event is about, a type and an ID. Durable media deliver the events of one subject in order.
- **Aggregate**: an `@Aggregate` class whose state is rebuilt from its own stored events and that handles its own commands.
- **Interceptor**: code that wraps a bus, sink, or processing group, for cross-cutting concerns.

SMD does **not** provide, so do not invent APIs for:

- broker integrations (Kafka, RabbitMQ, Artemis, SQS): implement an `EventSink` and `EventSource` yourself ([11.7](references/playbooks.md#117-publish-to-or-consume-from-a-broker));
- sagas, deadlines, timers, or scheduled messages;
- aggregate snapshots;
- subscription or streaming queries;
- a projection rebuild or reset tool, metrics, or an HTTP admin endpoint;
- automatic schema migration: you copy SMD's SQL into your own migrations;
- automatic retry of a command that failed with `ConcurrentAggregateChangeException`;
- storage other than PostgreSQL 15+ for the event store and durable delivery.

## 5. Rules

Each rule ends with its reason.

**Messages**

- Make messages records and validate them in their compact constructor. Invalid messages then cannot exist.
- Name commands `<Verb><Subject>Command`, queries `Get<Thing>Query`, events `<Subject><PastTenseVerb>Event`. Agents and people then find them by name.
- Put only serializable value types in events: records, strings, numbers, `UUID`, `Instant`, enums, lists of these. Events are stored as JSON and must be readable for years.
- Never change a released event's shape or type name in place; version it ([10](references/stored-events.md#10-stored-events-type-names-and-evolution)). Stored events keep the shape they were
  written with.

**Handlers**

- Handler methods are public, take exactly one message parameter, and may add the context parameters of [8.1](references/event-handlers.md#81-handler-parameters). SMD rejects other shapes at startup.
- Handler classes are public too. Startup does not check the class, and a package-private handler class fails at its first dispatch with `IllegalAccessException`.
- Command and query handlers return exactly the message's result type `R`; event handlers return `void`. A boxed `R` must be returned as its primitive: a handler of
  `Command<Integer>` returns `int`, of `Command<Boolean>` returns `boolean`, and of `Command<Void>` returns `void`. Startup validation compares them.
- Handler classes are Spring beans (`@Component`, `@Repository`, or `@Bean`) inside an `@EnableSMD` layer. Aggregates are the exception: they are not beans. A handler
  outside the `@EnableSMD` packages is not found, and sending its message fails with `No command handler found` at dispatch, not at startup.
- Exactly one handler per command or query type. Two handlers fail at startup, also when one takes a supertype of the other's message, and also a `@CommandHandler`
  next to an `@AggregateCommandHandler` for the same command.
- Inject collaborators of plain handlers through the constructor, not as method parameters. Only aggregate command handlers take collaborator parameters.
- One command changes at most one aggregate. To change another, let a policy react to the event and send a second command. Each aggregate then stays consistent on
  its own, and the second change is retried on its own.
- Do not send a command from a command handler. It runs in the same transaction and couples two changes. React to the event instead.
- Command handlers may send queries, and aggregates may take collaborators that read.
- Read who acted from the `Principal` parameter, never from a payload field.
- Let exceptions propagate. SMD uses them to roll back, to retry durable deliveries, and to report failures. Never catch and swallow inside a handler to make it pass.

**Transactions**

- Every command, query, and publication through the gateways runs in a Spring transaction on the calling thread. `sync()` handlers, the event store's inlet, and
  durable dispatcher inlets join it. Everything commits or rolls back together.
- The event store and durable dispatchers write when the outermost transaction commits, after the handlers and `sync()` groups ran. Their failures, such as an unmapped
  event type or `ConcurrentAggregateChangeException`, therefore surface at the end of `send(...)` or `publish(...)`, and a nested `send(...)` cannot catch them.
- Durable groups handle events later, in batches, each batch in its own transaction together with the group's progress.
- Never start your own transaction around a gateway call to bundle several commands. One command is one unit of work.

## 15. Checklist

Before calling an SMD change done, confirm:

- [ ] Every new message is a record in the right `drivingport` subpackage and validates itself.
- [ ] Every new event has a versioned type name in the `StaticEventTypeResolver`; no released event changed shape without a new version and an upcaster.
- [ ] Every new processing group has a constant and an explicit delivery in its layer's configuration, chosen with [4.3](references/decisions.md#43-event-delivery).
- [ ] Every handler on the event store or a durable dispatcher is idempotent.
- [ ] No external call happens in a command handler or a `sync()` group.
- [ ] Each command changes at most one aggregate, and who acted comes from the principal.
- [ ] No processing group, dispatcher, route, or aggregate name was renamed without the steps of [11.9](references/playbooks.md#119-rename-or-remove-a-processing-group).
- [ ] New SMD tables or schema changes are in a Flyway migration.
- [ ] Tests cover the change at the boundary of [12](references/testing.md#12-testing), and the application's whole build passes, integration and
      acceptance tests included.
- [ ] The checks the application's `AGENTS.md` or `CLAUDE.md` adds for SMD changes are done.
