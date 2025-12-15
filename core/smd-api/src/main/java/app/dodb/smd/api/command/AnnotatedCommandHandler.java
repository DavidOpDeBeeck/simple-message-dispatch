package app.dodb.smd.api.command;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import com.google.common.collect.Sets;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logClasses;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public record AnnotatedCommandHandler<R, C extends Command<R>>(Provider<?> provider, Method method, Class<C> commandType) implements CommandHandlerBehaviour<R, C> {

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

        Set<Class<?>> allowedParameterTypes = Set.of(MessageId.class, Metadata.class);
        Set<Class<?>> invalidParameterTypes = Sets.difference(otherParameterTypes, allowedParameterTypes);
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

        Class<?> commandType = commandParameterTypes.iterator().next();
        Type commandReturnType = resolveGenericType(commandType, Command.class);
        Type methodReturnType = method.getGenericReturnType();
        if (!Objects.equals(methodReturnType, commandReturnType)) {
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

        return new AnnotatedCommandHandler<>(provider, method, (Class<C>) commandType);
    }

    public AnnotatedCommandHandler {
        requireNonNull(provider);
        requireNonNull(method);
        requireNonNull(commandType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public R handle(CommandMessage<R, C> commandMessage) {
        try {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] parameters = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (MessageId.class.isAssignableFrom(parameterType)) {
                    parameters[i] = commandMessage.getMessageId();
                }
                if (Metadata.class.isAssignableFrom(parameterType)) {
                    parameters[i] = commandMessage.getMetadata();
                }
                if (Command.class.isAssignableFrom(parameterType)) {
                    parameters[i] = commandMessage.getPayload();
                }
            }
            return (R) method.invoke(provider.get(), parameters);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
