package app.dodb.smd.ticket.infrastructure;

import app.dodb.smd.ticket.drivingport.TicketUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static app.dodb.smd.ticket.infrastructure.RequestPrincipalProvider.USER_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

class RequestPrincipalProviderTest {

    private final RequestPrincipalProvider principalProvider = new RequestPrincipalProvider();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void get_requestWithUserHeader_returnsThatUser() {
        var request = new MockHttpServletRequest();
        request.addHeader(USER_HEADER, " agent-7 ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var actual = principalProvider.get();

        assertThat(actual).isEqualTo(new TicketUser("agent-7"));
    }

    @Test
    void get_requestWithBlankUserHeader_returnsAnonymous() {
        var request = new MockHttpServletRequest();
        request.addHeader(USER_HEADER, " ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var actual = principalProvider.get();

        assertThat(actual).isEqualTo(TicketUser.ANONYMOUS);
    }

    @Test
    void get_requestWithoutUserHeader_returnsAnonymous() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        var actual = principalProvider.get();

        assertThat(actual).isEqualTo(TicketUser.ANONYMOUS);
    }

    @Test
    void get_outsideRequest_returnsAnonymous() {
        var actual = principalProvider.get();

        assertThat(actual).isEqualTo(TicketUser.ANONYMOUS);
    }
}
