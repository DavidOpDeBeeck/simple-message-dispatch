# SMD: Errors

## Contents

Tables by area, each mapping a message fragment to its cause and fix: Handlers, Processing groups and routes, Subjects, Aggregates, Event types and serialization, Configuration and storage, Tests, Logged, not thrown.

## 13. Errors

Search this section for a fragment of the message. "Startup" errors are thrown while Spring creates the `CommandGateway`, `QueryGateway`, or `EventPublisher` bean;
"commit" errors at the end of the outermost `send(...)` or `publish(...)`.

**Handlers** (startup unless noted)

| Message contains                                                         | Cause                                                                                   | Fix                                                                     |
|--------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `Invalid handler: method must be public`                                 | Handler method or aggregate command handler not public                                  | Make it public                                                          |
| `method must include a parameter of type`                                | The parameter does not implement `Command`, `Query`, or `Event`                          | Add `implements Command<R>`, `Query<R>`, or `Event` to the record       |
| `method must only include one parameter of type`                         | Two message parameters, or two of `MessageId`, `Metadata`, `Principal`, `Instant`        | Keep one of each                                                        |
| `metadata value parameter must be annotated with @MetadataValue`         | A `String` parameter without `@MetadataValue`                                           | Annotate it, or move the value into the payload                         |
| `only parameters of type String can be annotated with @MetadataValue`    | `@MetadataValue` on a non-`String`                                                      | Take a `String` and parse it                                            |
| `unsupported parameter types found`                                      | A collaborator as a parameter of a plain handler                                        | Inject it through the constructor                                       |
| `return type mismatch`                                                   | Return type is not exactly `R`, a boxed `R` instead of its primitive, or an event handler returning a value | Return exactly `R`, `int` for `Integer`, `void` for events |
| `method must not be static`                                              | Static event sourcing or aggregate command handler                                      | Make it an instance method                                              |
| `no processingConfig group found`                                        | `@EventHandler` without `@ProcessingGroup` on the method or class                       | Add `@ProcessingGroup(<layer constant>)`                                |
| `Ambiguous command handlers found` / `Ambiguous query handlers found`    | Two handlers for one type, a supertype handler next to a subtype handler, or a `@CommandHandler` next to an aggregate's | Keep one handler per type |
| `Event handlers with same order found`                                   | Two handlers in one group for the same event or a supertype                             | Merge them, set distinct `order`, or use separate groups                |
| `No command handler found for` / `No query handler found for` (dispatch) | Handler outside the `@EnableSMD` packages, annotation missing, or an aggregate with the event store disabled | Fix the layer, the annotation, or `smd.event-store.enabled` |
| `IllegalAccessException ... cannot access a member of class` (dispatch)  | Handler class not public                                                                | Make the class public                                                   |
| `NoSuchBeanDefinitionException` for a handler class (dispatch)           | Handler class is not a Spring bean, or has two beans                                    | Register exactly one bean                                               |

**Processing groups and routes** (startup)

| Message contains                                         | Cause                                                                                  | Fix                                                                       |
|----------------------------------------------------------|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `Processing group '<g>' has no configuration`            | A `ProcessingGroupsConfigurer` exists and no configurer names group `g`                 | Add `g` with a delivery to its layer's configurer ([4.3](decisions.md#43-event-delivery)) |
| `are configured but not located`                         | A configured group has no handlers in the scanned packages: a typo, or a group of another layer | Fix the name, or configure the group in its own layer               |
| `Processing group '<g>' is already configured`           | Two configurers name the same group                                                     | Configure each group once, in its layer                                   |
| `Processing-group configuration '<g>' is already configured` | Group `g` got two deliveries; `g` is `anyProcessingGroup()` when two configurers call it | Name groups explicitly; give each one delivery                       |
| `has the name of another route or processing group`      | Route name equals another route or a group                                             | Use unique names                                                          |
| `cannot subscribe until they match` (lanes)              | `smd.durable-delivery.processing.lane-count` changed after the dispatcher was created  | Restore it, or follow the lane-count change procedure of the durable-delivery docs |

**Subjects** (publication)

| Message contains                                                     | Cause                                                              | Fix                                                        |
|----------------------------------------------------------------------|--------------------------------------------------------------------|------------------------------------------------------------|
| `exactly one @SubjectId method or record component may be declared`  | Several `@SubjectId`s on one event                                 | Keep one                                                   |
| `@SubjectId must resolve to a non-blank value`                       | The subject ID is blank; `null` means no subject                    | Set the ID before publishing                               |
| `a declared @SubjectId must resolve to the aggregate's type and ID`  | An aggregate's event declares a different subject type or ID       | Use the `@Aggregate` name as type and the aggregate ID      |

**Aggregates**

