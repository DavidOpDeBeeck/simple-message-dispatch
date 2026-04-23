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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void dispatch_whenOneListenerFails_waitsForOtherListenersBeforeThrowing() throws InterruptedException {
        var completed = new CountDownLatch(1);
        var allowCompletion = new CountDownLatch(1);
        var channel = AsyncAwaitingEventChannel.usingVirtualThreads();
        channel.subscribe(new FailingListener("fast failure"));
        channel.subscribe(new AwaitingListener(completed, allowCompletion));

        var sendThreadFailure = new AtomicReference<Throwable>();
        var sendThread = Thread.ofPlatform().start(() -> {
            try {
                channel.send(EventMessage.from(new EventForTest("Hello world"), METADATA));
            } catch (Throwable throwable) {
                sendThreadFailure.set(throwable);
            }
        });

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sendThread.isAlive()).isTrue();

        allowCompletion.countDown();
        sendThread.join(5000);

        assertThat(sendThread.isAlive()).isFalse();
        assertThat(sendThreadFailure.get())
            .isInstanceOf(RuntimeException.class)
            .hasMessage("fast failure");
    }

    @Test
    void dispatch_whenMultipleListenersFail_addsSuppressedFailures() {
        var channel = AsyncAwaitingEventChannel.usingVirtualThreads();
        channel.subscribe(new FailingListener("first failure"));
        channel.subscribe(new FailingListener("second failure"));

        assertThatThrownBy(() -> channel.send(EventMessage.from(new EventForTest("Hello world"), METADATA)))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("first failure")
            .satisfies(throwable -> assertThat(throwable.getSuppressed())
                .singleElement()
                .satisfies(suppressed -> assertThat(suppressed).hasMessage("second failure")));
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

        private final List<Integer> methodCalled = new CopyOnWriteArrayList<>();

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

    private static class FailingListener implements EventChannelListener {

        private final String message;

        private FailingListener(String message) {
            this.message = message;
        }

        @Override
        public String processingGroup() {
            return DEFAULT;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            throw new RuntimeException(message);
        }
    }

    private static class AwaitingListener implements EventChannelListener {

        private final CountDownLatch completed;
        private final CountDownLatch allowCompletion;

        private AwaitingListener(CountDownLatch completed, CountDownLatch allowCompletion) {
            this.completed = completed;
            this.allowCompletion = allowCompletion;
        }

        @Override
        public String processingGroup() {
            return DEFAULT;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            completed.countDown();

            try {
                assertThat(allowCompletion.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
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
