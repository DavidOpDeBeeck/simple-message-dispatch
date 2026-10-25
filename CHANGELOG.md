# Changelog

## [Unreleased]

- **Breaking:** `Metadata.properties()` returns `MetadataProperties` instead of `Map<String, String>`. It offers `find`, `get`, `contains`, `names`, `with`, `without`,
  `inheritedBy`, and `asMap()`; `Metadata` adds `property(name)` and `withProperty(name, value)`. The `Map` constructor and `Metadata.withProperties(Map)` remain, and the JSON form of
  metadata is unchanged.
- Added `MetadataInheritance`, which decides which properties a message sent by a handler inherits from the message it received. The default,
  `MetadataInheritance.excludingSmdProperties()`, inherits everything except properties starting with `smd.`; `excludingPrefix(...)`, `excludingProperties(...)`, and `and(...)` build
  other policies. Set it with `metadataInheritance(...)` on the bus specs, or with a `MetadataInheritance` bean in Spring Boot.
- **Breaking:** the delivery metadata of event-store handlers moved under `smd.`: `processingId`, `sequenceNumber`, `subjectId`, and `errorCount` are now
  `smd.processingId`, `smd.sequenceNumber`, `smd.subjectId`, and `smd.errorCount`, with constants in `app.dodb.smd.api.event.delivery.ProcessingMetadata`. Messages a handler sends
  no longer inherit them; before, a subjectless event sent while handling another event could carry that event's `subjectId`.
- `MetadataFactory` accepts a `MetadataInheritance` as a third constructor argument; the two-argument constructor uses `excludingSmdProperties()`.
- **Breaking:** event serialization moved from `smd-event-store` to the new `smd-serialization` module. `EventSerializer`, `EventTypeResolver`, `ClassNameEventTypeResolver`,
  `StaticEventTypeResolver`, `EventTypeResolutionException`, and `SerializedEvent` (from `app.dodb.smd.eventstore.storage`) are in `app.dodb.smd.serialization`;
  `JacksonEventSerializer` and `SMDJacksonModule` are in `app.dodb.smd.serialization.jackson`. `smd-event-store` exposes the module, so only imports change.
- **Breaking:** `ConnectionProvider` moved from `app.dodb.smd.eventstore.storage` to `app.dodb.smd.api.framework`, `RetryBackoffStrategy` from `app.dodb.smd.eventstore` to
  `app.dodb.smd.api.event.delivery`, and `SpringConnectionProvider` from `app.dodb.smd.spring.eventstore` to `app.dodb.smd.spring`. Only imports change.
