package app.dodb.smd.api.command;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import app.dodb.smd.api.utils.TypeUtils;
import com.google.common.collect.Sets;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logClasses;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public record AnnotatedCommandHandler<R, C extends Command<R>>(Provider<?> provider, Method method, Class<C> commandType, MessageArgumentBinder<C, CommandMessage<R, C>> argumentBinder)
    implements CommandHandlerBehaviour<R, C> {

    public static CommandHandlerRegistry from(Object object) {
        return from(new SingletonProvider<>(object));
    }

    public static CommandHandlerRegistry from(Provider<?> provider) {
        Set<AnnotatedCommandHandler<?, ?>> handlers = new HashSet<>();
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
        Set<Class<?>> allParameterTypes = Set.of(method.getParameterTypes());
        Set<Class<?>> commandParameterTypes = allParameterTypes.stream()
            .filter(Command.class::isAssignableFrom)
            .collect(toSet());
        Set<Class<?>> otherParameterTypes = Sets.difference(allParameterTypes, commandParameterTypes);

        if (allParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid command handler: method must have at least one parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
        if (commandParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid command handler: method must include a parameter of type Command.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
        if (commandParameterTypes.size() > 1) {
            throw new IllegalArgumentException("""
                Invalid command handler: method must only include one Command as a parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        Set<Class<?>> allowedParameterTypes = Set.of(MessageId.class, Metadata.class, Principal.class, Instant.class, String.class);
        Set<Class<?>> invalidParameterTypes = TypeUtils.unrelatedTypes(otherParameterTypes, allowedParameterTypes);
        if (!invalidParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid command handler: unsupported parameter types found.
                
                    Method:
                    %s
                
                    Allowed:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(method), logClasses(allowedParameterTypes), logClasses(invalidParameterTypes)));
        }

        var parameters = method.getParameters();
        for (Parameter parameter : parameters) {
            if (String.class.isAssignableFrom(parameter.getType()) && !parameter.isAnnotationPresent(MetadataValue.class)) {
                throw new IllegalArgumentException("""
                    Invalid command handler: metadata value parameter must be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            }
            if (parameter.isAnnotationPresent(MetadataValue.class) && !String.class.isAssignableFrom(parameter.getType())) {
                throw new IllegalArgumentException("""
                    Invalid command handler: only parameters of type String can be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            }
        }

        Class<C> commandType = (Class<C>) commandParameterTypes.iterator().next();
        Type commandReturnType = resolveGenericType(commandType, Command.class);
        Type methodReturnType = method.getGenericReturnType();
        if (!haveSameBounds(methodReturnType, commandReturnType)) {
            throw new IllegalArgumentException("""
                Invalid command handler: return type mismatch.
                
                    Method:
                    %s
                
                    Expected:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(method), logClass(commandReturnType), logClass(methodReturnType)));
        }

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
}
