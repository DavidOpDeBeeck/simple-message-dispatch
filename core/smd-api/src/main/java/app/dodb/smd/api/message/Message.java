package app.dodb.smd.api.message;

import app.dodb.smd.api.metadata.Metadata;

import java.util.function.UnaryOperator;

public interface Message<P, M extends Message<P, M>> {

    MessageId messageId();

    P payload();

    Metadata metadata();

    M withMetadata(Metadata newMetadata);

    default M withMetadata(UnaryOperator<Metadata> update) {
        return withMetadata(update.apply(metadata()));
    }
}
