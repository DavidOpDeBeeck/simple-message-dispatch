# SMD: Testing

## Contents

- [12.1 Aggregates](#121-aggregates)
- [12.2 Use case handlers](#122-use-case-handlers)
- [12.3 Driven adapters](#123-driven-adapters)
- [12.4 Acceptance](#124-acceptance)

## 12. Testing

| What changed                                     | Test                                                                     | Source set        |
|--------------------------------------------------|--------------------------------------------------------------------------|-------------------|
| An aggregate's decisions                         | `AggregateTestFixture`: given events, when command, expect events        | `test`            |
| A query, plain command, or policy handler        | `@EnableSMDStubs` with the layer's configuration and in-memory port fakes | `test`            |
| A projection, adapter, or delivery configuration | Integration test of `DrivenAdapterConfiguration` against PostgreSQL with Testcontainers | `integrationTest` |
| An endpoint and the full flow                    | Acceptance test of the whole application over HTTP                       | `acceptanceTest`  |

Name tests `operation_condition_expectedOutcome`. Use AssertJ, real objects, and small handwritten fakes. Wait for asynchronous results with Awaitility, never with sleeps.

### 12.1 Aggregates

```java
class TicketTest {

    private final AggregateTestFixture<Ticket> fixture = AggregateTestFixture.of(Ticket.class);

    @Test
    void assignTicketCommand_openTicket_appendsAssignedEvent() {
        fixture.given(new TicketOpenedEvent(TICKET_ID, "Cannot sign in", "Reset mail missing"))
            .when(new AssignTicketCommand(TICKET_ID, "alice"))
            .expectEvents(new TicketAssignedEvent(TICKET_ID, "alice"))
            .expectResult(TICKET_ID);
    }

    @Test
    void assignTicketCommand_unknownTicket_throwsAggregateNotFound() {
        fixture.givenNoPriorActivity()
            .when(new AssignTicketCommand(TICKET_ID, "alice"))
            .expectException(AggregateNotFoundException.class);
    }
}
```

Other steps: `givenCommands(...)`, `expectNoEvents()`, `expectState(...)`, and `expectEventsSatisfying(...)` or `expectResultSatisfying(...)` for generated values. Configure
collaborators, time, the principal, or the application's `EventSerializer` with `AggregateTestFixture.builder(Ticket.class)`.

### 12.2 Use case handlers

```java
@EnableSMDStubs
@SpringBootTest(classes = UseCaseConfiguration.class, webEnvironment = NONE)
class TicketQueryHandlerTest {

    @Autowired
    private SMDTestExtension smd;

    @Autowired
    private InMemoryTicketViewRepository views;

    @Test
    void send_getTicketQuery_returnsTicketView() {
        views.stub(TICKET_ID, TICKET);

        var actual = smd.send(new GetTicketQuery(TICKET_ID));

        assertThat(actual).contains(TICKET);
    }
}
```

- Put the in-memory fakes of driven ports as `@Component`s in a test subpackage of `usecase`, such as `usecase.stubs`, so the layer's component scan finds them.
- `smd.send(...)` dispatches through the real handler. Gateways and the publisher injected into handlers are stubs: stub nested calls with `stubCommand(...)` and
  `stubQuery(...)`, assert published events with `getEvents()`, and fix metadata with `stubPrincipal(...)` and `stubTimestamp(...)`.
- Events that handlers publish are captured by `getEvents()`, not delivered. Run event handlers with `smd.send(event)`.
- A command or query that is not stubbed returns `null`. Stubs match by `equals`, so stub an equal record.
- Stubs work only on the test thread. When the layer's configurer uses any asynchronous or durable delivery, replace it in the test with a bean of the same name that
  makes every group `sync()`; the test then also needs no database.
- Load only one layer configuration per stub test: the stub support accepts a single `ProcessingGroupsConfigurer` and fails with `NoUniqueBeanDefinitionException`
  when there are two.

### 12.3 Driven adapters

Create a meta-annotation that loads only the driven adapter layer, and in each test replace the layer's configurer with one that enables only the group under test:

```java
@Target(TYPE)
@Retention(RUNTIME)
@EnableAutoConfiguration
@SpringBootTest(classes = DrivenAdapterConfiguration.class)
public @interface IntegrationTest {
}
```

```java
@IntegrationTest
@Import(CustomerWebhooksIntegrationTest.TestConfig.class)
class CustomerWebhooksIntegrationTest {

    static class TestConfig {

        @Bean
        ProcessingGroupsConfigurer drivenAdapterProcessingGroups(DurableDelivery durableDelivery) {
            return groups -> groups
                .processingGroup(CUSTOMER_WEBHOOKS).medium(durableDelivery.dispatcher("ticket-service." + CUSTOMER_WEBHOOKS))
                .anyProcessingGroup().disabled();
        }

        @Bean
        @Primary
        FakeCustomerWebhookSender fakeCustomerWebhookSender() {
            return new FakeCustomerWebhookSender();
        }
    }

    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private FakeCustomerWebhookSender webhookSender;

    @Test
    void publish_ticketOpenedEvent_sendsWebhook() {
        var opened = EventMessage.from(new TicketOpenedEvent(TICKET_ID, "Cannot sign in", "Reset mail missing"),
            Metadata.at(TIMESTAMP).withProperty("customerId", CUSTOMER_ID));

        eventPublisher.publish(opened);

        await().untilAsserted(() -> assertThat(webhookSender.attemptsFor(TICKET_ID))
            .containsExactly(new CustomerWebhook(opened.messageId(), CUSTOMER_ID, opened.payload())));
    }
}
```

- `anyProcessingGroup().disabled()` is fine here: the test context holds a single layer, and it switches off every other group.
- Publish `EventMessage.from(event, Metadata.at(...))` to control the message ID, timestamp, and properties you assert on.
- Use a Testcontainers JDBC URL and short polling delays in the integration test `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:tc:postgresql:18:///ticket_service

smd:
  event-store:
    enabled: true
    scheduling:
      initial-delay: 0s
      polling-delay: 1s
  durable-delivery:
    enabled: true
```

- Test failure behavior with a fake that fails a given number of times, and assert the retries and the order.

### 12.4 Acceptance

Start the whole application with `@SpringBootTest(classes = TicketApplication.class, webEnvironment = RANDOM_PORT)`, call it over HTTP, assert `sync()` read models right
away, and wait with Awaitility for durable ones.
