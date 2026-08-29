package app.dodb.smd.ticket.drivenadapter.ticketactivity;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.ticket.drivenport.TicketActivityRepository;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@ProcessingGroup("ticket-activity")
public class JdbcTicketActivityRepository implements TicketActivityRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventHandler
    public void handle(TicketOpenedEvent event,
                       MessageId eventId,
                       Instant timestamp,
                       @MetadataValue("customerId") String customerId) {
        recordEvent(eventId, event.ticketId(), "TicketOpened", customerId, timestamp, "title=" + event.title());
    }

    @EventHandler
    public void handle(TicketAssignedEvent event,
                       MessageId eventId,
                       Instant timestamp,
                       @MetadataValue("customerId") String customerId) {
        recordEvent(eventId, event.ticketId(), "TicketAssigned", customerId, timestamp, "assignee=" + event.assignee());
    }

    @EventHandler
    public void handle(TicketResolvedEvent event,
                       MessageId eventId,
                       Instant timestamp,
                       @MetadataValue("customerId") String customerId) {
        recordEvent(eventId, event.ticketId(), "TicketResolved", customerId, timestamp, "resolution=" + event.resolution());
    }

    @Override
    public List<TicketActivityDTO> findAll(UUID ticketId) {
        return jdbc.query("""
                SELECT ticket_id, event_id, event_type, customer_id, timestamp, details
                FROM ticket_activity
                WHERE ticket_id = ?
                ORDER BY timestamp, event_id
                """,
            (resultSet, _) -> new TicketActivityDTO(
                resultSet.getObject("ticket_id", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("customer_id"),
                resultSet.getTimestamp("timestamp").toInstant(),
                resultSet.getString("details")
            ),
            ticketId
        );
    }

    private void recordEvent(MessageId eventId,
                             UUID ticketId,
                             String eventType,
                             String customerId,
                             Instant timestamp,
                             String details) {
        jdbc.update("""
                INSERT INTO ticket_activity
                    (ticket_id, event_id, event_type, customer_id, timestamp, details)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
            ticketId,
            eventId.value(),
            eventType,
            customerId,
            Timestamp.from(timestamp),
            details
        );
    }
}
