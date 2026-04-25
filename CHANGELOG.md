# Changelog

## [Unreleased]

- Annotated handler parameter validation now supports multiple `@MetadataValue` `String` parameters and uses clearer shared validation rules.
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
