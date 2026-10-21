package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.eventstore.deadline.SerializedDeadline;

import java.time.Instant;

public interface DeadlineSerializer {

    <R, C extends Command<R>> SerializedDeadline serialize(CommandMessage<R, C> commandMessage, Instant dueAt);

    <R, C extends Command<R>> CommandMessage<R, C> deserialize(SerializedDeadline deadline);
}
