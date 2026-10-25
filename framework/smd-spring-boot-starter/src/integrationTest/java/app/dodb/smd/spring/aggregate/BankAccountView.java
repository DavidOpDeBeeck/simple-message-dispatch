package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.api.event.delivery.ProcessingMetadata.SUBJECT_ID;

@ProcessingGroup(BankAccountView.PROCESSING_GROUP)
public class BankAccountView {

    static final String PROCESSING_GROUP = "bank-account-view";

    private final List<String> deposits = new CopyOnWriteArrayList<>();

    @EventHandler
    public void handle(MoneyDepositedEvent event, Metadata metadata) {
        deposits.add(metadata.property(SUBJECT_ID).orElse("no-subject") + ":" + event.amount());
    }

    public List<String> deposits() {
        return List.copyOf(deposits);
    }
}
