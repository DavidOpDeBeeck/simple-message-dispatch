package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.command.AnnotatedCommandHandler;
import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.CommandHandlerRegistry;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static app.dodb.smd.api.event.ProcessingGroup.DEFAULT;
import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static app.dodb.smd.api.metadata.MetadataTestConstants.PRINCIPAL;
import static app.dodb.smd.api.metadata.MetadataTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AsyncFireAndForgetEventChannelTest {

    @Test
    void dispatch_withDifferentProcessingGroup() {
        var eventHandler = new EventHandlerWithDifferentProcessingGroup();
        var processingGroupOne = AnnotatedEventHandler.from(eventHandler).findBy("1");
        var processingGroupTwo = AnnotatedEventHandler.from(eventHandler).findBy("2");

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(processingGroupOne);
        channel.subscribe(processingGroupTwo);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1, 2));
    }

    @Test
    void dispatch_withDifferentOrders() {
        var eventHandler = new EventHandlerWithDifferentOrders();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1, 2, 3));
    }

    @Test
    void dispatch_whenEventHandlerThrowsExceptionInSameProcessingGroup_thenStopProcessingGroupExecution() {
        var eventHandler = new EventHandlerThatThrowsExceptionInSameProcessingGroup();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1));
    }

    @Test
    void dispatch_whenEventHandlerThrowsExceptionInDifferentProcessingGroup_thenStopProcessingGroupExecution() {
        var eventHandler = new EventHandlerThatThrowsExceptionInDifferentProcessingGroup();
        var processingGroupOne = AnnotatedEventHandler.from(eventHandler).findBy("1");
        var processingGroupTwo = AnnotatedEventHandler.from(eventHandler).findBy("2");

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(processingGroupOne);
        channel.subscribe(processingGroupTwo);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1, 2));
    }

    @Test
    void dispatch_withAsyncListener_nestedCommandInheritsEventMetadata() {
        var commandHandler = new CommandHandlerForMetadata();
        var commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new StaticCommandHandlerLocator(AnnotatedCommandHandler.from(commandHandler)))
            .create();

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(new NestedCommandDispatchingListener(commandBus));

        var eventMetadata = new Metadata(PRINCIPAL, TIMESTAMP, null, Map.of("key", "value"));
        var eventMessage = EventMessage.from(new EventForTest("Hello world"), eventMetadata);

        channel.send(eventMessage);

        await().untilAsserted(() -> {
            var nestedMetadata = commandHandler.handledMetadata.get();
            assertThat(nestedMetadata).isNotNull();
            assertThat(nestedMetadata.principal()).isEqualTo(eventMetadata.principal());
            assertThat(nestedMetadata.properties()).containsEntry("key", "value");
            assertThat(nestedMetadata.parentMessageId()).isEqualTo(eventMessage.messageId());
            assertThat(nestedMetadata.timestamp()).isNotEqualTo(eventMetadata.timestamp());
        });
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

        private final List<Integer> methodCalled = new CopyOnWriteArrayList<>();

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
    public static class EventHandlerThatThrowsExceptionInSameProcessingGroup {

        private final List<Integer> methodCalled = new CopyOnWriteArrayList<>();

        @EventHandler(order = 1)
        public void handle(EventForTest event) {
            methodCalled.add(1);
            throw new RuntimeException("this is an exception");
        }

        @EventHandler(order = 2)
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    public static class EventHandlerThatThrowsExceptionInDifferentProcessingGroup {

        private final List<Integer> methodCalled = new CopyOnWriteArrayList<>();

        @ProcessingGroup("1")
        @EventHandler(order = 1)
        public void handle(EventForTest event) {
            methodCalled.add(1);
            throw new RuntimeException("this is an exception");
        }

        @ProcessingGroup("2")
        @EventHandler(order = 2)
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    public record CommandForMetadata() implements Command<Metadata> {
    }

    public static class CommandHandlerForMetadata {

        private final AtomicReference<Metadata> handledMetadata = new AtomicReference<>();

        @CommandHandler
        public Metadata handle(CommandForMetadata command, Metadata metadata, Principal principal) {
            handledMetadata.set(metadata);
            return metadata;
        }
    }

    private static class NestedCommandDispatchingListener implements EventChannelListener {

        private final app.dodb.smd.api.command.CommandGateway commandBus;

        private NestedCommandDispatchingListener(app.dodb.smd.api.command.CommandGateway commandBus) {
            this.commandBus = commandBus;
        }

        @Override
        public String processingGroup() {
            return DEFAULT;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            commandBus.send(new CommandForMetadata());
        }
    }

    private record StaticCommandHandlerLocator(CommandHandlerRegistry registry) implements CommandHandlerLocator {

        @Override
        public CommandHandlerRegistry locate() {
            return registry;
        }
    }
}
