package app.dodb.smd.eventstore.aggregate.locator;

import app.dodb.smd.api.command.CommandHandlerBehaviour;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.aggregate.AssignTestTicketCommand;
import app.dodb.smd.eventstore.aggregate.EventSourcedAggregates;
import app.dodb.smd.eventstore.aggregate.OpenTestTicketCommand;
import app.dodb.smd.eventstore.aggregate.PackageBasedAggregateLocator;
import app.dodb.smd.eventstore.aggregate.locator.duplicate.FirstDuplicateTicket;
import app.dodb.smd.eventstore.aggregate.locator.duplicate.SecondDuplicateTicket;
import app.dodb.smd.eventstore.aggregate.locator.plain.PlainHandlerTicket;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import app.dodb.smd.test.NoOpTransactionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageBasedAggregateLocatorTest {

    private final EventStore eventStore = new EventStore(EventStoreConfig.withDefaults()
        .transactionProvider(new NoOpTransactionProvider())
        .eventStorage(new InMemoryEventStorage())
        .eventSerializer(new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build(), new ClassNameEventTypeResolver(), List.of()))
        .tokenStore(new InMemoryTokenStore())
        .eventSequenceStore(new InMemoryEventSequenceStore())
        .build());
    private final EventSourcedAggregates aggregates = new EventSourcedAggregates(
        eventStore,
        EventBusSpec.withDefaults().sinks(eventStore.inlet()).create(),
        new MetadataFactory(new SimplePrincipalProvider(), new SystemTimeProvider())
    );

    @AfterEach
    void closeEventStore() {
        eventStore.close();
    }

    @Test
    void locate_aggregateInPackage_registersItsCreatingAndUpdatingHandlers() {
        var locator = new PackageBasedAggregateLocator(List.of("app.dodb.smd.eventstore.aggregate.locator.single"), aggregates, new ConstructorBasedObjectCreator());

        var registry = locator.locate();

        assertThat(registry.commandHandlers())
            .<Class<?>>extracting(CommandHandlerBehaviour::commandType)
            .containsExactlyInAnyOrder(OpenTestTicketCommand.class, AssignTestTicketCommand.class);
    }

    @Test
    void locate_aggregateWithOnlyCommandHandlerAnnotation_fails() {
        var locator = new PackageBasedAggregateLocator(List.of("app.dodb.smd.eventstore.aggregate.locator.plain"), aggregates, new ConstructorBasedObjectCreator());

        assertThatThrownBy(locator::locate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@AggregateCommandHandler instead of @CommandHandler")
            .hasMessageContaining(PlainHandlerTicket.class.getName());
    }

    @Test
    void locate_aggregatesSharingTypeName_fails() {
        var locator = new PackageBasedAggregateLocator(List.of("app.dodb.smd.eventstore.aggregate.locator.duplicate"), aggregates, new ConstructorBasedObjectCreator());

        assertThatThrownBy(locator::locate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate aggregate type name: duplicate-ticket")
            .hasMessageContaining(FirstDuplicateTicket.class.getName())
            .hasMessageContaining(SecondDuplicateTicket.class.getName());
    }
}
