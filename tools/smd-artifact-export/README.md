# smd-artifact-export

The changeviz extractor for SMD applications. It writes the
[SMD Artifact](https://github.com/DavidOpDeBeeck/smd-flow/blob/claude/gracious-franklin-dc8f4b/docs/smd-artifact.md)
of one commit of a Spring Boot application on SMD, from what the running application registered and from the bytecode
of its handlers. The application needs no dependency, class or build change, so it also works on commits from before
the extractor existed.

## Use

Run it at the root of the application's checkout:

```bash
CHANGEVIZ_OUT=smd.json path/to/tools/smd-artifact-export/extract-smd.sh \
  --main-class app.dodb.mynden.MyndenApplication \
  --source-set acceptanceTest \
  --property spring.datasource.url=jdbc:tc:postgresql:18:///mynden
```

| Argument | |
|---|---|
| `--main-class` | Required. The `@SpringBootApplication` class. Its `main` method isn't run. |
| `--source-set` | The source set whose runtime classpath boots the application, usually the one the full-context tests use. Default `test`. |
| `--project` | The Gradle project of the application in a multi-project build. Default `:`. |
| `--property` | A Spring property for the boot, `key=value`, repeatable. Use it for what the tests set in `@SpringBootTest(properties = ...)`. |
| `--config-class` | An extra configuration class to boot with, repeatable. Use it for what the tests `@Import`. |
| `--app` | The Artifact's `app`. Default `spring.application.name`, else the main class's name. |
| `--out` | The file to write. Default `$CHANGEVIZ_OUT`. |

In `.changeviz.json`, with this repository checked out next to the application (`$SMD` is its path):

```json
{
  "extensions": {
    "smd": {
      "extract": "$SMD/tools/smd-artifact-export/extract-smd.sh --main-class app.dodb.mynden.MyndenApplication --source-set acceptanceTest --property spring.datasource.url=jdbc:tc:postgresql:18:///mynden",
      "compare": "smd"
    }
  }
}
```

It needs Java 21 or later, the application's `./gradlew`, and Docker when the application's database comes from
Testcontainers.

## How it works

`extract-smd.sh` runs the application's Gradle wrapper with `smd-artifact-export.init.gradle`. The init script adds two
tasks to the application's project: one compiles `src/` against the chosen source set's runtime classpath, the other
runs `SmdArtifactExport` on that classpath. It boots the application the way a `@SpringBootTest` does
(`server.port=0`), reads SMD, writes the Artifact and exits.

| Artifact field | Where it comes from |
|---|---|
| `handlers`, `commands` | The `CommandHandlerLocator` beans, so aggregates and plain `@CommandHandler` classes, as SMD registered them. |
| `publishes` | The events the handler method creates (`new` or `Event::new`) in its own code and in every application method it calls, followed transitively. |
| `groups`, `handles` | The `ProcessingGroupLocator` beans: each group's `@EventHandler` methods and the event types they take. |
| `medium`, `dispatcher` | The application's `ProcessingGroupsConfigurer` beans, run again against a fresh `ProcessingGroupsSpec`: each group's source and its `delivery()` name. Disabled groups are left out. |
| `sends` | The commands the group's event handlers create, followed the same way as `publishes`. |
| `eventTypes` | For each type a group handles, the concrete application events it covers, when that is more than itself. |
| `queries`, `reads` | The `QueryHandlerLocator` beans; `reads` as below. |
| `fingerprint` | A SHA-256 (first 16 hex characters) of the handler method's instructions and those of the methods and lambdas of its own class it reaches. Line numbers, variable names and lambda numbering are left out, so moving code or adding a comment doesn't change it. A group's fingerprint covers all its event handlers. |

SMD is used only through reflection, by class and member name. The exporter compiles against Spring alone, so one
exporter reads every SMD version an application's history uses. `medium` falls back to the dispatcher's class name on
SMD versions before `EventSource.delivery()`.

**Application code** is what comes from the class folders or jars of the main class and the handlers. Only calls into
it are followed; calls into libraries (the buses, JDBC, Spring) are not. Calls to an interface or abstract method are
not followed either, because the bytecode doesn't name the implementation: an event that only an implementation
behind an interface creates is missed.

**`reads`** is a heuristic, since SMD doesn't know which views a query uses. A query reads a group when the query's
code calls
- the group's own event-handler class, or an application interface that class implements (a repository that is
  also the projection, as in the ticket service), or
- an application type that the group's event handlers call a `save`, `insert`, `update`, `upsert`, `delete`,
  `remove`, `persist`, `store`, `put`, `add`, `create`, `increment`, `decrement`, `mark`, `append`, `record`,
  `clear`, `merge` or `write` method on.

A query that reads through a library type only (a `JdbcTemplate` or jOOQ `DSLContext` in the query handler itself)
gets no `reads`, rather than a guess.

## Example

`examples/ticket-service` is the ticket service from this repository, before and after
[`change.patch`](examples/ticket-service/change.patch): an `EscalateTicket` command and `TicketEscalated` event, an
`auto-escalation` group that sends it, `ticket-activity` handling the new event, `ticket-log` moved to fire and
forget, and a change to how `AssignTicket` is decided. Both Artifacts were extracted twice, from `git worktree`
checkouts, with identical bytes, and rendered with `node changeviz/src/cli.ts compare smd base.json head.json`.

<picture><source media="(prefers-color-scheme: dark)" srcset="examples/ticket-service/smd-dark.svg"><img src="examples/ticket-service/smd-light.svg" alt="ticket-service SMD change"></picture>
