package app.dodb.smd.api.command;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import app.dodb.smd.api.utils.handlerstrategy.AllowedParameterTypesStrategy;
import app.dodb.smd.api.utils.handlerstrategy.AtLeastOneParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.AtMostOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.ExactlyOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy;
import app.dodb.smd.api.utils.handlerstrategy.MessageReturnTypeStrategy;
import app.dodb.smd.api.utils.handlerstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.PublicStrategy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

public record AnnotatedCommandHandler<R, C extends Command<R>>(Provider<?> provider, Method method, Class<C> commandType, MessageArgumentBinder<C, CommandMessage<R, C>> argumentBinder)
    implements CommandHandlerBehaviour<R, C> {

    private static final List<HandlerValidationStrategy> HANDLER_VALIDATION_STRATEGIES = List.of(
        new PublicStrategy(),
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Command.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new AllowedParameterTypesStrategy(
            Set.of(Command.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class)
        ),
        new MessageReturnTypeStrategy(Command.class)
    );

    public static CommandHandlerRegistry from(Object object) {
        return from(new SingletonProvider<>(object));
    }

    public static CommandHandlerRegistry from(Provider<?> provider) {
        Set<CommandHandlerBehaviour<?, ?>> handlers = new HashSet<>();
        Class<?> clazz = provider.getType();

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(CommandHandler.class)) {
                var commandHandler = mapToCommandHandler(provider, method);
                handlers.add(commandHandler);
            }
        }

        return new CommandHandlerRegistry(handlers);
    }

    @SuppressWarnings("unchecked")
    private static <R, C extends Command<R>> AnnotatedCommandHandler<R, C> mapToCommandHandler(Provider<?> provider, Method method) {
        validateHandler(method, HANDLER_VALIDATION_STRATEGIES);

        var parameters = method.getParameters();
        Class<C> commandType = (Class<C>) stream(parameters)
            .map(Parameter::getType)
            .filter(Command.class::isAssignableFrom)
            .findFirst()
            .orElseThrow();

        return new AnnotatedCommandHandler<>(provider, method, commandType, fromMethodParameters(commandType, parameters));
    }

    public AnnotatedCommandHandler {
        requireNonNull(provider);
        requireNonNull(method);
        requireNonNull(commandType);
        requireNonNull(argumentBinder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public R handle(CommandMessage<R, C> commandMessage) {
        try {
            return (R) method.invoke(provider.get(), argumentBinder.toArguments(commandMessage));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "@CommandHandler " + logMethod(method);
    }
}
