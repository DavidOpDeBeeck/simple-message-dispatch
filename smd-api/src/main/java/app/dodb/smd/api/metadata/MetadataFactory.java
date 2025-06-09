package app.dodb.smd.api.metadata;

import static java.util.Objects.requireNonNull;

public class MetadataFactory {

    private final PrincipalProvider principalProvider;
    private final DatetimeProvider datetimeProvider;

    public MetadataFactory(PrincipalProvider principalProvider,
                           DatetimeProvider datetimeProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        this.datetimeProvider = requireNonNull(datetimeProvider);
    }

    public Metadata create() {
        return new Metadata(
            principalProvider.get(),
            datetimeProvider.now()
        );
    }
}
