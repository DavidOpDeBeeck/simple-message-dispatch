package app.dodb.smd.spring.jms.outbox;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ProcessingGroup("billing")
public class BillingHandler {

    private final List<TicketChangedEvent> handled = new CopyOnWriteArrayList<>();

    @EventHandler
    public void on(TicketChangedEvent event) {
        handled.add(event);
    }

    public List<TicketChangedEvent> handled() {
        return handled;
    }
}
