package app.dodb.smd.spring.test;

import app.dodb.smd.spring.test.example.AddMoneyCommand;
import app.dodb.smd.spring.test.example.MoneyTransferredEvent;
import app.dodb.smd.spring.test.example.SubtractMoneyCommand;
import app.dodb.smd.spring.test.example.TransferMoneyCommand;
import app.dodb.smd.test.SMDTestExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@EnableSMDStubs
@SpringBootTest(classes = IntegrationTestConfiguration.class)
class SMDTestExtensionTest {

    private static final String ACCOUNT_1 = "ACCOUNT-1";
    private static final String ACCOUNT_2 = "ACCOUNT-2";

    @Autowired
    private SMDTestExtension smd;

    @Test
    void send_whenBothAccountsAcceptTransfer_publishesEvent() {
        // Given
        smd.stubCommand(new SubtractMoneyCommand(ACCOUNT_1, 100), true);
        smd.stubCommand(new AddMoneyCommand(ACCOUNT_2, 100), true);

        // When
        boolean actual = smd.send(new TransferMoneyCommand(ACCOUNT_1, ACCOUNT_2, 100));

        // Then
        assertThat(actual).isTrue();
        assertThat(smd.getEvents()).containsExactly(
            new MoneyTransferredEvent(ACCOUNT_1, ACCOUNT_2, 100)
        );
    }

    @Test
    void send_whenSubtractionFails_doesNotPublishEvent() {
        // Given
        smd.stubCommand(new SubtractMoneyCommand(ACCOUNT_1, 100), false);
        smd.stubCommand(new AddMoneyCommand(ACCOUNT_2, 100), true);

        // When
        boolean actual = smd.send(new TransferMoneyCommand(ACCOUNT_1, ACCOUNT_2, 100));

        // Then
        assertThat(actual).isFalse();
        assertThat(smd.getEvents()).isEmpty();
    }

    @Test
    void send_whenAdditionFails_doesNotPublishEvent() {
        // Given
        smd.stubCommand(new SubtractMoneyCommand(ACCOUNT_1, 100), true);
        smd.stubCommand(new AddMoneyCommand(ACCOUNT_2, 100), false);

        // When
        boolean actual = smd.send(new TransferMoneyCommand(ACCOUNT_1, ACCOUNT_2, 100));

        // Then
        assertThat(actual).isFalse();
        assertThat(smd.getEvents()).isEmpty();
    }
}
