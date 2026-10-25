package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ProcessingGroup(BankAccountView.PROCESSING_GROUP)
public class BankAccountView {

    static final String PROCESSING_GROUP = "bank-account-view";

    private final List<String> deposits = new CopyOnWriteArrayList<>();

    @EventHandler
    public void handle(MoneyDepositedEvent event) {
        deposits.add(event.accountId() + ":" + event.amount());
    }

    public List<String> deposits() {
        return List.copyOf(deposits);
    }
}
