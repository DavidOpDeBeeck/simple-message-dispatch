# SMD: Stored Events

## 10. Stored Events: Type Names and Evolution

With the event store or durable delivery enabled, events are serialized to JSON under a type name. The default type name is the Java class name, so moving or renaming a
class makes stored events unreadable.

- Before the first production event, map every event class to a stable, versioned name in one `StaticEventTypeResolver` bean in `DrivenAdapterConfiguration`:
  `"<subject>.<event>.v<n>"`, such as `"ticket.opened.v1"`.
- Every published event needs a mapping, because the event store stores every published event.
- A released type name and its JSON shape are frozen. To change an event:
  1. Change the record.
  2. Map it under the next version, `"ticket.opened.v2"`, and remove the old name from the resolver.
  3. Add an `EventUpcaster` bean that turns the old version into the new one.
- To rename an event class, keep its type name. The type name, not the class name, is stored.
- Upcasters are pure, run on every read, and are never changed after release. Invalid upcaster chains fail at startup.
- Applications that exchange events through a broker resolve the same type names. Deploy consumers before producers publish a new type or version.

```java
@Bean
EventUpcaster ticketOpenedV1ToV2() {
    return EventUpcaster.transform("ticket.opened.v1", "ticket.opened.v2", payload -> payload.put("priority", "NORMAL"));
}

@Bean
EventUpcaster ticketCreatedV1Renamed() {
    return EventUpcaster.rename("ticket.created.v1", "ticket.opened.v1");
}
```
