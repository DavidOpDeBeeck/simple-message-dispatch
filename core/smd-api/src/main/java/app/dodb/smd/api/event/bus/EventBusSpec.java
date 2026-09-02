package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.channel.AsyncAwaitingEventChannel;
import app.dodb.smd.api.event.channel.AsyncFireAndForgetEventChannel;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.event.channel.EventSink;
import app.dodb.smd.api.event.channel.EventSource;
import app.dodb.smd.api.event.channel.SynchronousEventChannel;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final List<EventInterceptor> interceptors = new ArrayList<>();
    private final Set<EventSink> eventSinks = new LinkedHashSet<>();
    private ProcessingGroupsSpec processingGroupsSpec;

    public EventBusSpec time(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
        return this;
    }

    public EventBusSpec principal(PrincipalProvider principalProvider) {
        this.principalProvider = principalProvider;
        return this;
    }

    public EventBusSpec interceptors(EventInterceptor... interceptors) {
        return interceptors(asList(interceptors));
    }

    public EventBusSpec interceptors(List<EventInterceptor> interceptors) {
        this.interceptors.addAll(interceptors);
        return this;
    }

    public EventBusSpec sinks(EventSink... sinks) {
        return sinks(asList(sinks));
    }

    public EventBusSpec sinks(List<EventSink> sinks) {
        this.eventSinks.addAll(sinks);
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
        var subscriptions = processingGroupsSpec.configure(this);
        for (var subscription : subscriptions) {
            subscription.subscribe();
        }
        return new EventBus(new MetadataFactory(principalProvider, timeProvider), interceptors, eventSinks);
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
            for (var processingGroup : allProcessingGroups) {
                var listener = processingGroupRegistry.findBy(processingGroup);
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

                if (spec.eventSink != null) {
                    eventBusSpec.sinks(spec.eventSink);
                }

                subscriptions.add(new EventSourceSubscription(spec.eventSource, listener));
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
            return channel(new SynchronousEventChannel());
        }

        public ProcessingGroupAsyncChannelSpec async() {
            validateNotConfigured();
            return new ProcessingGroupAsyncChannelSpec(this);
        }

        public ProcessingGroupsSpec source(EventSource source) {
            validateNotConfigured();
            this.eventSource = source;
            return parent;
        }

        public ProcessingGroupsSpec channel(EventChannel channel) {
            validateNotConfigured();
            this.eventSource = channel;
            this.eventSink = channel;
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

    private record EventSourceSubscription(EventSource source, EventChannelListener listener) {

        private EventSourceSubscription {
            requireNonNull(source);
            requireNonNull(listener);
        }

        private void subscribe() {
            source.subscribe(listener);
        }
    }

    public static class ProcessingGroupAsyncChannelSpec {

        private final ProcessingGroupSpec parent;

        private ProcessingGroupAsyncChannelSpec(ProcessingGroupSpec parent) {
            this.parent = requireNonNull(parent);
        }

        public ProcessingGroupsSpec await() {
            return parent.channel(AsyncAwaitingEventChannel.usingVirtualThreads());
        }

        public ProcessingGroupsSpec await(List<EventInterceptor> interceptors) {
            return parent.channel(AsyncAwaitingEventChannel.usingVirtualThreads(interceptors));
        }

        public ProcessingGroupsSpec await(ExecutorService executorService) {
            return parent.channel(AsyncAwaitingEventChannel.using(executorService));
        }

        public ProcessingGroupsSpec await(ExecutorService executorService, List<EventInterceptor> interceptors) {
            return parent.channel(AsyncAwaitingEventChannel.using(executorService, interceptors));
        }

        public ProcessingGroupsSpec fireAndForget() {
            return parent.channel(AsyncFireAndForgetEventChannel.usingVirtualThreads());
        }

        public ProcessingGroupsSpec fireAndForget(List<EventInterceptor> interceptors) {
            return parent.channel(AsyncFireAndForgetEventChannel.usingVirtualThreads(interceptors));
        }

        public ProcessingGroupsSpec fireAndForget(ExecutorService executorService) {
            return parent.channel(AsyncFireAndForgetEventChannel.using(executorService));
        }

        public ProcessingGroupsSpec fireAndForget(ExecutorService executorService, List<EventInterceptor> interceptors) {
            return parent.channel(AsyncFireAndForgetEventChannel.using(executorService, interceptors));
        }
    }
}
