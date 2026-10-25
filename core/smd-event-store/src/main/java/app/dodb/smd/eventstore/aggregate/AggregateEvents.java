package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;

public interface AggregateEvents<E extends Event> {

    void apply(E event);
}
