package app.dodb.smd.spring.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateCommandHandler;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.EventSourcingHandler;

import java.util.UUID;

@Aggregate("audited-account")
public class AuditedAccount {

    @AggregateId
    private UUID accountId;

    private AuditedAccount() {
    }

    @AggregateCommandHandler
    public AuditedAccount(OpenAuditedAccountCommand command, AuditLog auditLog, AggregateEvents<Event> events) {
        auditLog.record("opened " + command.accountId());
        events.apply(new AuditedAccountOpenedEvent(command.accountId()));
    }

    @EventSourcingHandler
    void on(AuditedAccountOpenedEvent event) {
        accountId = event.accountId();
    }
}
