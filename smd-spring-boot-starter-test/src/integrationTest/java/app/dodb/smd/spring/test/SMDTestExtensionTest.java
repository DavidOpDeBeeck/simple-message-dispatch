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
    void transferSucceeds() {
        smd.stubCommand(new SubtractMoneyCommand(ACCOUNT_1, 100), true);
        smd.stubCommand(new AddMoneyCommand(ACCOUNT_2, 100), true);

        boolean actual = smd.send(new TransferMoneyCommand(ACCOUNT_1, ACCOUNT_2, 100));

        assertThat(actual).isTrue();
        assertThat(smd.getEvents()).containsExactly(
            new MoneyTransferredEvent(ACCOUNT_1, ACCOUNT_2, 100)
        );
    }

    @Test
    void transferFails_fromAccountSubtractionFails() {
        smd.stubCommand(new SubtractMoneyCommand(ACCOUNT_1, 100), false);
        smd.stubCommand(new AddMoneyCommand(ACCOUNT_2, 100), true);

        boolean actual = smd.send(new TransferMoneyCommand(ACCOUNT_1, ACCOUNT_2, 100));

        assertThat(actual).isFalse();
        assertThat(smd.getEvents()).isEmpty();
    }

    @Test
    void transferFails_toAccountAdditionFails() {
        smd.stubCommand(new SubtractMoneyCommand(ACCOUNT_1, 100), true);
        smd.stubCommand(new AddMoneyCommand(ACCOUNT_2, 100), false);

        boolean actual = smd.send(new TransferMoneyCommand(ACCOUNT_1, ACCOUNT_2, 100));

        assertThat(actual).isFalse();
        assertThat(smd.getEvents()).isEmpty();
    }
}
