package app.dodb.smd.artifactexport;

import app.dodb.smd.artifactexport.Bytecode.MethodRef;
import app.dodb.smd.artifactexport.SmdRegistries.CommandHandler;
import app.dodb.smd.artifactexport.SmdRegistries.EventHandler;
import app.dodb.smd.artifactexport.SmdRegistries.QueryHandler;
import org.springframework.asm.ClassReader;
import org.springframework.asm.Type;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;

// Builds the SMD Artifact (changeviz docs/smd-artifact.md) of a running application. The registries give the
// handlers, groups and media; the bytecode of the handler methods gives what they publish, send and read.
final class SmdArtifact {

    private final ApplicationContext context;
    private final ClassLoader classLoader;
    private final SmdRegistries registries;
    private final Bytecode bytecode;
    private final Class<?> mainClass;
    private final Set<String> applicationRoots = new TreeSet<>();
    private final Map<String, Boolean> applicationTypes = new HashMap<>();
    private final Class<?> eventType;
    private final Class<?> commandType;
    private final Optional<Class<?>> queryType;
    private final Map<Class<?>, String> names = new HashMap<>();

    SmdArtifact(ApplicationContext context, Class<?> mainClass) {
        this.context = context;
        this.classLoader = context.getClassLoader();
        this.registries = new SmdRegistries(context);
        this.bytecode = new Bytecode(classLoader);
        this.mainClass = mainClass;
        this.eventType = registries.smdClass("app.dodb.smd.api.event.Event").orElseThrow(() -> new IllegalStateException("SMD is not on the classpath"));
        this.commandType = registries.smdClass("app.dodb.smd.api.command.Command").orElseThrow();
        this.queryType = registries.smdClass("app.dodb.smd.api.query.Query");
    }

    Map<String, Object> build(String app) {
        var commandHandlers = registries.commandHandlers();
        var eventHandlers = registries.eventHandlers();
        var queryHandlers = registries.queryHandlers();
        addApplicationRoot(mainClass);
        commandHandlers.forEach(handler -> addApplicationRoot(handler.member().getDeclaringClass()));
        eventHandlers.forEach(handler -> addApplicationRoot(handler.member().getDeclaringClass()));
        queryHandlers.forEach(handler -> addApplicationRoot(handler.member().getDeclaringClass()));

        var handlers = handlers(commandHandlers);
        var groups = groups(eventHandlers);
        var queries = queries(queryHandlers, groups);
        var eventTypes = eventTypes(groups.values(), handlers);
        assignNames(handlers, groups.values(), queries, eventTypes);

        var artifact = new LinkedHashMap<String, Object>();
        artifact.put("smd", 1);
        artifact.put("app", app);
        artifact.put("eventTypes", eventTypesJson(eventTypes));
        artifact.put("handlers", handlersJson(handlers));
        artifact.put("groups", groupsJson(groups.values()));
        artifact.put("queries", queriesJson(queries));
        return artifact;
    }

    // Handlers

    private record HandledCommand(Class<?> command, List<Class<?>> publishes, String fingerprint) {
    }

    private record Handler(Class<?> type, boolean aggregate, List<HandledCommand> commands) {
    }

    private List<Handler> handlers(List<CommandHandler> commandHandlers) {
        var byClass = commandHandlers.stream()
            .collect(groupingBy(handler -> handler.member().getDeclaringClass(), LinkedHashMap::new, toCollection(ArrayList::new)));
        var handlers = new ArrayList<Handler>();
        byClass.forEach((type, ofClass) -> {
            var commands = new ArrayList<HandledCommand>();
            for (CommandHandler handler : ofClass) {
                var reach = reach(List.of(handler.member()));
                commands.add(new HandledCommand(handler.commandType(), messagesOf(reach, eventType), reach.fingerprint(internalName(type))));
            }
            handlers.add(new Handler(type, isAggregate(type), commands));
        });
        return handlers;
    }

