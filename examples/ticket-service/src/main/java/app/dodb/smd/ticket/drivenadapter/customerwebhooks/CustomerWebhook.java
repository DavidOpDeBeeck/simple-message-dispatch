package app.dodb.smd.ticket.drivenadapter.customerwebhooks;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.ticket.drivingport.event.TicketEvent;

import static java.util.Objects.requireNonNull;

public record CustomerWebhook(MessageId deliveryId, String customerId, TicketEvent event) {

    public CustomerWebhook {
        requireNonNull(deliveryId);
        requireNonNull(customerId);
        requireNonNull(event);
    }
}
