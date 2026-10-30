package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.ProcessingGroupInterceptor;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.ProcessingGroupRegistry.EventHandlerRegistry;
import app.dodb.smd.api.event.delivery.ConcurrentEventDispatcher;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventRoute;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.event.delivery.FireAndForgetEventDispatcher;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.defaultSynchronous;
import static com.google.common.collect.Sets.difference;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

public class EventBusSpec {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventBusSpec.class);

    public static EventBusSpec withDefaults() {
        return new EventBusSpec()
            .time(new SystemTimeProvider())
            .principal(new SimplePrincipalProvider());
    }

    public static EventBusSpec withoutDefaults() {
        return new EventBusSpec();
    }

    private EventBusSpec() {
    }

    private TimeProvider timeProvider;
    private PrincipalProvider principalProvider;
    private final List<EventBusInterceptor> interceptors = new ArrayList<>();
    private final List<EventSinkInterceptor> sinkInterceptors = new ArrayList<>();
    private final List<ProcessingGroupInterceptor> processingGroupInterceptors = new ArrayList<>();
    private final Set<EventSink> eventSinks = new LinkedHashSet<>();
    private final List<EventRoute> routes = new ArrayList<>();
    private ProcessingGroupsSpec processingGroupsSpec;

    public EventBusSpec time(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
        return this;
    }

    public EventBusSpec principal(PrincipalProvider principalProvider) {
        this.principalProvider = principalProvider;
        return this;
    }

    public EventBusSpec interceptors(EventBusInterceptor... interceptors) {
        return interceptors(List.of(interceptors));
    }

    public EventBusSpec interceptors(List<EventBusInterceptor> interceptors) {
        this.interceptors.addAll(interceptors);
        return this;
    }

    public EventBusSpec sinkInterceptors(EventSinkInterceptor... sinkInterceptors) {
        return sinkInterceptors(List.of(sinkInterceptors));
    }

    public EventBusSpec sinkInterceptors(List<EventSinkInterceptor> sinkInterceptors) {
        this.sinkInterceptors.addAll(sinkInterceptors);
        return this;
    }

    public EventBusSpec processingGroupInterceptors(ProcessingGroupInterceptor... processingGroupInterceptors) {
        return processingGroupInterceptors(List.of(processingGroupInterceptors));
    }

    public EventBusSpec processingGroupInterceptors(List<ProcessingGroupInterceptor> processingGroupInterceptors) {
        this.processingGroupInterceptors.addAll(processingGroupInterceptors);
        return this;
    }

    public EventBusSpec sinks(EventSink... sinks) {
        return sinks(asList(sinks));
    }

    public EventBusSpec sinks(List<EventSink> sinks) {
        this.eventSinks.addAll(sinks);
        return this;
    }

    public EventBusSpec route(String name, EventSource source, EventSink sink) {
        return routes(new EventRoute(name, source, sink));
    }

    public EventBusSpec routes(EventRoute... routes) {
        return routes(List.of(routes));
    }

    public EventBusSpec routes(List<EventRoute> routes) {
        this.routes.addAll(routes);
        return this;
    }

    public EventBusSpec processingGroups(ProcessingGroupLocator locator) {
        return processingGroups(locator, defaultSynchronous());
    }

    public EventBusSpec processingGroups(ProcessingGroupLocator locator, ProcessingGroupsConfigurer processingGroupsConfigurer) {
        this.processingGroupsSpec = new ProcessingGroupsSpec(locator);
        processingGroupsConfigurer.configure(processingGroupsSpec);
        return this;
    }

    public EventBus create() {
        var subscriptions = new ArrayList<EventSourceSubscription>();
        if (processingGroupsSpec != null) {
            subscriptions.addAll(processingGroupsSpec.configure(this));
        }
        for (var route : routes) {
            subscriptions.add(new EventSourceSubscription(route.source(), new RouteSubscriber(route, sinkInterceptors)));
        }
        validateUniqueSubscriberNames(subscriptions);

        for (var subscription : subscriptions) {
            // These subscriptions need to be closed when the application shuts down, but for now we just subscribe to them and let the application exit.
            subscription.subscribe();
        }
        return new EventBus(new MetadataFactory(principalProvider, timeProvider), interceptors, sinkInterceptors, eventSinks);
    }

    private static void validateUniqueSubscriberNames(List<EventSourceSubscription> subscriptions) {
        var names = new HashSet<String>();
        for (var subscription : subscriptions) {
            var name = subscription.subscriber().name();
            if (!names.add(name)) {
                throw new IllegalArgumentException("""
                    Event route '%s' has the name of another route or processing group. Sources name their subscriptions after it, \
                    so give every route a name that no other route or processing group uses.""".formatted(name));
            }
        }
    }

    public static class ProcessingGroupsSpec {

        private final ProcessingGroupLocator processingGroupLocator;
        private final Map<String, ProcessingGroupSpec> processingGroupSpecByName = new HashMap<>();
        private final ProcessingGroupSpec defaultProcessingGroupSpec = new ProcessingGroupSpec(this, "anyProcessingGroup()");

        public ProcessingGroupsSpec(ProcessingGroupLocator processingGroupLocator) {
            this.processingGroupLocator = requireNonNull(processingGroupLocator);
        }

        private List<EventSourceSubscription> configure(EventBusSpec eventBusSpec) {
            var processingGroupRegistry = processingGroupLocator.locate();
            var allProcessingGroups = processingGroupRegistry.eventHandlerRegistryByProcessingGroup().keySet();

            var notLocatedProcessingGroups = difference(processingGroupSpecByName.keySet(), allProcessingGroups);
            if (!notLocatedProcessingGroups.isEmpty()) {
                throw new IllegalArgumentException("""
                    Processing groups '%s' are configured but not located by the ProcessingGroupLocator. \
                    Please ensure that the ProcessingGroupLocator locates all configured processing groups, or remove the configuration for these processing groups.\
                    """.formatted(notLocatedProcessingGroups)
                );
            }

            var subscriptions = new ArrayList<EventSourceSubscription>();
            var registriesBySink = new LinkedHashMap<EventSink, List<EventHandlerRegistry>>();
            for (var processingGroup : allProcessingGroups) {
                var spec = processingGroupSpecByName.getOrDefault(processingGroup, defaultProcessingGroupSpec);

                if (spec.disabled) {
                    LOGGER.info("Processing group '{}' is disabled. Event handlers in this group will not be executed.", processingGroup);
                    continue;
                }

                if (spec.eventSource == null) {
                    throw new IllegalArgumentException("""
                        Processing group '%s' has no configuration. Event handlers in this group will \
                        not be executed. Please register a source or channel for this processing group using \
                        ProcessingGroupsSpec.processingGroup("%s") or ProcessingGroupsSpec.anyProcessingGroup(), \
                        or disable it explicitly using .disabled().""".formatted(processingGroup, processingGroup));
                }

                var registry = processingGroupRegistry.findBy(processingGroup);
                if (spec.eventSink != null) {
                    registriesBySink.computeIfAbsent(spec.eventSink, _ -> new ArrayList<>()).add(registry);
                }

                var subscriber = new ProcessingGroupSubscriber(registry, spec.eventSource, eventBusSpec.processingGroupInterceptors);
                subscriptions.add(new EventSourceSubscription(spec.eventSource, subscriber));
            }

            for (var entry : registriesBySink.entrySet()) {
                var sink = entry.getKey();
                var registries = entry.getValue();
                eventBusSpec.sinks(sink.selecting(eventMessage -> registries.stream().anyMatch(registry -> registry.handles(eventMessage))));
            }
            return subscriptions;
        }

        public ProcessingGroupSpec processingGroup(String processingGroup) {
            validateProcessingGroupIsNotYetConfigured(processingGroup);
            var spec = new ProcessingGroupSpec(this, processingGroup);
            processingGroupSpecByName.put(processingGroup, spec);
            return spec;
        }

        public ProcessingGroupSpec anyProcessingGroup() {
            return defaultProcessingGroupSpec;
        }

        private void validateProcessingGroupIsNotYetConfigured(String processingGroup) {
            if (processingGroupSpecByName.containsKey(processingGroup)) {
                throw new IllegalArgumentException("Processing group '" + processingGroup + "' is already configured");
            }
        }
    }

    public static class ProcessingGroupSpec {

        private final ProcessingGroupsSpec parent;
        private final String name;
        private EventSource eventSource;
        private EventSink eventSink;
        private boolean disabled;

        private ProcessingGroupSpec(ProcessingGroupsSpec parent, String name) {
            this.parent = requireNonNull(parent);
            this.name = requireNonNull(name);
        }

        public ProcessingGroupsSpec disabled() {
            validateNotConfigured();
            this.disabled = true;
            return parent;
        }

        public ProcessingGroupsSpec sync() {
            return medium(new SynchronousEventDispatcher());
        }

        public ProcessingGroupAsyncSpec async() {
            validateNotConfigured();
            return new ProcessingGroupAsyncSpec(this);
        }

        public ProcessingGroupsSpec source(EventSource source) {
            validateNotConfigured();
            this.eventSource = source;
            return parent;
        }

        public ProcessingGroupsSpec medium(EventMedium medium) {
            validateNotConfigured();
            this.eventSource = medium.outlet();
            this.eventSink = medium.inlet();
            return parent;
        }

        private void validateNotConfigured() {
            if (disabled || eventSource != null) {
                throw new IllegalArgumentException("""
                    Processing-group configuration '%s' is already configured; choose one source, channel, or disable it.
                    """.formatted(name));
            }
        }
    }

    private record EventSourceSubscription(EventSource source, EventSubscriber subscriber) {

        private EventSourceSubscription {
            requireNonNull(source);
            requireNonNull(subscriber);
        }

        private EventSubscription subscribe() {
            return source.subscribe(subscriber);
        }
    }

    public static class ProcessingGroupAsyncSpec {

        private final ProcessingGroupSpec parent;

        private ProcessingGroupAsyncSpec(ProcessingGroupSpec parent) {
            this.parent = requireNonNull(parent);
        }

        public ProcessingGroupsSpec await() {
            return parent.medium(ConcurrentEventDispatcher.usingVirtualThreads());
        }

        public ProcessingGroupsSpec await(ExecutorService executorService) {
            return parent.medium(ConcurrentEventDispatcher.using(executorService));
        }

        public ProcessingGroupsSpec fireAndForget() {
            return parent.medium(FireAndForgetEventDispatcher.usingVirtualThreads());
        }

        public ProcessingGroupsSpec fireAndForget(ExecutorService executorService) {
            return parent.medium(FireAndForgetEventDispatcher.using(executorService));
        }
    }
}
