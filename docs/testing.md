# Testing Guide

Use `smd-test` when you want lightweight handler or bus tests without Spring.

## Main Utilities

- `SMDTestExtension`
- `CommandGatewayStub`
- `QueryGatewayStub`
- `EventPublisherStub`

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

## When To Use It

- use `smd-test` for fast unit-style tests
- use [Spring Boot Testing Guide](spring-boot-testing.md) when the Spring container is part of what you need to verify

## Related Docs

- [Getting Started](getting-started.md)
- [Spring Boot Testing Guide](spring-boot-testing.md)
