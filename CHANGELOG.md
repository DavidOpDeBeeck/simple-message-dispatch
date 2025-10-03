# Changelog

## [0.0.3]

- Compile with Java 25 due to usage of `ScopedValue`.
- Introduced interceptors for command, query, and event bus to enable cross-cutting concerns.
- Metadata is now partially reused/copied downstream (e.g. when a command handler publishes an event, the metadata of the command is reused for the event). This mechanism will evolve as metadata is
  expanded.
- Added `@ConditionalOnMissingBean` to `ObjectCreator`, `PrincipalProvider`, and `DatetimeProvider` to allow applications to override them if needed.
- `Principal` and `Datetime` can now be correctly stubbed during testing.

## [0.0.2]

- Package-based locators now only scan the explicitly provided packages via the `@EnableSMD` annotation.
- If no packages are specified with `@EnableSMD`, the package of the annotated class is used by default.
- When a wrapper type is used as the return type for a command or query, the handler must return the primitive type. If `null` is a valid case, the type should be wrapped using `Optional`.

## [0.0.1]

- Initial version.