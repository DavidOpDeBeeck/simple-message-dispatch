# Design: Durable Delivery

Status: implemented. Supersedes the event-store outbox and inbox explored in [#5](https://github.com/DavidOpDeBeeck/simple-message-dispatch/pull/5).

## Problem

Applications exchange events through a broker. A send must belong to the publishing transaction, every event must reach every interested processing group once, and the events of a
subject must be handled in publishing order. Loom, a real application with 13 services on ActiveMQ Artemis, built this outside SMD. This design takes what Loom needs and leaves out the rest.

## Decisions

| Decision                                                          | Why                                                                                                                                         |
|-------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Own tables, not event-store logs                                  | An outbox is a queue, not a log. Sharing the event store's global sequence tied every cursor to outbox and inbox gaps, and to `gap-timeout` |
| One inbox per application, stored once, fanned out per group      | One broker subscription per topic; a processing group reads any number of topics through one inbox                                          |
| Producer positions per outbox and subject                         | Wrong order is silent and spreads into new events; a missing position is a visible stall that heals when the message arrives                 |
| Inbox stores the serialized event, resolves types at delivery     | An unknown type never blocks the broker queue; the subject waits until a deploy adds the type                                                |
| Ingest retries database failures in place                         | Rolling back would use up broker delivery attempts and let later messages overtake, whatever the broker's redelivery settings                |
| Events without a subject are unordered                            | Ordering them would serialize every subjectless publication on one counter row                                                               |
| Handler failures retry forever                                    | Dropping an inbound event is never correct; a blocked subject is visible in `smd_inbox_pending`                                             |
| Fan-out at ingest, with a persistent group registry               | No watermark or discovery scan; a new group is backfilled under the same inbox lock                                                          |
| Positions travel in the transport, not in metadata                | Neither the relay nor the ingest deserializes events                                                                                         |
| Placed in `smd-event-store`                                       | Reuses `EventSerializer`, `SerializedEvent`, and `ConnectionProvider`; can move to its own module later                                      |

## Mechanism

**Outbox.** `send` defers two statements into the publishing transaction: an upsert of `smd_outbox_sequence` that returns the next position for the subject, and an insert into
`smd_outbox`. The relay claims the oldest unsent row whose subject has no earlier unsent row (`FOR UPDATE SKIP LOCKED`), sends it, and marks it sent in the same transaction. A send
failure is recorded in a new transaction with a backoff.

**Inbox.** Each received event is inserted under a transaction-scoped advisory lock on the inbox. In the same transaction, the event is queued in `smd_inbox_pending` for every group in
`smd_inbox_group`, and a cursor is created for new subjects. A group claims the oldest pending row that is either unpositioned or at its cursor's next position, handles it, deletes the
row, and advances the cursor, all in one transaction.

## Accepted Limitations

- A cursor starts at the first position stored for a subject. A lower position of a new subject arriving after a higher one is logged and not delivered.
- Repairs (skipping a lost position, removing a group) are SQL updates.
- Rows are kept indefinitely.
- Outbox names must be unique across the system; a clash is logged, not prevented.

## Not Included

- Tracing spans for the relay's send and the ingest's insert. The trace context still travels in the metadata.
- Wake-ups on commit (`LISTEN/NOTIFY`); delivery polls every `polling-delay`.
- Metrics, a status API, and a skip API.
- Commands.
