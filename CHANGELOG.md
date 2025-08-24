# Changelog

## [0.0.2]

- Package-based locators now only scan the explicitly provided packages via the `@EnableSMD` annotation.
- If no packages are specified with `@EnableSMD`, the package of the annotated class is used by default.
- When a wrapper type is used as the return type for a command or query, the handler must return the primitive type. If `null` is a valid case, the type should be wrapped using `Optional`.

## [0.0.1]

- Initial version.