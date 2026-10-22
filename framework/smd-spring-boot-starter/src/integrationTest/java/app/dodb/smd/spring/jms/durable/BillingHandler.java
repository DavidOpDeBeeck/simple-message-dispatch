package app.dodb.smd.spring.jms.durable;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ProcessingGroup("billing")
public class BillingHandler {

    private final List<Object> handled = new CopyOnWriteArrayList<>();

    @EventHandler
    public void on(OrderPlacedEvent event) {
        handled.add(event);
    }

    @EventHandler
    public void on(PaymentReceivedEvent event) {
        handled.add(event);
    }

    public List<Object> handled() {
        return handled;
    }
}
