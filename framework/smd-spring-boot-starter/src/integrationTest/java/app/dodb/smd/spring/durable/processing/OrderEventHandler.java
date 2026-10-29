package app.dodb.smd.spring.durable.processing;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ProcessingGroup(DurableDeliveryTestConfiguration.ORDERS)
public class OrderEventHandler {

    private final List<OrderEvent> handledEvents = new CopyOnWriteArrayList<>();

    @EventHandler
    public void on(OrderEvent event) {
        handledEvents.add(event);
    }

    public List<OrderEvent> getHandledEvents() {
        return List.copyOf(handledEvents);
    }
}
