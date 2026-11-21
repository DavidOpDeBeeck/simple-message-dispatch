package app.dodb.smd.artifactexport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// Reads what SMD registered in a running application context. SMD is reached only by class and member name, never
// compiled against, so the exporter runs against any SMD version an application's history uses: it compiles against
// Spring alone and adapts to the SMD classes it finds at runtime.
final class SmdRegistries {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmdRegistries.class);

    record CommandHandler(Executable member, Class<?> commandType) {
    }

    record EventHandler(String processingGroup, Executable member, Class<?> eventType) {
    }

    record QueryHandler(Executable member, Class<?> queryType) {
    }

    record Medium(String name, Optional<String> dispatcher) {
    }

    record GroupConfiguration(boolean disabled, Medium medium) {
    }

    private final ApplicationContext context;
    private final ClassLoader classLoader;

    SmdRegistries(ApplicationContext context) {
        this.context = requireNonNull(context);
        this.classLoader = requireNonNull(context.getClassLoader());
    }

    Optional<Class<?>> smdClass(String name) {
        try {
            return Optional.of(Class.forName(name, false, classLoader));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    List<CommandHandler> commandHandlers() {
        var handlers = new ArrayList<CommandHandler>();
        for (var registry : locate("app.dodb.smd.api.command.CommandHandlerLocator")) {
            for (var handler : (Collection<?>) invoke(registry, "commandHandlers")) {
                handlers.add(new CommandHandler(member(handler), (Class<?>) invoke(handler, "commandType")));
            }
        }
        return handlers;
    }

    List<EventHandler> eventHandlers() {
        var handlers = new ArrayList<EventHandler>();
        for (var registry : locate("app.dodb.smd.api.event.ProcessingGroupLocator")) {
            var byGroup = (Map<?, ?>) invoke(registry, "eventHandlerRegistryByProcessingGroup");
            for (var entry : byGroup.entrySet()) {
                for (var handler : (Collection<?>) invoke(entry.getValue(), "eventHandlers")) {
                    handlers.add(new EventHandler(String.valueOf(entry.getKey()), member(handler), (Class<?>) invoke(handler, "eventType")));
                }
            }
        }
        return handlers;
    }

    List<QueryHandler> queryHandlers() {
        var handlers = new ArrayList<QueryHandler>();
        for (var registry : locate("app.dodb.smd.api.query.QueryHandlerLocator")) {
            for (var handler : (Collection<?>) invoke(registry, "queryHandlers")) {
                handlers.add(new QueryHandler(member(handler), (Class<?>) invoke(handler, "queryType")));
            }
        }
        return handlers;
    }

    // Runs the application's ProcessingGroupsConfigurer beans, in the order the event bus runs them, against a fresh
    // ProcessingGroupsSpec and reads what they configured for each group.
    Map<String, GroupConfiguration> groupConfigurations(Collection<String> groups) {
        var specType = smdClass("app.dodb.smd.api.event.bus.EventBusSpec$ProcessingGroupsSpec").orElseThrow(() ->
            new IllegalStateException("SMD's EventBusSpec.ProcessingGroupsSpec is not on the classpath"));
        var locatorType = smdClass("app.dodb.smd.api.event.ProcessingGroupLocator").orElseThrow();
        var configurerType = smdClass("app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer").orElseThrow();

        Object spec;
        try {
            var constructor = specType.getDeclaredConstructor(locatorType);
            constructor.setAccessible(true);
            spec = constructor.newInstance(beans(locatorType).get(0));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create a ProcessingGroupsSpec to read the media from", e);
        }
        var configure = interfaceMethod(configurerType, "configure");
        for (var configurer : beans(configurerType)) {
            try {
                configure.invoke(configurer, spec);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("The application's ProcessingGroupsConfigurer " + configurer.getClass().getName() + " failed", e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to run the ProcessingGroupsConfigurer " + configurer.getClass().getName(), e);
            }
        }

        var specByName = (Map<?, ?>) field(spec, "processingGroupSpecByName");
        var defaultSpec = field(spec, "defaultProcessingGroupSpec");
        var configurations = new LinkedHashMap<String, GroupConfiguration>();
        for (var group : groups) {
            var groupSpec = specByName.containsKey(group) ? specByName.get(group) : defaultSpec;
            configurations.put(group, new GroupConfiguration(Boolean.TRUE.equals(field(groupSpec, "disabled")), medium(groupSpec)));
        }
        return configurations;
    }

    private Medium medium(Object groupSpec) {
        var source = firstPresent(groupSpec, "eventSource", "channel");
        if (source == null) {
            return new Medium("unconfigured", Optional.empty());
        }
        var name = deliveryName(source).orElseGet(() -> mediumFromClassName(source));
        return new Medium(name, dispatcherName(source));
    }

    private Optional<String> deliveryName(Object source) {
        var sourceType = smdClass("app.dodb.smd.api.event.delivery.EventSource");
        if (sourceType.isEmpty() || !sourceType.get().isInstance(source)) {
            return Optional.empty();
        }
        try {
            var value = sourceType.get().getMethod("delivery").invoke(source);
            return Optional.of(String.valueOf(invoke(value, "name")));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read the delivery of " + source.getClass().getName(), e);
        }
    }

    // SMD versions before EventSource.delivery() existed: the medium is named after the class that delivers it.
    private static String mediumFromClassName(Object source) {
        var type = source.getClass();
        while (type.getEnclosingClass() != null) {
            type = type.getEnclosingClass();
        }
        var name = type.getSimpleName();
        if (name.startsWith("Synchronous")) {
            return "sync";
        }
        if (name.startsWith("Concurrent") || name.startsWith("AsyncAwait")) {
            return "async-await";
        }
        if (name.startsWith("FireAndForget") || name.startsWith("AsyncFireAndForget")) {
            return "async-fire-and-forget";
        }
        if (name.startsWith("EventStore")) {
            return "event-store";
        }
        if (name.startsWith("Durable")) {
            return "durable";
        }
        LOGGER.warn("Unknown event medium {}; naming the medium after the class", type.getName());
        return name;
    }

    private static Optional<String> dispatcherName(Object source) {
        for (var current = source; current != null; current = firstPresent(current, "delegate")) {
            if (current.getClass().getSimpleName().startsWith("Durable")) {
                var name = firstPresent(current, "dispatcher", "name");
                return name instanceof String value ? Optional.of(value) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private List<Object> locate(String locatorTypeName) {
        return smdClass(locatorTypeName)
            .map(type -> beans(type).stream().map(locator -> invoke(locator, "locate")).toList())
            .orElse(List.of());
    }

    private List<Object> beans(Class<?> type) {
        return context.getBeanProvider(type).orderedStream().map(Object.class::cast).toList();
    }

    // The handler's method or constructor: a field of the handler record, or of an object it wraps.
    private static Executable member(Object handler) {
        var member = findMember(handler, 0);
        if (member == null) {
            throw new IllegalStateException("Found no handler method in " + handler.getClass().getName() + ": " + handler);
        }
        return member;
    }

    private static Executable findMember(Object value, int depth) {
        if (value == null || depth > 2) {
            return null;
        }
        for (var field : instanceFields(value.getClass())) {
            if (Executable.class.isAssignableFrom(field.getType())) {
                return (Executable) read(field, value);
            }
        }
        for (var field : instanceFields(value.getClass())) {
            if (field.getType().getName().startsWith("app.dodb.smd.")) {
                var member = findMember(read(field, value), depth + 1);
                if (member != null) {
                    return member;
                }
            }
        }
        return null;
    }

    private static Method interfaceMethod(Class<?> type, String name) {
        for (var method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new IllegalStateException("SMD's " + type.getName() + " has no method " + name + "(..)");
    }

    private static Object firstPresent(Object target, String... names) {
        for (var name : names) {
            var field = findField(target.getClass(), name);
            if (field.isPresent()) {
                var value = read(field.get(), target);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        return findField(target.getClass(), name)
            .map(field -> read(field, target))
            .orElseThrow(() -> new IllegalStateException("SMD's " + target.getClass().getName() + " has no field '" + name + "'"));
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        for (var current = type; current != null; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())) {
                    return Optional.of(field);
                }
            }
        }
        return Optional.empty();
    }

    private static List<Field> instanceFields(Class<?> type) {
        var fields = new ArrayList<Field>();
        for (var current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException("Failed to read " + field + " of " + target.getClass().getName(), e);
        }
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        try {
            var method = findMethod(target.getClass(), name, arguments.length);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SMD's " + target.getClass().getName() + "." + name + "() failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to call " + target.getClass().getName() + "." + name + "()", e);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (var current = type; current != null; current = current.getSuperclass()) {
            for (var method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount && !method.isBridge()) {
                    return method;
                }
            }
        }
        for (var method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
