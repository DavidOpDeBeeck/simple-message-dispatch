# SMD: Decisions

## Contents

- [4.1 Command, query, or event?](#41-command-query-or-event)
- [4.2 Aggregate or plain command handler?](#42-aggregate-or-plain-command-handler)
- [4.3 Event delivery](#43-event-delivery)
- [4.4 Processing groups](#44-processing-groups)
- [4.5 Payload, principal, property, or transient property?](#45-payload-principal-property-or-transient-property)
- [4.6 Interceptor or not?](#46-interceptor-or-not)
- [14. Anti-Patterns](#14-anti-patterns)

## 4. Decisions

### 4.1 Command, query, or event?

| The caller...                                                           | Use                                                            |
|-------------------------------------------------------------------------|----------------------------------------------------------------|
| asks the application to change state, and the change may be rejected   | `Command<R>`                                                   |
| asks for data and changes nothing                                       | `Query<R>`                                                     |
| needs to tell others that something already happened                    | `Event`, published by the handler that made the change         |

- A command returns only what the caller needs to continue, usually the ID of what it created or changed. To show the result, the caller then sends a query.
- A query returns a DTO from `drivingport.transferobject`, an `Optional` of one, or a `List` of them. Never return an aggregate, an entity, or a mutable object.
- Events are facts in the past tense. Publish them from command handling, never from a controller or a query handler.
- Publish an event even when no handler exists yet, if other parts of the system will plausibly care. Events are cheap and the event store keeps them.

### 4.2 Aggregate or plain command handler?

| The state...                                                                         | Use                                                                         |
|--------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| has rules or a lifecycle, so a command can be rejected because of earlier events     | An `@Aggregate` in `domain` ([7](messages-and-aggregates.md#7-event-sourced-aggregates))              |
| has a history that matters (audit, timeline, rebuildable projections)                | An `@Aggregate`                                                             |
| can be changed by concurrent commands that must not silently overwrite each other    | An `@Aggregate`: concurrent changes fail with `ConcurrentAggregateChangeException` |
| has no rules and no history worth keeping, such as a read position or a preference   | A `@CommandHandler` in `usecase` that writes through a driven port          |
| is owned by another system                                                           | A `@CommandHandler` in `usecase` that calls a driven port                   |

Aggregates require `smd.event-store.enabled=true`. A plain command handler that changes state that matters to others publishes an event through an injected
`EventPublisher`.

### 4.3 Event delivery

Choose a delivery for every processing group by what its handlers do:

| The handlers...                                                                                                  | Configure                                                         |
|------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| update a read model that the caller must see as soon as the command returns                                      | `.sync()`                                                         |
| update a read model that may lag a little and that you may want to rebuild from history                          | `.source(eventStore.outlet())`                                    |
| call something outside the database (HTTP, email, webhook, language model, file) for every committed event       | `.medium(durableDelivery.dispatcher("<application>.<group>"))`    |
| decide and send a follow-up command that must eventually happen                                                  | `.medium(durableDelivery.dispatcher("<application>.<group>"))`    |
| consume events another application published through a broker                                                   | `.source(incoming.outlet())` of an incoming durable dispatcher ([11.7](playbooks.md#117-publish-to-or-consume-from-a-broker)) |
| do best-effort, in-memory work whose loss on a crash is acceptable, without delaying the publisher               | `.async().fireAndForget()`                                        |
| must run off the publishing thread and outside its transaction while the publisher still waits and sees failures | `.async().await()`                                                |
| must not run, for example temporarily or in a test                                                               | `.disabled()`                                                     |

What each delivery means:

| Delivery                | Runs on                  | Publisher waits | Handler failure                                  | Transaction                                             | Survives a crash | Order                       |
|-------------------------|--------------------------|-----------------|--------------------------------------------------|---------------------------------------------------------|------------------|-----------------------------|
| `sync()`                | Publishing thread        | Yes             | Fails the publisher; the command rolls back      | The publisher's                                         | Commits with it  | Publication order           |
| `async().await()`       | Virtual threads          | Yes             | Fails the publisher                              | None inherited; worker side effects do not roll back    | No               | Sequential within the group |
| `async().fireAndForget()` | Virtual threads        | No              | Logged and lost                                  | None inherited                                          | No               | Not guaranteed              |
| Event store outlet      | Polling scheduler        | No              | Retried with backoff, then the subject is **abandoned** after `max-retries` | Handlers and progress commit together, in a new transaction | Yes | Per subject              |
| Durable dispatcher      | Polling workers          | No              | Retried with backoff **forever**; later events of its lane wait | Handlers and removal commit together, in a new transaction | Yes | Per subject              |

Event store outlet or durable dispatcher:

| Concern                     | Event store outlet                               | Durable dispatcher                                       |
|-----------------------------|--------------------------------------------------|----------------------------------------------------------|
| History                     | Keeps every event                                | Deletes an event once every subscriber handled it        |
| A new group starts at       | The first event ever stored                      | The first event stored after it first subscribed         |
| Persistent failure          | Abandons the subject after `max-retries`; others continue | Retries forever; blocks the events of the same lane |
| Latency                     | Polling delay, default `5s`                      | Idle backoff, at most `5s` by default                    |
| Best for                    | Projections, read models, timelines              | Side effects that must happen for every event, and only for new events |

Rules:

- Durable deliveries (event store and durable dispatcher) are **at least once**. Their handlers must be idempotent ([8.3](event-handlers.md#83-idempotency)).
- Give every durable-dispatcher group its own dispatcher. A failing group then delays no other group.
- Prefix dispatcher names with the application name: `ticket-service.customer-webhooks`. The name is stored in the database.
- Never call an external system from a `sync()` group or a command handler. The call cannot roll back, happens before the commit, and fails the user's command when the
  external system is down. Put it on a durable dispatcher.
- Never rely on `async().await()` for atomicity. A failure can roll back the publisher, but what the worker threads already did stays done.
- Do not use `fireAndForget()` for anything that must happen. Prefer a durable dispatcher.
- A read model that is queried right after the command that changed it must be `sync()`. With any other delivery the query can return stale data.

### 4.4 Processing groups

- Split groups by delivery and by failure isolation: handlers that must not wait on each other's failures belong in different groups.
- One group per read model or per external system is a good default. Name it after what it maintains, such as `ticket-view` or `customer-webhooks`.
- A class annotated `@ProcessingGroup` holds the handlers of one group. Handlers of one group may be spread over several classes.
- Within a group, handlers run one at a time in ascending `@EventHandler(order = ...)` order, and a failure stops the rest. Two handlers in one group for the same event,
  or for an event and its supertype, fail at startup unless their `order` values differ. Prefer one handler per event per group. Order between groups is not
  guaranteed: never let one group depend on another group having run.
- Group names are persistent identifiers: the event store keys its progress, and a durable dispatcher its subscriber, by group name. Renaming a group on the event store
  replays every event into the new name. Renaming a group on a durable dispatcher loses its backlog and leaves the old subscriber queueing events forever. Do not rename
  groups; if you must, follow [11.9](playbooks.md#119-rename-or-remove-a-processing-group).

### 4.5 Payload, principal, property, or transient property?

| The value...                                                                                  | Put it in                                         |
|-----------------------------------------------------------------------------------------------|---------------------------------------------------|
| is input the handler decides on                                                               | A payload field                                   |
| identifies who acted                                                                          | The principal, from your `PrincipalProvider` ([9.1](metadata.md#91-principal)) |
| is context the whole chain of messages needs, such as a tenant, customer, or correlation ID   | A metadata property                               |
| describes only this handling, such as a cache hit                                             | A transient metadata property                     |

Properties are stored with events, sent to brokers, and inherited by every message a handler sends. Transient properties are none of these. Never put the acting user in
a command field: callers could then act as anyone.

### 4.6 Interceptor or not?

Use an interceptor only for concerns that apply to every message of a kind: logging, authorization checks on the principal, adding or validating metadata, or
diagnostics. Never put business decisions in an interceptor. SMD already installs transaction interceptors and, with an `OpenTelemetry` bean, tracing.

## 14. Anti-Patterns

| Do not                                                                   | Instead                                                                      |
|--------------------------------------------------------------------------|------------------------------------------------------------------------------|
| Put `userId` or `actor` in a command                                     | Read the `Principal` parameter                                               |
| Call HTTP, email, or a model from a command handler or `sync()` group    | Handle the event in a group on a durable dispatcher                          |
| Change two aggregates in one command                                     | One command, one aggregate; a policy sends the second command                |
| Send a command from a command handler                                    | Publish the event and react in a policy                                      |
| Query a read model on the event store outlet right after the command     | Make that read model `sync()`, or accept and handle the lag                  |
| Catch and log exceptions in a durable handler                            | Let them propagate so the event is retried                                   |
| Write non-idempotent `INSERT`s in a durable projection                   | Key on the event's `MessageId` with `ON CONFLICT DO NOTHING`                 |
| Change a released event record's fields                                  | New versioned type name and an `EventUpcaster`                               |
| Rely on class names as event type names                                  | `StaticEventTypeResolver` with versioned names                               |
| Set state in an `@AggregateCommandHandler`                               | Apply an event and set state in its `@EventSourcingHandler`                  |
| Use `anyProcessingGroup()` in production configuration                   | Name every group in its layer's configurer                                   |
| Share one durable dispatcher between groups                              | One dispatcher per group                                                     |
| Rename a processing group, dispatcher, or `@Aggregate` name casually     | Treat them as stored identifiers ([11.9](playbooks.md#119-rename-or-remove-a-processing-group)) |
| Use `async().await()` to make side effects transactional                 | A durable dispatcher, with idempotent handlers                               |
| Return aggregates or entities from queries                               | DTO records from `drivingport.transferobject`                                |
| Put secrets or personal data in metadata properties                      | Payload fields, or nowhere                                                   |
| Swallow the exception of `chain.proceed(...)` in an interceptor          | Let it propagate                                                             |
| Add `Thread.sleep` to tests of durable groups                            | Awaitility with bounded waits                                                |
