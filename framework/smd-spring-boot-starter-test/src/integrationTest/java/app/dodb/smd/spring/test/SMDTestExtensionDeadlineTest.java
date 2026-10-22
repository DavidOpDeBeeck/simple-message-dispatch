package app.dodb.smd.spring.test;

import app.dodb.smd.spring.test.example.ExpireReservationCommand;
import app.dodb.smd.spring.test.example.ReservationExpiredEvent;
import app.dodb.smd.spring.test.example.ReserveFundsCommand;
import app.dodb.smd.test.ScheduledDeadline;
import app.dodb.smd.test.SMDTestExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static app.dodb.smd.spring.test.example.FundsReservationManager.RESERVATION_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@EnableSMDStubs
@SpringBootTest(classes = IntegrationTestConfiguration.class)
class SMDTestExtensionDeadlineTest {

    private static final String ACCOUNT = "ACCOUNT-1";
    private static final Instant RESERVED_AT = Instant.parse("2026-10-22T09:00:00Z");

    @Autowired
    private SMDTestExtension smd;

    @Test
    void send_whenHandlerSchedulesDeadline_capturesItWithoutFiring() {
        // Given
        smd.stubTimestamp(RESERVED_AT);

        // When
        smd.send(new ReserveFundsCommand(ACCOUNT, 100));

        // Then
        assertThat(smd.getDeadlines())
            .extracting(ScheduledDeadline::dueAt, ScheduledDeadline::command)
            .containsExactly(tuple(RESERVED_AT.plus(RESERVATION_TIMEOUT), new ExpireReservationCommand(ACCOUNT)));
        assertThat(smd.getEvents()).isEmpty();
    }

    @Test
    void fireDueDeadlines_afterTimeAdvances_dispatchesDueCommandThroughRealHandler() {
        // Given
        smd.stubTimestamp(RESERVED_AT);
        smd.send(new ReserveFundsCommand(ACCOUNT, 100));

        // When
        smd.stubTimestamp(RESERVED_AT.plus(RESERVATION_TIMEOUT));
        var fired = smd.fireDueDeadlines();

        // Then
        assertThat(fired).extracting(ScheduledDeadline::command).containsExactly(new ExpireReservationCommand(ACCOUNT));
        assertThat(smd.getEvents()).containsExactly(new ReservationExpiredEvent(ACCOUNT));
        assertThat(smd.getDeadlines()).isEmpty();
    }

    @Test
    void fireDueDeadlines_beforeDueTime_firesNothing() {
        // Given
        smd.stubTimestamp(RESERVED_AT);
        smd.send(new ReserveFundsCommand(ACCOUNT, 100));

        // When
        smd.stubTimestamp(RESERVED_AT.plus(RESERVATION_TIMEOUT).minusSeconds(1));
        var fired = smd.fireDueDeadlines();

        // Then
        assertThat(fired).isEmpty();
        assertThat(smd.getDeadlines()).hasSize(1);
    }
}
