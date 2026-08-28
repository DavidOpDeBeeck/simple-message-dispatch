# AGENTS.md

## Design Priorities

SMD is a lightweight Java library. Keep usage simple and correctness-sensitive behavior explicit.

Prefer, in order:

1. Observable correctness.
2. A small public API.
3. Framework-independent core code.
4. Early validation and useful errors.
5. Direct code.
6. Deduplication.

Command, query, and event code intentionally stays symmetrical. Share code only for a common invariant or stable boundary.

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `core/smd-api` | Messages, buses, handlers, channels, metadata, and framework ports |
| `core/smd-event-store` | PostgreSQL storage, polling, ordering, retries, and tokens |
| `core/smd-test` | Framework-independent test support |
| `framework/smd-spring-boot-starter` | Spring Boot wiring and autoconfiguration |
| `framework/smd-spring-boot-starter-test` | Spring test support |

Core modules must not depend on Spring. Define the smallest required core contract, then adapt it in the framework module. Keep classes near the domain concept they use.

## Code Design

- Use domain verbs such as `send`, `publish`, `handle`, `locate`, and `markProcessed`. Avoid vague `manager`, `util`, `process`, and `execute` names.
- Expose policy with clear factories and fluent specs. Defaults must be safe; incomplete configuration must fail rather than silently fall back.
- Keep interfaces narrow. Name implementations by role, never with an `Impl` suffix.
- Use records for immutable data and results; use classes for stateful services, builders, adapters, channels, and lifecycle owners.
- Preserve domain and generic types. Do not reduce IDs, messages, metadata, or results to strings and maps for convenience.
- Use sealed results when callers must distinguish several outcomes; use booleans for predicates.
- Keep visibility limited to the real extension surface.
- Extract an abstraction only when it names a stable concept or replaceable boundary.

Create valid objects. Reject null dependencies in constructors, validate values in their owning types, validate handlers during discovery, detect registry ambiguity, and validate completed specs before use.

Errors at user-controlled boundaries must state what is invalid, what was expected, and the relevant method, type, processing group, message ID, or value.

Contain reflection, unchecked casts, serialization, SQL, and nullable storage values at their boundaries. Return domain types to core code.

## Correctness Semantics

Threads, metadata, ordering, transactions, and failure propagation are API behavior. For dispatch changes, establish:

- the executing thread and visible metadata lineage;
- the transaction owning each state change;
- what commits or rolls back after partial failure;
- listener and processing-group isolation;
- ordering guarantees and concurrent claim ownership.

Restore metadata on worker threads. Preserve interruption, cancel owned work where appropriate, and use thread-safe state across threads.

Do not rearrange handler side effects, deferred work, token advancement, retries, or rollback recovery without proving the transaction semantics remain correct.

Preserve handler exceptions. Wrap failures only at technology boundaries and add domain context. Keep one primary concurrent failure and attach others as suppressed exceptions.

Comment only when transaction, locking, concurrency, lifecycle, or ownership intent is not clear from the code.

## Implementation Style

- Write methods top to bottom: derive values, handle guards, perform the operation, translate boundary failures, return the result.
- Use `var` when the type is obvious. Use explicit types when they clarify generics, reflection, JDBC, or examples.
- Use streams for transformations and searches; use loops for mutation, resources, state machines, and early exits.
- Extract helpers to name a step or isolate a boundary, not just to shorten a method.
- Keep JDBC direct and auditable: nearby SQL, prepared statements, explicit mapping, try-with-resources, row-count checks, and contextual exceptions.
- Keep Spring bean methods as explicit assembly code; keep policy in core specs.
- Log operator-relevant state changes once, with useful identifiers.
- Prefer names and types over comments. Add Javadoc only for public semantics the API cannot express.

Follow `.editorconfig`: four spaces, same-line braces, one top-level type per file, no wildcard imports, and no routine `final` on parameters or locals. Avoid unrelated formatting.

## Tests

Test caller-visible behavior and likely regressions, not private interactions.

- Name tests `operation_condition_expectedOutcome`.
- Separate arrange, act, and assert with blank lines; prefer one meaningful action.
- Use real objects with small handwritten fakes, lambdas, or records instead of mocks.
- Use AssertJ. Assert results, side effects, failure identity and context, guaranteed order, and forbidden effects.
- Keep scenario fixtures beside the test. Share only reusable domain fixtures.
- Cover distinct validation cases separately.
- Make async tests deterministic with latches, atomics, thread-safe collections, Awaitility, and bounded waits. Do not use arbitrary sleeps.
- Reserve integration tests for Spring wiring, transactions, JDBC, PostgreSQL behavior, and Testcontainers concurrency.

Treat `smd-test` as a public API: keep stubbing explicit, captured outcomes ordered, mutable state contained, and reset behavior reliable.

## Change Workflow

Before coding, identify the owning module, smallest public contract, safe defaults, construction-time invariants, execution semantics, and required test boundary.

Implement one semantic slice. Check parallel command/query/event paths, add focused tests, and update documentation when public behavior or configuration changes.

## Commands

```bash
./gradlew test
./gradlew integrationTest  # Event-store tests require Docker
./gradlew check
./gradlew build

./gradlew :smd-api:test --tests "app.dodb.smd.api.command.AnnotatedCommandHandlerTest"
./gradlew :smd-spring-boot-starter:integrationTest --tests "app.dodb.smd.spring.event.EventBusIntegrationTest"

./gradlew publishToMavenLocal
```