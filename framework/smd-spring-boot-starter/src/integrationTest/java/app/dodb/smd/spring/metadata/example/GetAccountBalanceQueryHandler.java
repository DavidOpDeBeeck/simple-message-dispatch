package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.query.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GetAccountBalanceQueryHandler {

    public static final List<Metadata> handledMetadata = new ArrayList<>();
    public static final List<String> metadataValues = new ArrayList<>();

    @QueryHandler
    public int handle(GetAccountBalanceQuery query, Metadata metadata, @MetadataValue("key") String value) {
        handledMetadata.add(metadata);
        metadataValues.add(value);
        return 1;
    }
}
