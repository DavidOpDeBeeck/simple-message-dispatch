package app.dodb.smd.api.metadata;

import app.dodb.smd.api.metadata.principal.Principal;

import java.time.LocalDateTime;

public record Metadata(Principal principal, LocalDateTime timestamp) {
}
