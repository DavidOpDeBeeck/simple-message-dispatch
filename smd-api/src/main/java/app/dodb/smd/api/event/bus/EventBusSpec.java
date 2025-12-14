package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.channel.BlockingEventChannel;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.event.channel.NonBlockingEventChannel;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.datetime.DatetimeProvider;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.defaultBlocking;
import static java.util.Objects.requireNonNull;

public class EventBusSpec {

    public static EventBusSpec withDefaults() {
        return new EventBusSpec()
            .datetime(new LocalDatetimeProvider())
            .principal(new SimplePrincipalProvider());
    }

    public static EventBusSpec withoutDefaults() {
        return new EventBusSpec();
    }

    private EventBusSpec() {
    }

    private DatetimeProvider datetimeProvider;
    private PrincipalProvider principalProvider;
    private final List<EventBusInterceptor> interceptors = new ArrayList<>();
    private final Set<EventChannel> eventChannels = new LinkedHashSet<>();
    private ProcessingGroupsSpec processingGroupsSpec;

    public EventBusSpec datetime(DatetimeProvider datetimeProvider) {
        this.datetimeProvider = requireNonNull(datetimeProvider);
        return this;
    }

    public EventBusSpec principal(PrincipalProvider principalProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        return this;
    }

    public EventBusSpec interceptors(EventBusInterceptor... interceptors) {
        return interceptors(List.of(interceptors));
    }

    public EventBusSpec interceptors(List<EventBusInterceptor> interceptors) {
        this.interceptors.addAll(requireNonNull(interceptors));
        return this;
    }

    public EventBusSpec processingGroups(ProcessingGroupLocator locator) {
        return processingGroups(locator, defaultBlocking());
    }

    public EventBusSpec processingGroups(ProcessingGroupLocator locator, ProcessingGroupsConfigurer processingGroupsConfigurer) {
        this.processingGroupsSpec = new ProcessingGroupsSpec(locator);
        processingGroupsConfigurer.configure(processingGroupsSpec);
        return this;
    }

    public EventBus create() {
        processingGroupsSpec.configure(this);
        return new EventBus(new MetadataFactory(principalProvider, datetimeProvider), interceptors, eventChannels);
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
                var processingGroupSpec = processingGroupSpecByName.getOrDefault(processingGroup, defaultProcessingGroupSpec);
                var eventChannel = processingGroupSpec.channel;
                if (eventChannel == null) {
                    return;
                }

                var listener = processingGroupRegistry.findBy(processingGroup);
                eventChannel.subscribe(listener);
                eventBus.eventChannels.add(eventChannel);
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

        private final BlockingEventChannel blockingEventChannel = BlockingEventChannel.usingVirtualThreads();
        private final NonBlockingEventChannel nonBlockingEventChannel = NonBlockingEventChannel.usingVirtualThreads();

        private final ProcessingGroupsSpec parent;
        private EventChannel channel;

        private ProcessingGroupSpec(ProcessingGroupsSpec parent) {
            this.parent = requireNonNull(parent);
        }

        public ProcessingGroupsSpec disabled() {
            return parent;
        }

        public ProcessingGroupsSpec blocking() {
            return channel(blockingEventChannel);
        }

        public ProcessingGroupsSpec nonBlocking() {
            return this.channel(nonBlockingEventChannel);
        }

        public ProcessingGroupsSpec channel(EventChannel channel) {
            this.channel = channel;
            return parent;
        }
    }
}