| Message contains                                                          | When     | Cause                                                              | Fix                                                        |
|---------------------------------------------------------------------------|----------|--------------------------------------------------------------------|------------------------------------------------------------|
| `a no-argument constructor is required`                                   | startup  | No no-argument constructor                                         | Add a private one                                          |
| `exactly one non-static @AggregateId field`                               | startup  | Zero or several `@AggregateId` fields on the class itself          | Declare exactly one                                        |
| `instead of @CommandHandler`                                              | startup  | `@CommandHandler` inside an `@Aggregate`                            | Use `@AggregateCommandHandler`                             |
| `an @AggregateCommandHandler constructor must create the aggregate`       | startup  | No creating constructor                                            | Add an `@AggregateCommandHandler` constructor              |
| `a creating command must not declare @TargetAggregateId`                  | startup  | Creating command targets an ID                                     | Remove the annotation                                      |
| `a creating command must return the @AggregateId`                         | startup  | Creating command's `R` differs from the `@AggregateId` type         | Use `Command<UUID>` for a `UUID` ID                         |
| `the command must declare @TargetAggregateId`                             | startup  | Changing command without `@TargetAggregateId`                       | Annotate the ID component                                  |
| `the @TargetAggregateId must have the type of the @AggregateId`           | startup  | Types differ, also `long` against `Long`                            | Use the same type; prefer `UUID` or `String` IDs           |
| `Duplicate aggregate type name`                                           | startup  | Two `@Aggregate`s with one name                                    | Unique names                                               |
| `Aggregate not found` (`AggregateNotFoundException`)                      | dispatch | No events for the ID: wrong ID, creation rolled back, created in an outer transaction not yet committed when using `doInNewTransaction`, or the `@Aggregate` name changed | Map to `404`; never rename aggregates      |
| `Aggregate was changed concurrently` (`ConcurrentAggregateChangeException`) | commit | Another transaction stored the same version first, or a second creation with an existing ID; never a change earlier in the same transaction | Map to `409` or resend; check existence before creating |
| `no events were applied, so there is no stream to store`                  | dispatch | Creating constructor applied no event                              | Apply the creation event                                   |
| `@AggregateId is null after applying the creation events`                 | dispatch | No `@EventSourcingHandler` assigns the ID                           | Assign it from the creation event                          |
| `events can only be applied while the command handler runs`               | dispatch | `AggregateEvents` used after the handler returned                   | Apply only inside the handler                              |

**Event types and serialization**

| Message contains                                       | When                 | Cause                                                                   | Fix                                                             |
|--------------------------------------------------------|----------------------|-------------------------------------------------------------------------|-----------------------------------------------------------------|
| `Cannot serialize event ... can not be resolved`       | publish (event store), commit (durable dispatcher) | Event class missing from the `StaticEventTypeResolver`, which matches exact classes | Map every published event class                     |
| `Failed to serialize event`                            | publish (event store), commit (durable dispatcher) | Payload or principal not serializable by Jackson                         | Use plain records and value types                               |
| `Cannot deserialize event ... event type ... can not be resolved` | processing, aggregate load | Stored type name no longer mapped, or a class renamed under the class-name default | Restore the mapping or add `EventUpcaster.rename` |
| `Failed to deserialize event`                          | processing, aggregate load | Stored JSON no longer fits the record                                | New versioned type name and `EventUpcaster.transform`           |
| `is mapped to event types`                             | startup              | One class under two names                                               | Map only the current name; upcast the old one                   |
| `Duplicate event upcasters` / `Event upcasters form a cycle` | startup        | Invalid upcaster chain                                                  | One upcaster per old type, no cycles                            |
| `is upcast by ... but can also be resolved`            | startup              | The old name is still mapped                                            | Remove the old name from the resolver                           |
| `produces event type ... which has no upcaster and can not be resolved` | startup | Upcaster target not mapped, or a typo                                   | Fix the target name                                             |
| `required a bean of type 'app.dodb.smd.serialization.EventSerializer'` | startup | No Spring Boot auto-configuration, so no `JsonMapper.Builder`         | Add `@EnableAutoConfiguration` to the context                   |

**Configuration and storage**

| Message contains                                                       | Cause                                                              | Fix                                                        |
|------------------------------------------------------------------------|--------------------------------------------------------------------|------------------------------------------------------------|
| `required a bean of type 'app.dodb.smd.api.command.CommandGateway'`    | No `@EnableSMD` in the context                                     | Add `@EnableSMD` to the layer configurations               |
| `required a bean of type 'javax.sql.DataSource'`                       | Event store or durable delivery enabled without a datasource       | Configure PostgreSQL                                       |
| `required a bean of type '...EventStore'` or `'...DurableDelivery'`    | A configurer needs the feature but it is not enabled               | Set `smd.event-store.enabled` or `smd.durable-delivery.enabled` |
| `Failed to append event`, `Failed to load`, `Failed to register dispatcher`, `Failed to store event in dispatcher` | SMD schema missing, or the database is down | Add the schema migration of [2](setup.md#2-setup) |
| `Event store ... must be` / `Retry backoff ... must be` / `Durable event dispatcher ... must be` | Invalid `smd.*` property value             | Use zero or more, and more than zero where the message says so |

**Tests**

| Message contains                                                 | Cause                                                                          | Fix                                                                 |
|------------------------------------------------------------------|--------------------------------------------------------------------------------|---------------------------------------------------------------------|
| `NullPointerException` after a stubbed gateway call              | Command or query not stubbed; stubs return `null`                              | `smd.stubCommand(...)` or `smd.stubQuery(...)` with an equal record |
| `SMD test scoped beans can only be used while a test is being executed` | A stub used outside the test thread, such as from an async or durable group | Make the layer's groups `sync()` in the test                         |
| `NoUniqueBeanDefinitionException` for `ProcessingGroupsConfigurer` | Two configurers in an `@EnableSMDStubs` context                               | Load one layer, or replace the configurers with one test bean       |
| `Missing aggregate collaborator`                                 | Aggregate collaborator not registered on the fixture                           | `AggregateTestFixture.builder(...).collaborator(Type.class, fake)`   |
| `an @EventSourcingHandler must assign the @AggregateId` (given)  | The given events do not start with the creation event                          | Start `given(...)` with it                                          |
| `but the command failed`                                         | The command threw; the cause holds the real exception                          | Fix the handler, or expect the exception                            |

Logged, not thrown: `Event processing failed: processingGroup=... retry=<n>/<max>` and `Event abandoned (retries exhausted)` for event-store groups, `Durable delivery
failed, retrying in` for durable dispatchers, and `Unhandled event error` for fire-and-forget groups. An abandoned subject stays abandoned for that group; later events of
the subject are skipped.
