package app.dodb.smd.api.metadata;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static app.dodb.smd.api.metadata.MetadataInheritance.excludingProperties;
import static app.dodb.smd.api.metadata.MetadataInheritance.excludingSmdProperties;
import static app.dodb.smd.api.metadata.MetadataTestConstants.PRINCIPAL;
import static app.dodb.smd.api.metadata.MetadataTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataFactoryTest {

    private static final EventMessage<ParentEvent> PARENT = EventMessage.from(new ParentEvent(),
        new Metadata(PRINCIPAL, TIMESTAMP, null, Map.of("tenant", "acme", "Idempotency-Key", "key-1", "smd.position", "3")));

    @Test
    void createScope_insideParentScope_inheritsAllButSmdProperties() {
        var factory = new MetadataFactory(new SimplePrincipalProvider(), new SystemTimeProvider(), excludingSmdProperties());

        var child = childMetadata(factory);

        assertThat(child.properties().asMap()).containsOnly(Map.entry("tenant", "acme"), Map.entry("Idempotency-Key", "key-1"));
        assertThat(child.principal()).isEqualTo(PRINCIPAL);
        assertThat(child.parentMessageId()).isEqualTo(PARENT.messageId());
    }

    @Test
    void createScope_customInheritance_dropsListedProperties() {
        var factory = new MetadataFactory(new SimplePrincipalProvider(), new SystemTimeProvider(),
            excludingSmdProperties().and(excludingProperties("Idempotency-Key")));

        var child = childMetadata(factory);

        assertThat(child.properties().asMap()).containsOnly(Map.entry("tenant", "acme"));
    }

    private static Metadata childMetadata(MetadataFactory factory) {
        var child = new AtomicReference<Metadata>();
        MetadataFactory.runInScope(PARENT, () -> factory.createScope()
            .run(metadata -> EventMessage.from(new ChildEvent(), metadata), (EventMessage<ChildEvent> message) -> child.set(message.metadata())));
        return child.get();
    }

    record ParentEvent() implements Event {
    }

    record ChildEvent() implements Event {
    }
}
