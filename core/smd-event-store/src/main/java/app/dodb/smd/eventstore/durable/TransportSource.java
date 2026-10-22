package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.event.delivery.EventSubscription;

public interface TransportSource {

    // Acknowledge a message only after receive returns; when it throws, the transport must redeliver the message.
    EventSubscription subscribe(String subscriberName, TransportReceiver receiver);
}
