# SMD: Metadata

## 9. Metadata

### 9.1 Principal

SMD asks the `PrincipalProvider` for the principal only when a message starts a new chain, such as a command sent from a request. Without your own, each new chain gets
a random `SimplePrincipal`. Provide one in `infrastructure` that maps the authenticated user:

```java
@Component
public class SignedInPrincipalProvider implements PrincipalProvider {

    @Override
    public Principal get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return AppUser.SYSTEM;
        }
        return new AppUser(authentication.getName());
    }
}
```

`AppUser` is your own record implementing `Principal`; put it in `drivingport` so handlers can read it. Messages a handler sends, including from durable groups,
inherit the principal of the message being handled.

The principal is stored with every event under its class name. Treat that name like an event type name: moving or renaming the class makes stored events
unreadable.

### 9.2 Properties

Add a property when sending, usually in a driving adapter:

```java
commandGateway.send(command, metadata -> metadata.withProperty("customerId", customerId));
```

Read it in any later handler of the chain with `@MetadataValue("customerId") String customerId` or `metadata.property("customerId")`.

- Properties are strings. They are stored with events and sent to brokers. Never put secrets or personal data in them.
- Every message a handler sends inherits all properties of the message it handles. Transient properties (`withTransientProperty`) are never inherited or stored.
- SMD's own keys start with `smd.`; do not use that prefix. Durable deliveries add `smd.processingId` and `smd.errorCount` (`ProcessingMetadata`), and the event
  store adds `smd.globalPosition` and `smd.subjectVersion` (`EventStoreMetadata`), as transient properties.
- To import a message with fixed metadata, pass a complete `Metadata.at(timestamp).withPrincipal(...).withProperty(...)`: it inherits nothing.
