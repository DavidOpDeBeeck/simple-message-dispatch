package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.query.AnnotatedQueryHandler;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryHandler;
import app.dodb.smd.api.query.QueryHandlerLocator;
import app.dodb.smd.api.query.QueryHandlerRegistry;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static app.dodb.smd.api.event.ProcessingGroup.DEFAULT;
import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static app.dodb.smd.api.metadata.MetadataTestConstants.PRINCIPAL;
import static app.dodb.smd.api.metadata.MetadataTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncAwaitingEventChannelTest {

    @Test
    void dispatch_withDifferentProcessingGroup() {
        var eventHandler = new EventHandlerWithDifferentProcessingGroup();
        var processingGroupOne = AnnotatedEventHandler.from(eventHandler).findBy("1");
        var processingGroupTwo = AnnotatedEventHandler.from(eventHandler).findBy("2");

        var channel = AsyncAwaitingEventChannel.usingVirtualThreads();
        channel.subscribe(processingGroupOne);
        channel.subscribe(processingGroupTwo);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        assertThat(eventHandler.getMethodCalled())
            .containsOnly(1, 2);
    }

    @Test
    void dispatch_withDifferentOrders() {
        var eventHandler = new EventHandlerWithDifferentOrders();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = AsyncAwaitingEventChannel.usingVirtualThreads();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        assertThat(eventHandler.getMethodCalled())
            .containsExactly(1, 2, 3);
    }

    @Test
    void dispatch_whenEventHandlerThrowsException_thenRethrow() {
        var eventHandler = new EventHandlerThatThrowsException();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = AsyncAwaitingEventChannel.usingVirtualThreads();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");

        assertThatThrownBy(() -> channel.send(EventMessage.from(event, METADATA)))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("this is an exception");
    }

    @Test
    void dispatch_withAsyncInterceptor_nestedQueryInheritsEventMetadata() {
        var queryHandler = new QueryHandlerForMetadata();
        var queryBus = QueryBusSpec.withDefaults()
            .queryHandlers(new StaticQueryHandlerLocator(AnnotatedQueryHandler.from(queryHandler)))
            .create();
        EventInterceptor interceptor = new EventInterceptor() {
            @Override
            public <E extends Event> void intercept(EventMessage<E> eventMessage, app.dodb.smd.api.event.EventInterceptorChain<E> chain) {
                queryBus.send(new QueryForMetadata());
                chain.proceed(eventMessage);
            }
        };

        var channel = AsyncAwaitingEventChannel.usingVirtualThreads(List.of(interceptor));
        channel.subscribe(new NoopListener());

        var eventMetadata = new Metadata(PRINCIPAL, TIMESTAMP, null, Map.of("key", "value"));
        var eventMessage = EventMessage.from(new EventForTest("Hello world"), eventMetadata);

        channel.send(eventMessage);

        var nestedMetadata = queryHandler.handledMetadata.get();
        assertThat(nestedMetadata).isNotNull();
        assertThat(nestedMetadata.principal()).isEqualTo(eventMetadata.principal());
        assertThat(nestedMetadata.properties()).containsEntry("key", "value");
        assertThat(nestedMetadata.parentMessageId()).isEqualTo(eventMessage.messageId());
        assertThat(nestedMetadata.timestamp()).isNotEqualTo(eventMetadata.timestamp());
    }

    public record EventForTest(String value) implements Event {
    }

    public static class EventHandlerWithDifferentProcessingGroup {

        private final List<Integer> methodCalled = new ArrayList<>();

        @EventHandler
        @ProcessingGroup("1")
        public void handle(EventForTest event) {
            methodCalled.add(1);
        }

        @EventHandler
        @ProcessingGroup("2")
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    @ProcessingGroup
    public static class EventHandlerWithDifferentOrders {

        private final List<Integer> methodCalled = new ArrayList<>();

        @EventHandler(order = 1)
        public void handle(EventForTest event) {
            methodCalled.add(1);
        }

        @EventHandler(order = 3)
        public void handle3(EventForTest event) {
            methodCalled.add(3);
        }

        @EventHandler(order = 2)
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    @ProcessingGroup
    public static class EventHandlerThatThrowsException {

        @EventHandler
        public void handle(EventForTest event) {
            throw new RuntimeException("this is an exception");
        }
    }

    private static class NoopListener implements EventChannelListener {

        @Override
        public String processingGroup() {
            return DEFAULT;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
        }
    }

    public record QueryForMetadata() implements Query<Metadata> {
    }

    public static class QueryHandlerForMetadata {

        private final AtomicReference<Metadata> handledMetadata = new AtomicReference<>();

        @QueryHandler
        public Metadata handle(QueryForMetadata query, Metadata metadata, Principal principal) {
            handledMetadata.set(metadata);
            return metadata;
        }
    }

    private record StaticQueryHandlerLocator(QueryHandlerRegistry registry) implements QueryHandlerLocator {

        @Override
        public QueryHandlerRegistry locate() {
            return registry;
        }
    }
}
