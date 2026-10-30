package app.dodb.smd.api.metadata;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static app.dodb.smd.api.metadata.MetadataTestConstants.PRINCIPAL;
import static app.dodb.smd.api.metadata.MetadataTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataFactoryTest {

    private static final EventMessage<ParentEvent> PARENT = EventMessage.from(new ParentEvent(),
        Metadata.at(TIMESTAMP).withPrincipal(PRINCIPAL).withProperty("tenant", "acme").withTransientProperty("smd.processingId", "processing-1"));

    @Test
    void createScope_insideParentScope_inheritsPrincipalAndProperties() {
        var factory = new MetadataFactory(new SimplePrincipalProvider(), new SystemTimeProvider());

        var child = childMetadata(factory);

        assertThat(child.properties().asMap()).containsOnly(Map.entry("tenant", "acme"));
        assertThat(child.principal()).isEqualTo(PRINCIPAL);
        assertThat(child.parentMessageId()).isEqualTo(PARENT.messageId());
    }

    @Test
    void createScope_parentWithTransientProperties_childHasNone() {
        var factory = new MetadataFactory(new SimplePrincipalProvider(), new SystemTimeProvider());

        var child = childMetadata(factory);

        assertThat(child.transientProperties().isEmpty()).isTrue();
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
