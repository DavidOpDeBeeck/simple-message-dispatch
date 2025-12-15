package app.dodb.smd.api.metadata;

import app.dodb.smd.api.metadata.datetime.DatetimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public class MetadataFactory {

    private static final ScopedValue<Metadata> SCOPED_METADATA = ScopedValue.newInstance();

    private final PrincipalProvider principalProvider;
    private final DatetimeProvider datetimeProvider;

    public MetadataFactory(PrincipalProvider principalProvider, DatetimeProvider datetimeProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        this.datetimeProvider = requireNonNull(datetimeProvider);
    }

    public MetadataScope createScope() {
        return new MetadataScope(this::determineMetadata);
    }

    public MetadataScope createScope(Metadata metadata) {
        return new MetadataScope(() -> metadata);
    }

    private Metadata determineMetadata() {
        return SCOPED_METADATA.isBound()
            ? new Metadata(SCOPED_METADATA.get().principal(), datetimeProvider.now())
            : new Metadata(principalProvider.get(), datetimeProvider.now());
    }

    public record MetadataScope(Supplier<Metadata> metadataSupplier) {

        public MetadataScope {
            requireNonNull(metadataSupplier);
        }

        public void run(Consumer<Metadata> consumer) {
            Metadata metadata = metadataSupplier.get();
            ScopedValue.where(SCOPED_METADATA, metadata)
                .run(() -> consumer.accept(metadata));
        }

        public <T> T run(Function<Metadata, T> function) {
            Metadata metadata = metadataSupplier.get();
            return ScopedValue.where(SCOPED_METADATA, metadata)
                .call(() -> function.apply(metadata));
        }
    }
}
