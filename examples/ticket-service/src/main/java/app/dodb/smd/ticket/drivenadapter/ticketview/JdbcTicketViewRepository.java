package app.dodb.smd.ticket.drivenadapter.ticketview;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.ticket.domain.TicketStatus;
import app.dodb.smd.ticket.drivenport.TicketViewRepository;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ProcessingGroup("ticket-view")
public class JdbcTicketViewRepository implements TicketViewRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventHandler
    public void handle(TicketOpenedEvent event, Instant timestamp) {
        open(event.ticketId(), event.title(), event.description(), timestamp);
    }

    @EventHandler
    public void handle(TicketAssignedEvent event, Instant timestamp) {
        assign(event.ticketId(), event.assignee(), timestamp);
    }

    @EventHandler
    public void handle(TicketResolvedEvent event, Instant timestamp) {
        resolve(event.ticketId(), event.resolution(), timestamp);
    }

    public void open(UUID ticketId, String title, String description, Instant timestamp) {
        int inserted = jdbc.update("""
                INSERT INTO ticket_view
                    (ticket_id, title, description, status, opened_at)
                VALUES (?, ?, ?, 'OPEN', ?)
                """,
            ticketId, title, description, Timestamp.from(timestamp)
        );
        requireChanged(inserted, ticketId, "open");
    }

    public void assign(UUID ticketId, String assignee, Instant timestamp) {
        int updated = jdbc.update("""
                UPDATE ticket_view
                SET status = 'ASSIGNED', assignee = ?, assigned_at = ?
                WHERE ticket_id = ?
                """,
            assignee, Timestamp.from(timestamp), ticketId
        );
        requireChanged(updated, ticketId, "assign");
    }

    public void resolve(UUID ticketId, String resolution, Instant timestamp) {
        int updated = jdbc.update("""
                UPDATE ticket_view
                SET status = 'RESOLVED', resolution = ?, resolved_at = ?
                WHERE ticket_id = ?
                """,
            resolution, Timestamp.from(timestamp), ticketId
        );
        requireChanged(updated, ticketId, "resolve");
    }

    @Override
    public Optional<TicketDTO> find(UUID ticketId) {
        return jdbc.query("""
                SELECT ticket_id, title, description, status, assignee, resolution,
                       opened_at, assigned_at, resolved_at
                FROM ticket_view
                WHERE ticket_id = ?
                """,
            (resultSet, _) -> new TicketDTO(
                resultSet.getObject("ticket_id", UUID.class),
                resultSet.getString("title"),
                resultSet.getString("description"),
                TicketStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("assignee"),
                resultSet.getString("resolution"),
                resultSet.getTimestamp("opened_at").toInstant(),
                instant(resultSet.getTimestamp("assigned_at")),
                instant(resultSet.getTimestamp("resolved_at"))
            ),
            ticketId
        ).stream().findFirst();
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void requireChanged(int changed, UUID ticketId, String operation) {
        if (changed != 1) {
            throw new IllegalStateException("Failed to " + operation + " ticket view: ticketId=" + ticketId);
        }
    }
}
