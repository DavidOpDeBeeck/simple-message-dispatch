package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.eventstore.aggregate.TargetAggregateId;

import java.util.UUID;

public record DepositMoneyCommand(@TargetAggregateId UUID accountId, long amount) implements Command<Long> {
}
