# Spring Boot Testing Guide

Use `smd-spring-boot-starter-test` when you want Spring Boot tests with SMD beans replaced by stubs.

## Main Entry Points

- `@EnableSMDStubs`
- `SMDTestExtension`

`@EnableSMDStubs` registers stub beans and a test-scoped `SMDTestExtension`. Stubs are reset automatically between tests through the test-scope lifecycle extension.

## Example

```java
@SpringBootTest
@EnableSMDStubs
class TransferMoneyTest {

    @Autowired
    private SMDTestExtension smd;

    @Test
    void publishesEventWhenTransferSucceeds() {
        smd.stubCommand(new SubtractMoneyCommand("A-1", 100), true);
        smd.stubCommand(new AddMoneyCommand("A-2", 100), true);

        boolean success = smd.send(new TransferMoneyCommand("A-1", "A-2", 100));

        assertThat(success).isTrue();
        assertThat(smd.getEvents()).containsExactly(
            new MoneyTransferredEvent("A-1", "A-2", 100)
        );
    }
}
```

## When To Use It

- use `smd-spring-boot-starter-test` when the Spring container is part of what you need to verify
- use [Testing Guide](testing.md) for lightweight tests without Spring Boot

## Related Docs

- [Getting Started](getting-started.md)
- [Spring Boot Guide](spring-boot.md)
- [Testing Guide](testing.md)