    private static boolean isAggregate(Class<?> type) {
        return Stream.of(type.getAnnotations())
            .map(Annotation::annotationType)
            .anyMatch(annotation -> annotation.getName().startsWith("app.dodb.smd.") && annotation.getSimpleName().equals("Aggregate"));
    }

    // Processing groups

    private record Group(String name, SmdRegistries.Medium medium, List<Class<?>> handles, List<Class<?>> sends, String fingerprint,
                         Set<String> classes, Set<String> written) {
    }

    private Map<String, Group> groups(List<EventHandler> eventHandlers) {
        var byGroup = eventHandlers.stream()
            .collect(groupingBy(EventHandler::processingGroup, TreeMap::new, toCollection(ArrayList::new)));
        var configurations = registries.groupConfigurations(byGroup.keySet());

        var groups = new TreeMap<String, Group>();
        byGroup.forEach((name, handlers) -> {
            var configuration = configurations.get(name);
            if (configuration.disabled()) {
                return;
            }
            handlers.sort(comparing((EventHandler handler) -> handler.member().getDeclaringClass().getName()).thenComparing(handler -> MethodRef.of(handler.member()).key()));
            var members = handlers.stream().map(EventHandler::member).toList();
            var reach = reach(members);
            var fingerprints = members.stream()
                .map(member -> member.getDeclaringClass().getName() + "#" + MethodRef.of(member).key() + "=" + reach(List.of(member)).fingerprint(internalName(member.getDeclaringClass())))
                .toList();
            var handles = handlers.stream().map(EventHandler::eventType).distinct().toList();
            var classes = new TreeSet<String>();
            members.forEach(member -> classes.addAll(applicationTypesOf(member.getDeclaringClass())));
            groups.put(name, new Group(name, configuration.medium(), handles, messagesOf(reach, commandType), CodeReach.combinedHash(fingerprints),
                classes, withoutMessages(reach.writtenOwners())));
        });
        return groups;
    }

    // Queries

    private record HandledQuery(Class<?> query, List<String> reads, String fingerprint) {
    }

    private record Queries(Class<?> type, List<HandledQuery> queries) {
    }

    // A query reads a group when its code calls the group's own handler class (or an application interface the class
    // implements), or calls a type the group's event handlers write to with a save/insert/update/... method. Calls
    // through a library (a JdbcTemplate, a jOOQ DSLContext) don't link a query to a group.
    private List<Queries> queries(List<QueryHandler> queryHandlers, Map<String, Group> groups) {
        var byClass = queryHandlers.stream()
            .collect(groupingBy(handler -> handler.member().getDeclaringClass(), LinkedHashMap::new, toCollection(ArrayList::new)));
        var result = new ArrayList<Queries>();
        byClass.forEach((type, ofClass) -> {
            var queries = new ArrayList<HandledQuery>();
            for (QueryHandler handler : ofClass) {
                var reach = reach(List.of(handler.member()));
                var used = withoutMessages(reach.invokedOwners());
                used.addAll(applicationTypesOf(type));
                var reads = groups.values().stream()
                    .filter(group -> used.stream().anyMatch(owner -> group.classes().contains(owner) || group.written().contains(owner)))
                    .map(Group::name)
                    .sorted()
                    .toList();
                queries.add(new HandledQuery(handler.queryType(), reads, reach.fingerprint(internalName(type))));
            }
            result.add(new Queries(type, queries));
        });
        return result;
    }

    // Event types: for each type a group handles, the concrete events it covers, when that is more than itself.

