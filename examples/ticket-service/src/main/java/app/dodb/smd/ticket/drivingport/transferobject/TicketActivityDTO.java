package app.dodb.smd.ticket.drivingport.transferobject;

import java.time.Instant;
import java.util.UUID;

public record TicketActivityDTO(UUID ticketId,
                                UUID eventId,
                                String eventType,
                                String customerId,
                                Instant timestamp,
                                String details) {
}
