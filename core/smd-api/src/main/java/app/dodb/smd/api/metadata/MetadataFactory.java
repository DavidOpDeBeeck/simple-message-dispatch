package app.dodb.smd.api.metadata;

import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public class MetadataFactory {

    private static final ScopedValue<Metadata> PARENT_METADATA = ScopedValue.newInstance();
    private static final ScopedValue<MessageId> PARENT_MESSAGE_ID = ScopedValue.newInstance();

    private final PrincipalProvider principalProvider;
    private final TimeProvider timeProvider;

    public MetadataFactory(PrincipalProvider principalProvider, TimeProvider timeProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        this.timeProvider = requireNonNull(timeProvider);
    }

    public MetadataScope createScope() {
        return new MetadataScope(this::determineMetadata);
    }

    public MetadataScope createScope(Metadata metadata) {
        return new MetadataScope(() -> metadata);
    }

    private Metadata determineMetadata() {
        var parentMessageId = PARENT_MESSAGE_ID.isBound() ? PARENT_MESSAGE_ID.get() : null;
        var parentMetadata = PARENT_METADATA.isBound() ? PARENT_METADATA.get() : null;
        return parentMetadata == null
            ? new Metadata(principalProvider.get(), timeProvider.now(), parentMessageId)
            : new Metadata(parentMetadata.principal(), timeProvider.now(), parentMessageId, parentMetadata.properties());
    }

    public record MetadataScope(Supplier<Metadata> metadataSupplier) {

        public MetadataScope {
            requireNonNull(metadataSupplier);
        }

        public <P, M extends Message<P, M>> void run(Function<Metadata, M> messageCreator, Consumer<M> consumer) {
            Metadata metadata = metadataSupplier.get();
            ScopedValue.where(PARENT_METADATA, metadata)
                .run(() -> {
                    var message = messageCreator.apply(metadata);
                    ScopedValue.where(PARENT_MESSAGE_ID, message.messageId()).run(() -> consumer.accept(message));
                });
        }

        public <T> T run(Function<Metadata, T> function) {
            Metadata metadata = metadataSupplier.get();
            return ScopedValue.where(PARENT_METADATA, metadata)
                .call(() -> function.apply(metadata));
        }
    }
}
