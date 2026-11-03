package app.dodb.smd.ticket.drivingport.transferobject;

import java.time.Instant;
import java.util.UUID;

public record TicketDTO(UUID ticketId,
                        String title,
                        String description,
                        TicketStatus status,
                        String assignee,
                        String resolution,
                        Instant openedAt,
                        Instant assignedAt,
                        Instant resolvedAt) {
}
