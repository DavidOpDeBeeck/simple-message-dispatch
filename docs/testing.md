# Testing Guide

Use `smd-test` when you want lightweight handler or bus tests without Spring.

## Main Utilities

- `SMDTestExtension`
- `CommandGatewayStub`
- `QueryGatewayStub`
- `EventPublisherStub`
- `EventChannelListenerStub`
- `NoOpTransactionProvider`

```java
class CreateAccountHandlerTest {

    private final EventPublisherStub eventPublisher = new EventPublisherStub();
    private final CreateAccountHandler handler = new CreateAccountHandler(eventPublisher);

    @Test
    void createsAccountAndPublishesEvent() {
        handler.handle(new CreateAccount("Alice"));

        assertThat(eventPublisher.getEvents()).hasSize(1);
    }
}
```

`SMDTestExtension` also lets you stub principal, time, commands, and queries while building temporary buses internally.

Use `EventChannelListenerStub` to capture events delivered directly by a channel. `NoOpTransactionProvider` runs transaction callbacks and deferred work immediately when a test does not need real
transaction boundaries.

## When To Use It

- use `smd-test` for fast unit-style tests
- use [Spring Boot Testing Guide](spring-boot-testing.md) when the Spring container is part of what you need to verify

## Related Docs

- [Getting Started](getting-started.md)
- [Spring Boot Testing Guide](spring-boot-testing.md)
