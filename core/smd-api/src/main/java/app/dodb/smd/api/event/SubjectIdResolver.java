package app.dodb.smd.api.event;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.LoggingUtils.logMethods;
import static java.util.Arrays.stream;
import static java.util.stream.Stream.concat;

final class SubjectIdResolver {

    private static final ConcurrentMap<Class<?>, Optional<Method>> SUBJECT_ACCESSORS = new ConcurrentHashMap<>();

    private SubjectIdResolver() {
    }

    static Optional<String> resolve(Event event) {
        var eventType = event.getClass();
        return SUBJECT_ACCESSORS.computeIfAbsent(eventType, SubjectIdResolver::findSubjectIdAccessor)
            .flatMap(accessor -> resolve(event, accessor));
    }

    private static Optional<String> resolve(Event event, Method accessor) {
        var subjectIdOpt = invoke(event, accessor);
        if (subjectIdOpt.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("""
                Invalid event: @SubjectId must resolve to a non-blank value.

                    Event:
                    %s

                    Accessor:
                    %s
                """.formatted(logClass(event.getClass()), logMethod(accessor)));
        }
        return subjectIdOpt;
    }

    private static Optional<String> invoke(Event event, Method accessor) {
        try {
            return switch (accessor.invoke(event)) {
                case null -> Optional.empty();
                case String string -> Optional.of(string);
                case Optional<?> optional -> optional.map(Object::toString);
                case Object object -> Optional.of(object.toString());
            };
        } catch (Exception exception) {
            throw new IllegalArgumentException("""
                Invalid event: something went wrong when resolving the subjectId.

                    Event:
                    %s

                    Accessors:
                    %s
                """.formatted(logClass(event.getClass()), logMethod(accessor)), exception);
        }
    }

    private static Optional<Method> findSubjectIdAccessor(Class<?> eventType) {
        var accessors = concat(
            stream(eventType.getMethods()),
            stream(eventType.getDeclaredMethods())
        )
            .distinct()
            .filter(method -> method.isAnnotationPresent(SubjectId.class))
            .toList();

        if (accessors.isEmpty()) {
            return Optional.empty();
        }

        if (accessors.size() != 1) {
            throw new IllegalArgumentException("""
                Invalid event: exactly one @SubjectId method or record component may be declared.

                    Event:
                    %s

                    Accessors:
                    %s
                """.formatted(logClass(eventType), logMethods(accessors)));
        }

        var accessor = accessors.getFirst();
        if (Modifier.isStatic(accessor.getModifiers()) || accessor.getParameterCount() != 0 || accessor.getReturnType() == Void.TYPE) {
            throw new IllegalArgumentException("""
                Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.

                    Event:
                    %s

                    Accessor:
                    %s
                """.formatted(logClass(eventType), logMethod(accessor)));
        }

        if (!accessor.trySetAccessible()) {
            throw new IllegalArgumentException("""
                Invalid event: @SubjectId accessor is not accessible.

                    Event:
                    %s

                    Accessor:
                    %s
                """.formatted(logClass(eventType), logMethod(accessor)));
        }
        return Optional.of(accessor);
    }
}
