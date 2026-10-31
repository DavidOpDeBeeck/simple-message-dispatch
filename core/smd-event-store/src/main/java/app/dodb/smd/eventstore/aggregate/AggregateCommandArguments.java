package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

record AggregateCommandArguments<R, C extends Command<R>>(MessageArgumentBinder<C, CommandMessage<R, C>> messageArguments,
                                                          OptionalInt eventsIndex,
                                                          List<Collaborator> collaborators) {

    private static final Set<Class<?>> MESSAGE_PARAMETER_TYPES =
        Set.of(Command.class, AggregateEvents.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class);

    AggregateCommandArguments {
        requireNonNull(messageArguments);
        requireNonNull(eventsIndex);
        requireNonNull(collaborators);
    }

    static <R, C extends Command<R>> AggregateCommandArguments<R, C> from(Executable handler, Class<C> commandType, ObjectCreator objectCreator) {
        var parameters = handler.getParameters();
        var eventsIndex = IntStream.range(0, parameters.length)
            .filter(index -> parameters[index].getType() == AggregateEvents.class)
            .findFirst();
        var collaborators = IntStream.range(0, parameters.length)
            .filter(index -> MESSAGE_PARAMETER_TYPES.stream().noneMatch(type -> type.isAssignableFrom(parameters[index].getType())))
            .mapToObj(index -> new Collaborator(index, objectCreator.create(parameters[index].getType())))
            .toList();
        return new AggregateCommandArguments<>(fromMethodParameters(commandType, parameters), eventsIndex, collaborators);
    }

    @SuppressWarnings("unchecked")
    static Class<? extends Command<?>> commandType(Executable handler) {
        return stream(handler.getParameters())
            .map(Parameter::getType)
            .filter(Command.class::isAssignableFrom)
            .map(type -> (Class<? extends Command<?>>) type)
            .findFirst()
            .orElseThrow();
    }

    Object[] toArguments(CommandMessage<R, C> commandMessage, AggregateEvents<?> events) {
        var arguments = messageArguments.toArguments(commandMessage);
        eventsIndex.ifPresent(index -> arguments[index] = events);
        collaborators.forEach(collaborator -> arguments[collaborator.index()] = collaborator.provider().get());
        return arguments;
    }

    record Collaborator(int index, Provider<?> provider) {

        Collaborator {
            requireNonNull(provider);
        }
    }
}
