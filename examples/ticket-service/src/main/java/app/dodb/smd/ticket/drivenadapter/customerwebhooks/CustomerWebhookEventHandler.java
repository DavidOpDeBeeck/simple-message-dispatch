package app.dodb.smd.ticket.drivenadapter.customerwebhooks;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.ticket.drivingport.event.TicketEvent;
import org.springframework.stereotype.Component;

@Component
@ProcessingGroup("customer-webhooks")
public class CustomerWebhookEventHandler {

    private final CustomerWebhookSender webhookSender;

    public CustomerWebhookEventHandler(CustomerWebhookSender webhookSender) {
        this.webhookSender = webhookSender;
    }

    @EventHandler
    public void on(TicketEvent event, MessageId eventId, @MetadataValue("customerId") String customerId) {
        webhookSender.send(new CustomerWebhook(eventId, customerId, event));
    }
}
