package app.dodb.smd.spring.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateCommandHandler;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.EventSourcingHandler;

import java.util.UUID;

@Aggregate("bank-account")
public class BankAccount {

    @AggregateId
    private UUID accountId;
    private long balance;

    private BankAccount() {
    }

    @AggregateCommandHandler
    public BankAccount(OpenBankAccountCommand command, AggregateEvents<Event> events) {
        events.apply(new BankAccountOpenedEvent(command.accountId()));
    }

    @AggregateCommandHandler
    public long handle(DepositMoneyCommand command, DepositLimit limit, AggregateEvents<Event> events) {
        if (command.amount() <= 0) {
            throw new IllegalArgumentException("Deposit must be positive: " + command.amount());
        }
        if (command.amount() > limit.maximum()) {
            throw new IllegalArgumentException("Deposit exceeds limit of %d: %d".formatted(limit.maximum(), command.amount()));
        }
        events.apply(new MoneyDepositedEvent(accountId, command.amount()));
        return balance;
    }

    @EventSourcingHandler
    void on(BankAccountOpenedEvent event) {
        accountId = event.accountId();
    }

    @EventSourcingHandler
    void on(MoneyDepositedEvent event) {
        balance += event.amount();
    }
}
