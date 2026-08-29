package app.dodb.smd.ticket.drivenadapter;

import app.dodb.smd.ticket.domain.Ticket;
import app.dodb.smd.ticket.domain.TicketStatus;
import app.dodb.smd.ticket.drivenport.TicketRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static app.dodb.smd.ticket.domain.TicketStatus.valueOf;

@Repository
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Ticket> find(UUID ticketId) {
        return jdbc.query("""
                SELECT ticket_id, title, description, status, assignee, resolution, version
                FROM ticket
                WHERE ticket_id = ?
                """,
            (resultSet, _) -> {
                String title = resultSet.getString("title");
                String description = resultSet.getString("description");
                TicketStatus status = valueOf(resultSet.getString("status"));
                String assignee = resultSet.getString("assignee");
                String resolution = resultSet.getString("resolution");
                return new Ticket(ticketId, title, description, status, assignee, resolution, resultSet.getInt("version"));
            },
            ticketId
        ).stream().findFirst();
    }

    @Override
    public void save(Ticket ticket) {
        if (ticket.version() == 0) {
            insert(ticket);
            return;
        }

        int expectedVersion = ticket.version() - 1;
        int updated = jdbc.update("""
                UPDATE ticket
                SET status = ?, assignee = ?, resolution = ?, version = ?, updated_at = CURRENT_TIMESTAMP
                WHERE ticket_id = ? AND version = ?
                """,
            ticket.status().name(),
            ticket.assignee(),
            ticket.resolution(),
            ticket.version(),
            ticket.ticketId(),
            expectedVersion
        );
        if (updated != 1) {
            throw new ConcurrentTicketChangeException(ticket.ticketId(), expectedVersion);
        }
    }

    private void insert(Ticket ticket) {
        int inserted = jdbc.update("""
                INSERT INTO ticket
                    (ticket_id, title, description, status, assignee, resolution, version, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
            ticket.ticketId(),
            ticket.title(),
            ticket.description(),
            ticket.status().name(),
            ticket.assignee(),
            ticket.resolution(),
            ticket.version()
        );
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert ticket: ticketId=" + ticket.ticketId());
        }
    }
}
