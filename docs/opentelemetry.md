# OpenTelemetry

`smd-opentelemetry` records the invocations of the command, query, and event buses as OpenTelemetry spans. They are standard spans, so they go to any tracing backend the
application's OpenTelemetry setup exports to, such as Jaeger, Grafana Tempo, or [SMD Monitor](https://github.com/DavidOpDeBeeck/smd-monitor), a live monitor built on them.

## With Spring Boot

The SMD starter includes `smd-opentelemetry` and records spans as soon as the application has an `OpenTelemetry` bean. See [OpenTelemetry](spring-boot.md#opentelemetry) in the
Spring Boot guide for the dependency, sampling, and how to turn it off.

## Without Spring

Add the module next to `smd-api`:

```kotlin
dependencies {
    implementation("app.dodb:smd-opentelemetry:0.0.12")
}
```

Create one `MessageTracing` from your `OpenTelemetry` instance and install it on every bus, on the event bus's sinks, and on its processing groups:

```java
var tracing = new MessageTracing(openTelemetry);

var commandBus = CommandBusSpec.withDefaults()
        .commandHandlers(commandHandlerLocator)
        .interceptors(tracing, new TransactionalCommandBusInterceptor(transactionProvider))
        .create();

var queryBus = QueryBusSpec.withDefaults()
        .queryHandlers(queryHandlerLocator)
        .interceptors(tracing, new TransactionalQueryBusInterceptor(transactionProvider))
        .create();

var eventBus = EventBusSpec.withDefaults()
        .processingGroups(processingGroupLocator)
        .interceptors(tracing, new TransactionalEventBusInterceptor(transactionProvider))
        .sinkInterceptors(tracing)
        .processingGroupInterceptors(tracing)
        .create();
```

Put `MessageTracing` first: the first interceptor wraps the others, so the span covers the transaction and records a failure raised at its commit. A bus you build without
`MessageTracing` records nothing.

An event store's [outbox and inbox](event-store.md#outbox-and-inbox) forward and receive events outside the bus. Add `MessageTracing` to the store as well, so a forward records a
`send` span with the wrapped sink's delivery, such as `jms`, and an inbox insert records a `send` span with delivery `event-store`:

```java
var eventStore = new EventStore(EventStoreConfig.withDefaults()
        // storage, serializer, and transaction provider
        .sinkInterceptors(tracing)
        .build());
```

Give the `OpenTelemetry` instance a text-map propagator, such as W3C trace context, and a batch span processor. Without a propagator, spans are recorded without the links
described below; the batch processor exports on its own thread, so a slow or unavailable backend does not delay a message.

## What a Trace Looks Like

A command sent from an HTTP request, whose handler publishes an event to a synchronous sink and to the event store, records:

```text
POST /tickets
└─ send app.dodb.tickets.OpenTicket                  command
   └─ publish app.dodb.tickets.TicketOpened          event                          → links to send OpenTicket
      ├─ send app.dodb.tickets.TicketOpened          delivery sync                  → links to publish
      │  └─ process app.dodb.tickets.TicketOpened    group notifications, sync      → links to publish
      ├─ send app.dodb.tickets.TicketOpened          delivery event-store           → links to publish
      └─ process app.dodb.tickets.TicketOpened       group projections, event-store → links to publish
```

The event-store handling runs later, on a polling thread, and still joins the trace of its publication.

## Spans

Every span belongs to the instrumentation scope `app.dodb.smd` and is named after its operation and the message's class:

| Span                   | Kind       | Recorded for                                         |
|------------------------|------------|------------------------------------------------------|
| `send <command/query>` | `PRODUCER` | Each command or query dispatch, covering its handler |
| `publish <event>`      | `PRODUCER` | Each event publication                               |
| `send <event>`         | `PRODUCER` | Each sink the publication is sent to                 |
| `process <event>`      | `CONSUMER` | Each processing group handling the event             |

A span ends when the interceptor chain or the handlers it wraps return. A sink's span is also recorded for events a selecting sink's selector then drops. SMD does not record
a span per handler method within a processing group.

| Attribute                       | Value                                                                             |
|---------------------------------|-----------------------------------------------------------------------------------|
| `messaging.system`              | `smd`                                                                             |
| `messaging.operation.name`      | `send`, `publish`, or `process`, as in the span name                              |
| `messaging.operation.type`      | `send` for dispatches, publications, and sends to a sink; `process` for handlings |
| `messaging.destination.name`    | The message's class name                                                          |
| `messaging.message.id`          | The message ID                                                                    |
| `messaging.consumer.group.name` | The processing group; only on `process` spans                                     |
| `messaging.smd.message.kind`    | `command`, `query`, or `event`                                                    |
| `messaging.smd.delivery`        | The sink's delivery on an event's `send` span, the source's on a `process` span   |
| `thread.name`                   | The thread the invocation ran on                                                  |
| `error.type`                    | The failure's class name, set with the span status `ERROR`                        |

`messaging.smd.delivery` is the `EventDelivery` name: `sync`, `async-await`, `async-fire-and-forget`, `event-store`, or the medium a custom sink or source names. The
attributes follow the OpenTelemetry messaging semantic conventions; only the message kind and the delivery are SMD's own, in the `messaging.smd` namespace. The attribute keys
are constants of `MessageSpans`.

## Parents and Links

- A span's parent is the span that is current when it starts, such as an incoming HTTP request or an `@Observed` method. Without one, its parent is the span its message's
  metadata carries, so a handling on a worker or polling thread joins the trace of its publication.
- Every span links to the SMD invocation it continues, also when that invocation is its parent. Lineage is the link: a `process` span links to its event's `publish`, and a
  message a handler sends links to the invocation the handler runs in, so a query sent while handling a command links to the command's `send`.
- A `send` to a sink links to its publication but does not change the message's trace context. A processing group reading the event from that sink links to the `publish`,
  not to the `send`, also when it runs within the send, as a synchronous group does.
- A dispatch that continues no SMD invocation, such as a command sent from an HTTP request, has no link.
- A message that another application publishes again with its original metadata links to the original dispatch, which has the same message ID.

## Trace Context in Metadata

SMD carries trace context in each message's metadata properties, under the fields of the application's propagator: with W3C trace context, `traceparent`, and `tracestate` and
`baggage` when present. Each dispatch and each processing group's handling replaces those properties with its own trace context, so the message a handler receives carries
that of its invocation, and the messages the handler sends inherit it. Baggage in a message's metadata is kept: it is merged with the baggage current where the message
is dispatched or handled, and the message's entries win when both carry the same key.

Because it is ordinary metadata, the event store stores it with the event, and a broker that forwards metadata carries it to another application, so a handling links to its
publication on any thread, instance, or application. Expect these properties when you inspect stored events or assert on captured metadata.

## What Is Not Recorded

Payloads, results, principals, other metadata properties, exception messages, and stack traces are never recorded.