- `EventSelector.eventType` accepts several event types, and `EventSelector.eventPackage(Package)` selects events in a package and its subpackages.
- Added `EventRoute`, which moves events from a source into a sink. Declare routes with `route(name, source, sink)` or `routes(...)` on `EventBusSpec`, or as `EventRoute` beans in
  Spring Boot. The bus subscribes them after its processing groups and sends through the sink interceptors; `EventSinkContext.route()` names the route, and
  tracing records it as `messaging.smd.route`. A bus no longer needs processing groups. See [Route Between Media](docs/core-api.md#route-between-media).
- **Breaking:** `EventSubscriber.processingGroup()` is now `EventSubscriber.name()`, because routes subscribe as well as processing groups.
- Added event-sourced aggregates in `smd-event-store` (`app.dodb.smd.eventstore.aggregate`): `@Aggregate`, `@AggregateId`, `@TargetAggregateId`, `@EventSourcingHandler`, and
  `AggregateEvents`. A `@CommandHandler` constructor creates the aggregate and its command returns the aggregate ID; `@CommandHandler` methods handle commands that select an
  existing aggregate with `@TargetAggregateId`, and a no-argument constructor replays it. `EventStore.aggregateCommandHandlers(...)` loads and replays an aggregate's stream for
  each command and appends the applied events with a per-aggregate version; a concurrent change of the same version fails with `ConcurrentAggregateChangeException`.
  Applied events need no `@SubjectId`: the store records the aggregate ID and delivers it as the subject. The schema adds `aggregate_type`, `aggregate_version`, and the
  `smd_event_store_aggregate_stream` index. `EventStorage` adds `append(event, revision)` and `loadAggregateStream(...)`. See
  [Event-Sourced Aggregates](docs/event-store.md#event-sourced-aggregates).
- **Breaking:** the event store uses domain-driven design names, and SMD maps them to messages: `aggregate_id` is the subject and `global_position` the delivered sequence
  number. See [Store Model](docs/event-store.md#store-model). Existing databases need the upgrade in [Schema and Upgrades](docs/event-store.md#schema-and-upgrades).
  - `smd_event_store` renames `message_id`, `subject_id`, `serialized_payload`, `serialized_metadata`, `created_at`, and `sequence_number` to `event_id`, `aggregate_id`,
    `payload`, `metadata`, `occurred_at`, and `global_position`. `smd_token_store` and `smd_event_sequence_state` rename `last_processed_sequence_number` to
    `last_processed_position`, `gap_sequence_number` to `gap_position`, and `subject_id` to `aggregate_id`.
  - `EventStorage` appends a `NewEvent` with `append(...)` and loads `StoredEvent`s with `load(afterGlobalPosition, limit)`, instead of storing and loading `SerializedEvent`s.
    `InMemoryEventStorage` is seeded with `StoredEvent`s.
  - `EventSubjectSequenceStore`, `EventSubjectSequence`, and `EventSubjectSequenceStatus` are now `EventSequenceStore`, `EventSequence`, and `EventSequenceStatus`, with
    `PostgresEventSequenceStore` and `InMemoryEventSequenceStore`. `claimSubject(...)` is now `claimAggregate(...)`, and `EventSequenceState.subjectId()` is `aggregateId()`.
  - `lastProcessedSequenceNumber()` is now `lastProcessedPosition()` on `Token`, `TokenState`, `EventSequence`, and `EventSequenceState`; `TokenState.gapSequenceNumber()` is
    `gapPosition()`.
- `CommandHandlerRegistry` holds any `CommandHandlerBehaviour`, which now exposes the handling constructor or method as `executable()`. `@CommandHandler` may annotate
  constructors, which only aggregates support; a handler class with one fails discovery. `PackageBasedCommandHandlerLocator` accepts a filter for handler classes.
- The Spring Boot starter registers the command handlers of `@Aggregate` classes in the `@EnableSMD` packages when the event store is enabled. The default
  `CommandHandlerLocator` bean is now `@ConditionalOnMissingBean`.

## [0.0.12] - 2026-10-19

- Added `smd-opentelemetry`: `MessageTracing` records each dispatch, each send to a sink, and each processing group's handling as an OpenTelemetry span, and carries the
  trace context in message metadata (`traceparent`). See [OpenTelemetry](docs/opentelemetry.md).
- The Spring Boot starter includes `smd-opentelemetry` and registers `MessageTracing` when an `OpenTelemetry` bean exists, outside the transactional interceptors. Set
  `smd.opentelemetry.enabled=false` to turn it off.
- The starter's transactional interceptors moved from `HIGHEST_PRECEDENCE` to `SMDAutoConfiguration.TRANSACTION_INTERCEPTOR_ORDER`, one step lower. An interceptor bean
  ordered at `HIGHEST_PRECEDENCE` now runs outside the transaction.
- Added `EventSinkInterceptor` and `ProcessingGroupInterceptor`, registered with `.sinkInterceptors(...)` and `.processingGroupInterceptors(...)` on the event bus, or as
  Spring beans. They replace the interceptor overloads of `ConcurrentEventDispatcher` and `FireAndForgetEventDispatcher` and `EventStoreConfig.Builder.interceptors(...)`,
  which are removed; register a `ProcessingGroupInterceptor` to run around event handling on any source.
- Renamed `EventInterceptor`, `EventInterceptorChain`, and `TransactionalEventInterceptor` to `EventBusInterceptor`, `EventBusInterceptorChain`, and
  `TransactionalEventBusInterceptor` in `app.dodb.smd.api.event.bus`.
- `EventSink` and `EventSource` require `delivery()`, naming the medium they write to and read from.
- `ProcessingGroupRegistry.EventHandlerRegistry` is no longer an `EventSubscriber`; `handlersFor(eventMessage)` returns the handlers for an event.
- Messages a handler sends inherit metadata changed by an interceptor before the handler received the message.

## [0.0.11] - 2026-09-12

- Added `EventSinkStub` and `EventSourceStub` to `smd-test` with ordered message capture and reset support. Renamed `EventChannelListenerStub` to `EventSubscriberStub`.
- Added `EventSelector` and `.selecting(...)` on sinks, sources, and media to filter events by type, metadata, or custom predicates.
- Replaced event channels with `EventSink`, `EventSource`, and `EventMedium` in `app.dodb.smd.api.event.delivery`. Configure outbound delivery with `.sinks(...)`, inbound with `.source(...)`, or both
  with `.medium(...)`.
- Renamed the built-in channels to `SynchronousEventDispatcher`, `ConcurrentEventDispatcher`, and `FireAndForgetEventDispatcher`.
- Replaced `EventStoreChannel` and `EventStoreChannelConfig` with `EventStore` and `EventStoreConfig` in `app.dodb.smd.eventstore`. Spring Boot automatically registers an enabled store's inlet as a sink.
- Moved persistence types to `eventstore.storage` and serializers to `eventstore.serialization`.
- Added ordered Spring `CommandBusSpecCustomizer`, `EventBusSpecCustomizer`, and `QueryBusSpecCustomizer` extension points.
- Added closeable `EventSubscription` handles and `EventMedium.send(...)` and `subscribe(...)` conveniences.
- Direct sends to the Spring-managed event store now work without an existing transaction.

When upgrading, update all SMD modules together and replace `.channel(...)` with `.medium(...)` or `.source(...)`. See [event delivery](docs/core-api.md#event-delivery) for the new contracts.
The PostgreSQL schema is unchanged from 0.0.10.

## [0.0.10] - 2026-08-30

- Events can declare a non-blank subject ID through `@SubjectId`; `EventMessage` captures and persists it, while events without a subject ID share a global sequence.
- Event-store processing now supports independent, ordered subjects per processing group, with per-subject retry, backoff, and abandonment state.
- Event channels now distinguish directly subscribable channels from channels attached through an `EventChannelBinding`; the event store is directly subscribable.
- Built-in event-store persistence is now explicitly PostgreSQL-specific through `PostgresEventStorage`, `PostgresTokenStore`, and `PostgresEventSubjectSequenceStore`.
- The bundled event-store schema now requires PostgreSQL 15 or newer and needs an explicit database migration when upgrading an existing installation.
- Added `StaticEventTypeResolver` for stable application-level event type mappings and processing metadata for stored-event delivery attempts.

## [0.0.9] - 2026-07-09

- Event-store serialization now uses Jackson 3 (`tools.jackson.databind.*`, `tools.jackson.core:jackson-databind`). Spring Boot customization now goes through `JsonMapperBuilderCustomizer`,
  `JacksonModule`, or a replacement `EventSerializer` instead of the former `eventObjectMapper` bean.
- Gradle wrapper was updated to 9.6.1.

## [0.0.8] - 2026-04-26

- Annotated handler parameter validation now supports multiple `@MetadataValue` `String` parameters and uses clearer shared validation rules.
- Annotated handler methods must now be public, with clearer validation errors for non-public methods.
- Async-await event handling now reports listener failures consistently and preserves suppressed failures when multiple listeners fail.
- Documentation is now split into focused guides under `docs/` instead of one large root README.
- Time-related metadata APIs now use the `time` naming consistently.
- Metadata properties are now immutable after creation.
- Async event handling now restores parent metadata scope so nested dispatch keeps principal, properties, and lineage across worker threads.
- Event interception now uses one consistent API.
- Event processing groups must now be configured explicitly when you provide custom group routing.
- Event-store polling can now be disabled cleanly through configuration.
- Event-store configuration now fails fast on invalid values.
- Event-store retry backoff configuration now uses `base-delay` for linear and exponential strategies and documents default delay values.
- Event-store serialization now supports custom event type names.
- Event-store Spring Boot beans now live in a dedicated event-store auto-configuration package.
- Event-store processing is more robust when handlers fail after producing side effects.
- Event-store processing is safer in multi-instance or multi-threaded setups because processing-group tokens are claimed before work starts.
- Requires-new Spring transaction deferred work now runs correctly.
- Spring Boot dependency declarations now distinguish framework APIs, Boot auto-configuration, starters, and test dependencies more clearly.
- Gradle wrapper was updated to 9.4.1.

## [0.0.7]

- Message metadata now includes `Instant` timestamps, `parentMessageId`, and custom properties.
- `DatetimeProvider` was renamed to `TimeProvider`.
- Message access now uses `xxx()` style accessors instead of `getXxx()`.
- Handlers can now read individual metadata properties with `@MetadataValue`.
- Command, query, and event handling can now run through transactional interceptors.
- Added the `smd-event-store` module for JDBC-backed event storage and polling-based event delivery.
- Spring Boot applications can now autoconfigure event-store and transaction support.
- Build configuration was refreshed and dependency versions were updated.

## [0.0.6]

- Event channels now support synchronous, async-awaiting, and fire-and-forget delivery modes.

## [0.0.5]

- Command, query, and event buses are now configured through `*BusSpec` builders.
- Event delivery can now be configured through `EventChannel`.
- Event handlers now require an explicit `@ProcessingGroup`.

## [0.0.4]

- Fixed logging for applications with multiple processing groups.

## [0.0.3]

- SMD now targets Java 25.
- Added interceptors for command, query, and event handling.
- Downstream messages now inherit metadata from the message that triggered them.
- Spring applications can now override key infrastructure beans more easily.
- Principal and time providers can now be stubbed correctly in tests.

## [0.0.2]

- Package scanning now stays within the packages configured through `@EnableSMD`.
- If no packages are configured, SMD now defaults to the package of the annotated class.
- Command and query handler return types are now validated more strictly; use `Optional` when `null` is a valid outcome.

## [0.0.1]

- Initial version.