    private Map<Class<?>, List<Class<?>>> eventTypes(Collection<Group> groups, List<Handler> handlers) {
        var known = new TreeSet<Class<?>>(comparing(Class::getName));
        known.addAll(scanEvents());
        handlers.forEach(handler -> handler.commands().forEach(command -> known.addAll(command.publishes())));
        groups.forEach(group -> known.addAll(group.handles()));

        var eventTypes = new HashMap<Class<?>, List<Class<?>>>();
        groups.stream().flatMap(group -> group.handles().stream()).distinct().forEach(handled -> {
            var covered = known.stream()
                .filter(event -> event != handled && handled.isAssignableFrom(event) && isConcrete(event))
                .toList();
            if (!covered.isEmpty()) {
                eventTypes.put(handled, covered);
            }
        });
        return eventTypes;
    }

    private List<Class<?>> scanEvents() {
        var resolver = new PathMatchingResourcePatternResolver(classLoader);
        var events = new ArrayList<Class<?>>();
        for (var root : applicationRoots) {
            try {
                for (var resource : resolver.getResources(root + "**/*.class")) {
                    try (var stream = resource.getInputStream()) {
                        load(new ClassReader(stream).getClassName())
                            .filter(type -> eventType.isAssignableFrom(type) && isConcrete(type))
                            .ifPresent(events::add);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to scan " + root + " for events", e);
            }
        }
        return events;
    }

    // Code

    private CodeReach reach(List<Executable> members) {
        var roots = members.stream().map(MethodRef::of).toList();
        return CodeReach.from(roots, bytecode, isApplicationType());
    }

    // Application code is what comes from the same class folders or jars as the main class and the handlers; the
    // rest of the classpath is libraries.
    private Predicate<String> isApplicationType() {
        return internalName -> applicationTypes.computeIfAbsent(internalName, name -> root(name).map(applicationRoots::contains).orElse(false));
    }

    private void addApplicationRoot(Class<?> type) {
        root(internalName(type)).ifPresent(applicationRoots::add);
    }

    private Optional<String> root(String internalName) {
        if (internalName.startsWith("[")) {
            return Optional.empty();
        }
        var resource = internalName + ".class";
        var url = classLoader.getResource(resource);
        if (url == null) {
            return Optional.empty();
        }
        var location = url.toString();
        return location.endsWith(resource) ? Optional.of(location.substring(0, location.length() - resource.length())) : Optional.empty();
    }

    // The concrete messages of a kind (events or commands) among the types the code creates.
    private List<Class<?>> messagesOf(CodeReach reach, Class<?> messageType) {
        return reach.createdTypes().stream()
            .map(this::load)
            .flatMap(Optional::stream)
            .filter(type -> messageType.isAssignableFrom(type) && isConcrete(type))
            .distinct()
            .sorted(comparing(Class::getName))
            .toList();
    }

    private Set<String> withoutMessages(Set<String> owners) {
        var result = new TreeSet<String>();
        for (var owner : owners) {
            var type = load(owner);
            var message = type.isPresent() && (eventType.isAssignableFrom(type.get()) || commandType.isAssignableFrom(type.get())
                || queryType.map(query -> query.isAssignableFrom(type.get())).orElse(false));
            if (!message) {
                result.add(owner);
            }
        }
        return result;
    }

    // The class and every superclass and interface of it that the application declares, as internal names.
    private Set<String> applicationTypesOf(Class<?> type) {
        var types = new TreeSet<String>();
        var pending = new ArrayList<Class<?>>(List.of(type));
        while (!pending.isEmpty()) {
            var current = pending.removeLast();
            var name = internalName(current);
            if (!isApplicationType().test(name) || !types.add(name)) {
                continue;
            }
            if (current.getSuperclass() != null) {
                pending.add(current.getSuperclass());
            }
            pending.addAll(List.of(current.getInterfaces()));
        }
        return types;
    }

    private Optional<Class<?>> load(String internalName) {
        try {
            return Optional.of(Class.forName(internalName.replace('/', '.'), false, classLoader));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static boolean isConcrete(Class<?> type) {
        return !type.isInterface() && !Modifier.isAbstract(type.getModifiers());
    }

    private static String internalName(Class<?> type) {
        return Type.getInternalName(type);
    }

    // Names: a class is named without its package, unless another class in the Artifact has that name too.

    private void assignNames(List<Handler> handlers, Collection<Group> groups, List<Queries> queries, Map<Class<?>, List<Class<?>>> eventTypes) {
        var classes = new TreeSet<Class<?>>(comparing(Class::getName));
        handlers.forEach(handler -> {
            classes.add(handler.type());
            handler.commands().forEach(command -> {
                classes.add(command.command());
                classes.addAll(command.publishes());
            });
        });
        groups.forEach(group -> {
            classes.addAll(group.handles());
            classes.addAll(group.sends());
        });
        queries.forEach(query -> {
            classes.add(query.type());
            query.queries().forEach(handled -> classes.add(handled.query()));
        });
        eventTypes.forEach((type, events) -> {
            classes.add(type);
            classes.addAll(events);
        });

        var bySimpleName = classes.stream().collect(groupingBy(SmdArtifact::simpleName));
        bySimpleName.forEach((simpleName, sameName) -> sameName.forEach(type -> names.put(type, sameName.size() == 1 ? simpleName : type.getName())));
    }

    private static String simpleName(Class<?> type) {
        var name = type.getName();
        var packageName = type.getPackageName();
        return (packageName.isEmpty() ? name : name.substring(packageName.length() + 1)).replace('$', '.');
    }

    private String name(Class<?> type) {
        return names.get(type);
    }

    private List<String> sortedNames(Collection<Class<?>> types) {
        return types.stream().map(this::name).distinct().sorted().toList();
    }

    // JSON

    private Map<String, Object> eventTypesJson(Map<Class<?>, List<Class<?>>> eventTypes) {
        var json = new TreeMap<String, Object>();
        eventTypes.forEach((type, events) -> json.put(name(type), sortedNames(events)));
        return json;
    }

    private List<Object> handlersJson(List<Handler> handlers) {
        return handlers.stream()
            .sorted(comparing(handler -> name(handler.type())))
            .map(handler -> {
                var json = new LinkedHashMap<String, Object>();
                json.put("class", name(handler.type()));
                if (handler.aggregate()) {
                    json.put("aggregate", true);
                }
                json.put("commands", handler.commands().stream()
                    .sorted(comparing(command -> name(command.command())))
                    .map(command -> {
                        var commandJson = new LinkedHashMap<String, Object>();
                        commandJson.put("command", name(command.command()));
                        commandJson.put("publishes", sortedNames(command.publishes()));
                        commandJson.put("fingerprint", command.fingerprint());
                        return (Object) commandJson;
                    })
                    .toList());
                return (Object) json;
            })
            .toList();
    }

    private List<Object> groupsJson(Collection<Group> groups) {
        return groups.stream()
            .sorted(comparing(Group::name))
            .map(group -> {
                var json = new LinkedHashMap<String, Object>();
                json.put("name", group.name());
                json.put("medium", group.medium().name());
                group.medium().dispatcher().ifPresent(dispatcher -> json.put("dispatcher", dispatcher));
                json.put("handles", sortedNames(group.handles()));
                json.put("sends", sortedNames(group.sends()));
                json.put("fingerprint", group.fingerprint());
                return (Object) json;
            })
            .toList();
    }

    private List<Object> queriesJson(List<Queries> queries) {
        return queries.stream()
            .sorted(comparing(query -> name(query.type())))
            .map(query -> {
                var json = new LinkedHashMap<String, Object>();
                json.put("class", name(query.type()));
                json.put("queries", query.queries().stream()
                    .sorted(comparing(handled -> name(handled.query())))
                    .map(handled -> {
                        var queryJson = new LinkedHashMap<String, Object>();
                        queryJson.put("query", name(handled.query()));
                        queryJson.put("reads", handled.reads());
                        queryJson.put("fingerprint", handled.fingerprint());
                        return (Object) queryJson;
                    })
                    .toList());
                return (Object) json;
            })
            .toList();
    }
}
