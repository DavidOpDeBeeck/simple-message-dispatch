package app.dodb.smd.ticket.drivingadapter;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.ticket.drivingport.command.AssignTicketCommand;
import app.dodb.smd.ticket.drivingport.command.OpenTicketCommand;
import app.dodb.smd.ticket.drivingport.command.ResolveTicketCommand;
import app.dodb.smd.ticket.drivingport.query.GetTicketActivityQuery;
import app.dodb.smd.ticket.drivingport.query.GetTicketQuery;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.ResponseEntity.created;
import static org.springframework.http.ResponseEntity.notFound;

@RestController
@RequestMapping
public class TicketController {

    private static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    public TicketController(CommandGateway commandGateway, QueryGateway queryGateway) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TicketDTO> open(@RequestHeader(CUSTOMER_HEADER) String customerId,
                                          @Valid @RequestBody OpenTicketRequest request) {
        var ticketId = commandGateway.send(new OpenTicketCommand(request.title(), request.description()), metadata(customerId));
        return queryGateway.send(new GetTicketQuery(ticketId), metadata(customerId))
            .map(ticket -> created(URI.create("/tickets/" + ticket.ticketId())).body(ticket))
            .orElseGet(() -> notFound().build());
    }

    @PostMapping("/tickets/{ticketId}/assign")
    public ResponseEntity<TicketDTO> assign(@PathVariable UUID ticketId,
                                            @RequestHeader(CUSTOMER_HEADER) String customerId,
                                            @Valid @RequestBody AssignTicketRequest request) {
        commandGateway.send(new AssignTicketCommand(ticketId, request.assignee()), metadata(customerId));
        return queryGateway.send(new GetTicketQuery(ticketId), metadata(customerId))
            .map(ResponseEntity::ok)
            .orElseGet(() -> notFound().build());
    }

    @PostMapping("/tickets/{ticketId}/resolve")
    public ResponseEntity<TicketDTO> resolve(@PathVariable UUID ticketId,
                                             @RequestHeader(CUSTOMER_HEADER) String customerId,
                                             @Valid @RequestBody ResolveTicketRequest request) {
        commandGateway.send(new ResolveTicketCommand(ticketId, request.resolution()), metadata(customerId));
        return queryGateway.send(new GetTicketQuery(ticketId), metadata(customerId))
            .map(ResponseEntity::ok)
            .orElseGet(() -> notFound().build());
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<TicketDTO> get(@PathVariable UUID ticketId,
                                         @RequestHeader(CUSTOMER_HEADER) String customerId) {
        return queryGateway.send(new GetTicketQuery(ticketId), metadata(customerId))
            .map(ResponseEntity::ok)
            .orElseGet(() -> notFound().build());
    }

    @GetMapping("/tickets/{ticketId}/activity")
    public ResponseEntity<List<TicketActivityDTO>> activity(@PathVariable UUID ticketId,
                                                            @RequestHeader(CUSTOMER_HEADER) String customerId) {
        return ResponseEntity.ok(queryGateway.send(QueryMessage.from(new GetTicketActivityQuery(ticketId), metadata(customerId))));
    }

    private static Metadata metadata(String customerId) {
        return new Metadata(null, Instant.now(), null, Map.of("customerId", customerId));
    }

    public record OpenTicketRequest(@NotBlank String title, @NotBlank String description) {
    }

    public record AssignTicketRequest(@NotBlank String assignee) {
    }

    public record ResolveTicketRequest(@NotBlank String resolution) {
    }
}
