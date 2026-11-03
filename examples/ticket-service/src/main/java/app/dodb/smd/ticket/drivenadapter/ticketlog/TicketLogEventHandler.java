package app.dodb.smd.ticket.drivenadapter.ticketlog;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.ticket.drivenadapter.DrivenAdapterConfiguration;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ProcessingGroup(DrivenAdapterConfiguration.TICKET_LOG)
public class TicketLogEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketLogEventHandler.class);

    @EventHandler
    public void on(TicketOpenedEvent event, Principal principal, @MetadataValue("customerId") String customerId) {
        LOGGER.info("Ticket opened: ticketId={}, title={}, customerId={}, principal={}", event.ticketId(), event.title(), customerId, principal);
    }

    @EventHandler
    public void on(TicketAssignedEvent event, Principal principal, @MetadataValue("customerId") String customerId) {
        LOGGER.info("Ticket assigned: ticketId={}, assignee={}, customerId={}, principal={}", event.ticketId(), event.assignee(), customerId, principal);
    }

    @EventHandler
    public void on(TicketResolvedEvent event, Principal principal, @MetadataValue("customerId") String customerId) {
        LOGGER.info("Ticket resolved: ticketId={}, customerId={}, principal={}", event.ticketId(), customerId, principal);
    }
}
