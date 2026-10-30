package app.dodb.smd.ticket.drivenadapter.customerwebhooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingCustomerWebhookSender implements CustomerWebhookSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingCustomerWebhookSender.class);

    @Override
    public void send(CustomerWebhook webhook) {
        LOGGER.info("Customer webhook sent: deliveryId={}, customerId={}, ticketId={}, event={}",
            webhook.deliveryId().value(),
            webhook.customerId(),
            webhook.event().ticketId(),
            webhook.event().getClass().getSimpleName());
    }
}
