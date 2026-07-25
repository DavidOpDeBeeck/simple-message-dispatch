package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.channel.AsyncAwaitingEventChannel;
import app.dodb.smd.api.event.channel.AsyncFireAndForgetEventChannel;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.event.channel.EventChannelBinding;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.event.channel.SubscribableEventChannel;
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
    private final Set<EventChannel> eventChannels = new LinkedHashSet<>();
    private ProcessingGroupsSpec processingGroupsSpec;

    public EventBusSpec time(TimeProvider timeProvider) {
        this.timeProvider = requireNonNull(timeProvider);
        return this;
    }

    public EventBusSpec principal(PrincipalProvider principalProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        return this;
    }

    public EventBusSpec interceptors(EventInterceptor... interceptors) {
        return interceptors(List.of(interceptors));
    }

    public EventBusSpec interceptors(List<EventInterceptor> interceptors) {
        this.interceptors.addAll(requireNonNull(interceptors));
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
        processingGroupsSpec.configure(this);
        return new EventBus(new MetadataFactory(principalProvider, timeProvider), interceptors, eventChannels);
    }

    public static class ProcessingGroupsSpec {

        private final ProcessingGroupLocator processingGroupLocator;
        private final Map<String, ProcessingGroupSpec> processingGroupSpecByName = new HashMap<>();
        private final ProcessingGroupSpec defaultProcessingGroupSpec = new ProcessingGroupSpec(this);

        public ProcessingGroupsSpec(ProcessingGroupLocator processingGroupLocator) {
            this.processingGroupLocator = requireNonNull(processingGroupLocator);
        }

        void configure(EventBusSpec eventBus) {
            var processingGroupRegistry = processingGroupLocator.locate();
            var allProcessingGroups = processingGroupRegistry.eventHandlerRegistryByProcessingGroup().keySet();

            for (var processingGroup : allProcessingGroups) {
                var listener = processingGroupRegistry.findBy(processingGroup);
                var processingGroupSpec = processingGroupSpecByName.getOrDefault(processingGroup, defaultProcessingGroupSpec);
                var channelSubscription = processingGroupSpec.channelSubscription;

                if (processingGroupSpec.disabled) {
                    LOGGER.info("Processing group '{}' is disabled. Event handlers in this group will not be executed.", processingGroup);
                    continue;
                }
                if (channelSubscription == null) {
                    throw new IllegalArgumentException("""
                        Processing group '%s' has no configuration. Event handlers in this group will \
                        not be executed. Please register an EventChannel for this processing group using \
                        ProcessingGroupsSpec.processingGroup("%s") or ProcessingGroupsSpec.anyProcessingGroup(), \
                        or disable it explicitly using .disabled().""".formatted(processingGroup, processingGroup));
                }

                channelSubscription.subscribe(listener);
                eventBus.eventChannels.add(channelSubscription.eventChannel());
            }
        }

        public ProcessingGroupSpec processingGroup(String processingGroup) {
            validateProcessingGroupIsNotYetConfigured(processingGroup);
            var spec = new ProcessingGroupSpec(this);
            processingGroupSpecByName.put(processingGroup, spec);
            return spec;
        }

        public ProcessingGroupSpec anyProcessingGroup() {
            return defaultProcessingGroupSpec;
        }

        private void validateProcessingGroupIsNotYetConfigured(String processingGroup) {
            if (processingGroupSpecByName.containsKey(processingGroup)) {
                throw new IllegalArgumentException("ProcessingGroup " + processingGroup + " is already configured");
            }
        }
    }

    public static class ProcessingGroupSpec {

        private final ProcessingGroupsSpec parent;
        private final SynchronousEventChannel synchronousEventChannel;
        private ChannelSubscription<?> channelSubscription;
        private boolean disabled;

        private ProcessingGroupSpec(ProcessingGroupsSpec parent) {
            this.parent = requireNonNull(parent);
            this.synchronousEventChannel = new SynchronousEventChannel();
        }

        public ProcessingGroupsSpec disabled() {
            this.disabled = true;
            this.channelSubscription = null;
            return parent;
        }

        public ProcessingGroupsSpec sync() {
            return channel(synchronousEventChannel);
        }

        public ProcessingGroupAsyncChannelSpec async() {
            return new ProcessingGroupAsyncChannelSpec(this);
        }

        public ProcessingGroupsSpec channel(SubscribableEventChannel channel) {
            return channel(channel, EventChannelBinding.direct());
        }

        public <C extends EventChannel> ProcessingGroupsSpec channel(C channel, EventChannelBinding<? super C> binding) {
            this.disabled = false;
            this.channelSubscription = new ChannelSubscription<>(channel, binding);
            return parent;
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

    private record ChannelSubscription<C extends EventChannel>(C eventChannel, EventChannelBinding<? super C> binding) {

        private ChannelSubscription {
            requireNonNull(eventChannel);
            requireNonNull(binding);
        }

        private void subscribe(EventChannelListener listener) {
            binding.bind(eventChannel, listener);
        }
    }
}
