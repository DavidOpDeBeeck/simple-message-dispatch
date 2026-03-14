package app.dodb.smd.api.message;

import app.dodb.smd.api.metadata.Metadata;

public interface Message<P, M extends Message<P, M>> {

    MessageId messageId();

    P payload();

    Metadata metadata();

    M withMetadata(Metadata newMetadata);

    M andMetadata(Metadata newMetadata);
}
