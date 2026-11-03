package app.dodb.smd.ticket.infrastructure;

import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.ticket.drivingport.TicketUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestPrincipalProvider implements PrincipalProvider {

    public static final String USER_HEADER = "X-User-Id";

    @Override
    public Principal get() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return TicketUser.ANONYMOUS;
        }
        var user = attributes.getRequest().getHeader(USER_HEADER);
        if (user == null || user.isBlank()) {
            return TicketUser.ANONYMOUS;
        }
        return new TicketUser(user.strip());
    }
}
