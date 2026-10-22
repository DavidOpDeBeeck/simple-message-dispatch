# Changelog

## [Unreleased]

- Added durable delivery in `smd-event-store` (`app.dodb.smd.eventstore.durable`). `DurableDelivery.outbox(name, sink)` stores events in the publishing transaction and forwards
  them to a `TransportSink`; `DurableDelivery.inbox(name, sources...)` stores each received event once and delivers it to every subscribed processing group. Events with a subject
  carry a position per outbox and subject, and the inbox delivers them in that order whatever order the broker uses. Durable delivery has its own tables in
  [durable-delivery-schema.sql](core/smd-event-store/src/main/resources/db/smd/durable-delivery-schema.sql). See [Durable Delivery](docs/durable-delivery.md).
- Added `smd-jms` with `JmsEventMedium`, a JMS 3.1 medium verified on ActiveMQ Artemis. Its sink and source also implement `TransportSink` and `TransportSource` for durable delivery.
  See [JMS and ActiveMQ](docs/jms.md).
- The Spring Boot starter creates a `DurableDelivery` with `smd.durable-delivery.enabled=true` and a `JmsConfig` with `smd.jms.enabled=true`.

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
