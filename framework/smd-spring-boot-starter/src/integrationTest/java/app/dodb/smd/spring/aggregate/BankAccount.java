package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
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

    @CommandHandler
    public BankAccount(OpenBankAccountCommand command, AggregateEvents<Event> events) {
        events.apply(new BankAccountOpenedEvent(command.accountId()));
    }

    @CommandHandler
    public long handle(DepositMoneyCommand command, AggregateEvents<Event> events) {
        if (command.amount() <= 0) {
            throw new IllegalArgumentException("Deposit must be positive: " + command.amount());
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
