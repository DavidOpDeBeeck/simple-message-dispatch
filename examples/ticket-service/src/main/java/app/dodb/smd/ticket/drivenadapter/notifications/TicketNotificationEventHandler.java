package app.dodb.smd.ticket.drivenadapter.notifications;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ProcessingGroup("notifications")
public class TicketNotificationEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketNotificationEventHandler.class);

    @EventHandler
    public void on(TicketOpenedEvent event, @MetadataValue("customerId") String customerId) {
        LOGGER.info("Ticket opened notification: ticketId={}, title={}, customerId={}", event.ticketId(), event.title(), customerId);
    }

    @EventHandler
    public void on(TicketAssignedEvent event, @MetadataValue("customerId") String customerId) {
        LOGGER.info("Ticket assigned notification: ticketId={}, assignee={}, customerId={}", event.ticketId(), event.assignee(), customerId);
    }

    @EventHandler
    public void on(TicketResolvedEvent event, @MetadataValue("customerId") String customerId) {
        LOGGER.info("Ticket resolved notification: ticketId={}, customerId={}", event.ticketId(), customerId);
    }
}
