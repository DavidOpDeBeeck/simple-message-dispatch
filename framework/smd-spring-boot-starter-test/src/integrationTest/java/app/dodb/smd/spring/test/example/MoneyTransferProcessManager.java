package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.EventPublisher;

public class MoneyTransferProcessManager {

    private final CommandGateway commandGateway;
    private final EventPublisher eventPublisher;

    public MoneyTransferProcessManager(CommandGateway commandGateway, EventPublisher eventPublisher) {
        this.commandGateway = commandGateway;
        this.eventPublisher = eventPublisher;
    }

    @CommandHandler
    public boolean handle(TransferMoneyCommand command) {
        boolean subtractionSucceeded = commandGateway.send(new SubtractMoneyCommand(command.fromAccount(), command.amount()));
        boolean additionSucceeded = commandGateway.send(new AddMoneyCommand(command.toAccount(), command.amount()));

        if (subtractionSucceeded && additionSucceeded) {
            eventPublisher.publish(new MoneyTransferredEvent(command.fromAccount(), command.toAccount(), command.amount()));
            return true;
        }
        return false;
    }
}
