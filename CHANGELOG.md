# Changelog

## [Unreleased]

### Subjects

- **Breaking:** a subject is now a type and an ID, so subjects of different types with the same ID are kept apart. `@SubjectId` requires the subject type, as in
  `@SubjectId("invoice") UUID invoiceId`, and `EventMessage.subjectId()` is now `subject()`, an `Optional<Subject>`. See
  [Identify Event Subjects](docs/event-store.md#identify-event-subjects).
- **Breaking:** delivery metadata no longer carries the subject (`subjectId`); read it from `EventMessage.subject()` or the event. `smd_dispatcher_queue` adds a
  `subject_type` column.

### Event Sourcing

- Added event-sourced aggregates to `smd-event-store` (`app.dodb.smd.eventstore.aggregate`). Annotate the class with `@Aggregate`, its identity with `@AggregateId`, its
  command handlers with `@AggregateCommandHandler`, and its state changes with `@EventSourcingHandler`; later commands select their aggregate with `@TargetAggregateId`.
  Each command replays the aggregate's stream and appends the events it applies with the next version; a concurrent change fails with `ConcurrentAggregateChangeException`.
  An aggregate's events have the aggregate as their subject. Command handlers can take collaborators, resolved through the `ObjectCreator`.
  See [Event-Sourced Aggregates](docs/event-store.md#event-sourced-aggregates).
- The Spring Boot starter registers the aggregates in the `@EnableSMD` packages when `smd.event-store.enabled=true`. Without Spring, use `EventSourcedAggregates` and
  `PackageBasedAggregateLocator`.
- Added the `smd-event-store-test` module with `AggregateTestFixture` for given-when-then aggregate tests. `smd-spring-boot-starter-test` exposes it.
  See [Test Aggregates](docs/event-store.md#test-aggregates).
- **Breaking:** the event-store schema changed, with no upgrade script: events store `event_id`, `subject_type`, `subject_id`, `subject_version`, `payload`, `metadata`,
  `occurred_at`, and `global_position`, and event sequences are keyed by subject type and ID. See [Store Model](docs/event-store.md#store-model). The Java API follows:
    - `EventStorage` appends a `NewEvent` and loads `StoredEvent`s instead of `SerializedEvent`s, and adds `loadSubjectStream(subject)`.
  - `EventSubjectSequenceStore`, `EventSubjectSequence`, and `EventSubjectSequenceStatus` are now `EventSequenceStore`, `EventSequence`, and `EventSequenceStatus`. Their
    methods take a sealed `EventSequenceId` (`Global` or `OfSubject`) instead of separate global and subject variants.
    - The sequence types, `TokenStore`, `TokenState`, `TokenStoreException`, and `EventStoreSource` (now public) moved to `app.dodb.smd.eventstore.processing`; the
      `sequence` package is gone.
  - `lastProcessedSequenceNumber()` is now `lastProcessedPosition()`, and `gapSequenceNumber()` is `gapPosition()`.

### Metadata

- **Breaking:** `Metadata.properties()` returns `MetadataProperties` instead of `Map<String, String>`; build them with `MetadataProperties.builder()`. The JSON form is unchanged.
- **Breaking:** `Metadata` adds `transientProperties()`, which are never serialized or inherited; `property(name)` reads them first. Create metadata with `Metadata.at(timestamp)`
  and the `with...` methods; the other constructors, the static factories, `Metadata.and(...)`, and `Message.andMetadata(...)` are removed.
- The gateways accept `metadata -> ...` to change the generated metadata, such as `publish(event, metadata -> metadata.withProperty("customerId", id))`.
- **Breaking:** delivery metadata is now transient, so messages a handler sends no longer inherit it: `smd.processingId` and `smd.errorCount` (constants in
  `ProcessingMetadata`), and the event store's `smd.globalPosition`, which replaces `sequenceNumber` (constant in `EventStoreMetadata`).

### Serialization

- **Breaking:** event serialization moved from `smd-event-store` to the new `smd-serialization` module, in `app.dodb.smd.serialization` and its `jackson` subpackage.
  `smd-event-store` exposes the module, so only imports change.
- **Breaking:** `SerializedEvent` drops its `sequenceNumber` and its constructor without a subject, and its `subjectId` is now `subject`. `EventTypeResolver` returns `Optional` instead of throwing
  `EventTypeResolutionException`, which is removed.
- Added `EventUpcaster` to read events stored under an older type name. Spring Boot passes every `EventUpcaster` bean to the default serializer.
  See [Evolve Stored Events](docs/event-store.md#evolve-stored-events).

### Event Delivery

- Added the `smd-durable-delivery` module: a dispatcher that stores events in the publisher's transaction and delivers them at least once and in order per subject, within one
  application or through a broker. Spring Boot creates it when `smd.durable-delivery.enabled=true`. See [Durable Delivery](docs/durable-delivery.md).
- Added `EventRoute`, which moves events from a source into a sink. Declare routes on `EventBusSpec` or as Spring beans. A bus no longer needs processing groups.
  See [Route Between Media](docs/core-api.md#route-between-media).
- **Breaking:** `medium(medium)` on a processing group sends the medium only the events its groups handle, instead of every published event.
- **Breaking:** `EventSubscriber.processingGroup()` is now `name()`.
- `EventSelector.eventType` accepts several event types, and `EventSelector.eventPackage(Package)` selects a package and its subpackages.
- **Breaking:** `ConnectionProvider` moved to `app.dodb.smd.api.framework`, `RetryBackoffStrategy` to `app.dodb.smd.api.event.delivery`, and `SpringConnectionProvider` to
  `app.dodb.smd.spring`. Only imports change.

### Handlers

- Command, query, and processing-group registries hold any handler behaviour and name ambiguous handlers in their errors. The locators add `combine(locators)`, and Spring Boot
  combines every locator bean of each kind.
- **Breaking:** `ProcessingGroupRegistry.combine(...)` and `EventHandlerRegistry.combine(...)` are now `and(...)`.
- **Breaking:** `app.dodb.smd.api.utils.parameterstrategy` is now `app.dodb.smd.api.utils.handlerstrategy`, and validates the whole handler. Public and return type errors
  now start with `Invalid handler:`.

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
