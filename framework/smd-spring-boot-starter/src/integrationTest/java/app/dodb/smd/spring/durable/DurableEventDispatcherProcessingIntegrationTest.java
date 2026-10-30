package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.spring.durable.DurableDeliveryTestFixture.FailedDelivery;
import app.dodb.smd.spring.durable.DurableDeliveryTestFixture.PendingEvent;
import app.dodb.smd.spring.durable.processing.NoteEvent;
import app.dodb.smd.spring.durable.processing.OrderEvent;
import app.dodb.smd.spring.durable.processing.OrderEventHandler;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static app.dodb.smd.api.event.delivery.ProcessingMetadata.ERROR_COUNT;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.PROCESSING_ID;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.SUBJECT_ID;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.NO_RETRY_DURING_TEST;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.awaitRelease;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.durableDeliveryTestFixture;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.note;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.order;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.subscribe;
import static app.dodb.smd.spring.durable.processing.DurableDeliveryTestConfiguration.ORDERS;
import static app.dodb.smd.spring.durable.processing.DurableDeliveryTestConfiguration.eventSerializerFor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

class DurableEventDispatcherProcessingIntegrationTest {

    private static final String DISPATCHER = "order-events";

    @Test
    void publish_toDurableProcessingGroup_storesAndDeliversOnlyTheEventsItHandles() {
        // Given
        try (var fixture = durableDeliveryTestFixture()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(OrderEventHandler.class);
            var placed = new OrderEvent("order-1", 1);
            var noted = new NoteEvent("hello");

            // When
            fixture.bean(TransactionProvider.class).doInTransaction(() -> {
                eventBus.publish(placed);
                eventBus.publish(noted);
            });

            // Then
            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).containsExactly(placed);
                assertThat(fixture.pendingEvents(ORDERS)).isEmpty();
            });
        }
    }

    @Test
    void poll_withSeveralSubscribers_deliversEachEventToEachInSubjectOrder() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var billing = subscribe(dispatcher, "billing");
            var audit = subscribe(dispatcher, "audit");
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            fixture.publish(dispatcher.inlet(), placed, paid);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(billing).containsExactly(placed.payload(), paid.payload());
            assertThat(audit).containsExactly(placed.payload(), paid.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_severalEventsOfOneSubject_deliversThemInOneBatchInOrder() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var handled = subscribe(dispatcher, "billing");
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            var shipped = order("order-1", 3);
            fixture.publish(dispatcher.inlet(), placed, paid, shipped);

            // When
            scheduler.poll();

            // Then
            assertThat(handled).containsExactly(placed.payload(), paid.payload(), shipped.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_deliveredEvent_carriesProcessingMetadata() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var delivered = new CopyOnWriteArrayList<EventMessage<?>>();
            dispatcher.subscribe(new EventSubscriberStub("billing", delivered::add));
            var placed = order("order-1", 1);
            fixture.publish(dispatcher.inlet(), placed);

            // When
            scheduler.poll();

            // Then
            assertThat(delivered).singleElement().satisfies(message -> {
                assertThat(message.messageId()).isEqualTo(placed.messageId());
                assertThat(message.payload()).isEqualTo(placed.payload());
                assertThat(message.metadata().properties().asMap()).containsEntry("tenant", "acme");
                assertThat(message.metadata().transientProperties().asMap())
                    .containsEntry(SUBJECT_ID, "order-1")
                    .containsEntry(ERROR_COUNT, "0")
                    .containsKey(PROCESSING_ID);
            });
        }
    }

    @Test
    void poll_handlerCreatesMessage_usesDeliveredEventAsParent() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var created = new CopyOnWriteArrayList<Metadata>();
            dispatcher.subscribe(new EventSubscriberStub("billing", _ -> created.add(metadataOfNewMessage())));
            var placed = order("order-1", 1);
            fixture.publish(dispatcher.inlet(), placed);

            // When
            scheduler.poll();

            // Then
            assertThat(created).singleElement().satisfies(metadata -> {
                assertThat(metadata.parentMessageId()).isEqualTo(placed.messageId());
                assertThat(metadata.property("tenant")).contains("acme");
            });
        }
    }

    @Test
    void poll_whenSubjectFails_holdsItsLaterEventsAndDeliversSubjectsOfOtherLanes() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcherWithoutRetries(DISPATCHER, scheduler);
            var failing = order("order-1", 1);
            var blocked = order("order-1", 2);
            var other = order("order-2", 1);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(failing, 1, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), failing, blocked, other);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(other.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(failing.messageId(), blocked.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER)).singleElement().satisfies(failed -> {
                assertThat(failed.subject()).isEqualTo("order-1");
                assertThat(failed.attempts()).isOne();
                assertThat(failed.lastError()).contains("Billing unavailable");
            });
        }
    }

    @Test
    void poll_afterFailures_retriesSubjectInOrderWithErrorCount() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            var handled = new CopyOnWriteArrayList<HandledEvent>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(placed, 2,
                message -> handled.add(new HandledEvent(message.payload(), message.metadata().property(ERROR_COUNT).orElseThrow())))));
            fixture.publish(dispatcher.inlet(), placed, paid);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(new HandledEvent(placed.payload(), "2"), new HandledEvent(paid.payload(), "0"));
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_eventPublishedWhileSubjectIsBlocked_followsTheRetriedEvent() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(placed, 1, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), placed);
            scheduler.poll();

            // When
            fixture.publish(dispatcher.inlet(), paid);
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(placed.payload(), paid.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_afterFailedEventIsDeleted_continuesItsSubject() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var poison = order("order-1", 1);
            var next = order("order-1", 2);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(poison, Integer.MAX_VALUE, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), poison);
            scheduler.poll();
            fixture.execute("DELETE FROM smd_dispatcher_queue WHERE message_id = '" + poison.messageId().value() + "'");

            // When
            fixture.publish(dispatcher.inlet(), next);
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(next.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_afterFailedEventIsDeletedWhileLaterEventsWait_deliversTheNextOfItsLane() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var poison = order("order-1", 1);
            var next = order("order-1", 2);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(poison, Integer.MAX_VALUE, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), poison, next);
            scheduler.poll();
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(poison.messageId(), next.messageId());

            // When
            fixture.execute("DELETE FROM smd_dispatcher_queue WHERE message_id = '" + poison.messageId().value() + "'");
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(next.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_whenHandlerThrowsCheckedException_rollsBackItsChangesAndRetries() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var failuresLeft = new AtomicInteger(1);
            dispatcher.subscribe(new EventSubscriberStub("billing", message -> {
                fixture.recordHandlerEffect(message.payload());
                if (failuresLeft.getAndDecrement() > 0) {
                    sneakyThrow(new IOException("Billing unavailable"));
                }
            }));
            var placed = order("order-1", 1);
            fixture.publish(dispatcher.inlet(), placed);

            // When
            scheduler.poll();

            // Then
            assertThat(fixture.handlerEffects()).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(placed.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER)).singleElement().satisfies(failed -> {
                assertThat(failed.attempts()).isOne();
                assertThat(failed.lastError()).contains(IOException.class.getName());
            });

            // When
            scheduler.poll();

            // Then
            assertThat(fixture.handlerEffects()).containsExactly(placed.payload().toString());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_whenHandlerThrowsError_recordsFailureAndDeliversOtherSubjects() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcherWithoutRetries(DISPATCHER, scheduler);
            var failing = order("order-1", 1);
            var other = order("order-2", 1);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", message -> {
                if (message.messageId().equals(failing.messageId())) {
                    throw new AssertionError("Billing bug");
                }
                handled.add(message.payload());
            }));
            fixture.publish(dispatcher.inlet(), failing, other);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(other.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(failing.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER)).singleElement().satisfies(failed -> {
                assertThat(failed.subject()).isEqualTo("order-1");
                assertThat(failed.attempts()).isOne();
                assertThat(failed.lastError()).contains("Billing bug");
            });
        }
    }

    @Test
    void poll_whenEventFails_holdsLaterEventsOfOtherSubjectsInItsLane() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcherWithOneLaneWithoutRetries(DISPATCHER, scheduler);
            var failing = order("order-1", 1);
            var sameLane = order("order-2", 1);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(failing, 1, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), failing, sameLane);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(failing.messageId(), sameLane.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER))
                .extracting(FailedDelivery::subject, FailedDelivery::attempts)
                .containsExactly(tuple("order-1", 1));
        }
    }

    @Test
    void poll_whenSubjectlessEventFails_holdsLaterEventsOfItsLane() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcherWithOneLaneWithoutRetries(DISPATCHER, scheduler);
            var failing = note("first");
            var later = note("second");
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(failing, 1, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), failing, later);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(failing.messageId(), later.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER))
                .extracting(FailedDelivery::subject, FailedDelivery::attempts)
                .containsExactly(tuple(null, 1));
        }
    }

    @Test
    void poll_whenOneSubscriberFails_deliversToOtherSubscribers() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var audited = subscribe(dispatcher, "audit");
            dispatcher.subscribe(new EventSubscriberStub("billing", _ -> {
                throw new IllegalStateException("Billing unavailable");
            }));
            var placed = order("order-1", 1);
            fixture.publish(dispatcher.inlet(), placed);

            // When
            scheduler.poll();

            // Then
            assertThat(audited).containsExactly(placed.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::subscriber).containsExactly("billing");
            assertThat(fixture.failedDeliveries(DISPATCHER))
                .extracting(FailedDelivery::subscriber, FailedDelivery::attempts)
                .containsExactly(tuple("billing", 1));
        }
    }

    @Test
    void poll_whenStoredTypeIsUnknownToReceiver_recordsFailureAgainstThatEvent() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var publisher = fixture.dispatcher(DISPATCHER, scheduler);
            var receiver = fixture.dispatcher(DISPATCHER, fixture.dispatcherConfig(scheduler)
                .eventSerializer(eventSerializerFor(Map.of("note.v1", NoteEvent.class)))
                .processingConfig(fixture.processingConfig().batchSize(1).retryBackoffStrategy(NO_RETRY_DURING_TEST).build())
                .build());
            var handled = subscribe(receiver, "billing");
            var known = note("known");
            var unknown = order("order-1", 1);
            fixture.publish(publisher.inlet(), known, unknown);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(known.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(unknown.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER)).singleElement().satisfies(failed -> {
                assertThat(failed.attempts()).isOne();
                assertThat(failed.lastError()).contains("event type 'order.v1' can not be resolved");
            });
        }
    }

    @Test
    void poll_withMoreEventsThanBatchSize_deliversOneBatchPerPoll() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcherWithBatchSize(DISPATCHER, scheduler, 2);
            var handled = subscribe(dispatcher, "billing");
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            var shipped = order("order-1", 3);
            fixture.publish(dispatcher.inlet(), placed, paid, shipped);

            // When
            scheduler.poll();

            // Then
            assertThat(handled).containsExactly(placed.payload(), paid.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(shipped.messageId());

            // When
            scheduler.poll();

            // Then
            assertThat(handled).containsExactly(placed.payload(), paid.payload(), shipped.payload());
        }
    }

    @Test
    void poll_whenEventFailsMidBatch_rollsBackTheBatchAndRecordsTheFailureAgainstThatEvent() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var placed = order("order-1", 1);
            var failing = order("order-1", 2);
            var shipped = order("order-1", 3);
            var failuresLeft = new AtomicInteger(1);
            var calls = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", message -> {
                calls.add(message.payload());
                fixture.recordHandlerEffect(message.payload());
                if (message.messageId().equals(failing.messageId()) && failuresLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("Billing unavailable");
                }
            }));
            fixture.publish(dispatcher.inlet(), placed, failing, shipped);

            // When
            scheduler.poll();

            // Then
            assertThat(calls).containsExactly(placed.payload(), failing.payload());
            assertThat(fixture.handlerEffects()).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER))
                .extracting(PendingEvent::messageId)
                .containsExactly(placed.messageId(), failing.messageId(), shipped.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER)).singleElement().satisfies(failed -> assertThat(failed.attempts()).isOne());

            // When
            scheduler.poll();

            // Then
            assertThat(calls).containsExactly(placed.payload(), failing.payload(), placed.payload(), failing.payload(), shipped.payload());
            assertThat(fixture.handlerEffects())
                .containsExactly(placed.payload().toString(), failing.payload().toString(), shipped.payload().toString());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_afterEventFailedMidBatch_deliversTheEventsAheadOfItAndHoldsTheRest() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcherWithoutRetries(DISPATCHER, scheduler);
            var placed = order("order-1", 1);
            var failing = order("order-1", 2);
            var shipped = order("order-1", 3);
            var handled = new CopyOnWriteArrayList<>();
            dispatcher.subscribe(new EventSubscriberStub("billing", failingOn(failing, 1, message -> handled.add(message.payload()))));
            fixture.publish(dispatcher.inlet(), placed, failing, shipped);
            scheduler.poll();

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(placed.payload(), placed.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(failing.messageId(), shipped.messageId());
        }
    }

    @Test
    void poll_withCompetingInstances_deliversEachEventOnceInSubjectOrder() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var firstScheduler = new ManualPollingScheduler();
             var secondScheduler = new ManualPollingScheduler()) {
            var firstInstance = fixture.dispatcherWithBatchSize(DISPATCHER, firstScheduler, 1);
            var secondInstance = fixture.dispatcherWithBatchSize(DISPATCHER, secondScheduler, 1);
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            var other = order("order-2", 1);
            var shipped = order("order-1", 3);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var handled = new CopyOnWriteArrayList<>();
            Consumer<EventMessage<? extends Event>> billing = message -> {
                if (message.messageId().equals(placed.messageId())) {
                    entered.countDown();
                    awaitRelease(release);
                }
                handled.add(message.payload());
            };
            firstInstance.subscribe(new EventSubscriberStub("billing", billing));
            secondInstance.subscribe(new EventSubscriberStub("billing", billing));
            fixture.publish(firstInstance.inlet(), placed, paid);

            var firstPoll = firstScheduler.pollAsync();
            try {
                assertThat(entered.await(5, SECONDS)).as("first instance holds the first claim").isTrue();
                fixture.publish(firstInstance.inlet(), other, shipped);

                // When
                secondScheduler.poll();

                // Then
                assertThat(handled).containsExactly(other.payload());
                assertThat(fixture.pendingEvents(DISPATCHER))
                    .extracting(PendingEvent::messageId)
                    .containsExactly(placed.messageId(), paid.messageId(), shipped.messageId());
            } finally {
                release.countDown();
            }
            firstPoll.get(5, SECONDS);

            // When
            fixture.pollUntilIdle(secondScheduler);

            // Then
            assertThat(handled).containsExactly(other.payload(), placed.payload(), paid.payload(), shipped.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_whileAnotherInstanceHoldsAnEventOfASubject_deliversNothingOfThatSubject() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var firstScheduler = new ManualPollingScheduler();
             var secondScheduler = new ManualPollingScheduler()) {
            var firstInstance = fixture.dispatcherWithBatchSize(DISPATCHER, firstScheduler, 1);
            var secondInstance = fixture.dispatcherWithBatchSize(DISPATCHER, secondScheduler, 1);
            var held = order("order-1", 1);
            var waiting = order("order-1", 2);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var handled = new CopyOnWriteArrayList<>();
            Consumer<EventMessage<? extends Event>> billing = message -> {
                if (message.messageId().equals(held.messageId())) {
                    entered.countDown();
                    awaitRelease(release);
                }
                handled.add(message.payload());
            };
            firstInstance.subscribe(new EventSubscriberStub("billing", billing));
            secondInstance.subscribe(new EventSubscriberStub("billing", billing));
            fixture.publish(firstInstance.inlet(), held, waiting);

            var firstPoll = firstScheduler.pollAsync();
            try {
                assertThat(entered.await(5, SECONDS)).as("first instance holds the subject's lane").isTrue();

                // When
                secondScheduler.poll();

                // Then
                assertThat(handled).isEmpty();
            } finally {
                release.countDown();
            }
            firstPoll.get(5, SECONDS);
            fixture.pollUntilIdle(secondScheduler);

            assertThat(handled).containsExactly(held.payload(), waiting.payload());
        }
    }

    @Test
    void poll_whileAnotherInstanceHoldsALane_deliversNothingOfThatLane() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var firstScheduler = new ManualPollingScheduler();
             var secondScheduler = new ManualPollingScheduler()) {
            var firstInstance = fixture.dispatcherWithLaneCount(DISPATCHER, firstScheduler, 1);
            var secondInstance = fixture.dispatcherWithLaneCount(DISPATCHER, secondScheduler, 1);
            var held = order("order-1", 1);
            var sameLane = order("order-2", 1);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var handled = new CopyOnWriteArrayList<>();
            Consumer<EventMessage<? extends Event>> billing = message -> {
                if (message.messageId().equals(held.messageId())) {
                    entered.countDown();
                    awaitRelease(release);
                }
                handled.add(message.payload());
            };
            firstInstance.subscribe(new EventSubscriberStub("billing", billing));
            secondInstance.subscribe(new EventSubscriberStub("billing", billing));
            fixture.publish(firstInstance.inlet(), held);

            var firstPoll = firstScheduler.pollAsync();
            try {
                assertThat(entered.await(5, SECONDS)).as("first instance holds the only lane").isTrue();
                fixture.publish(firstInstance.inlet(), sameLane);

                // When
                secondScheduler.poll();

                // Then
                assertThat(handled).isEmpty();
            } finally {
                release.countDown();
            }
            firstPoll.get(5, SECONDS);
            fixture.pollUntilIdle(secondScheduler);

            assertThat(handled).containsExactly(held.payload(), sameLane.payload());
        }
    }

    @Test
    void poll_sameSubscriberOnTwoDispatchers_deliversOnlyEachDispatchersOwnEvents() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var tickets = fixture.dispatcher(DISPATCHER, scheduler);
            var invoices = fixture.dispatcher("invoice-events", scheduler);
            var ticketBilling = subscribe(tickets, "billing");
            var invoiceBilling = subscribe(invoices, "billing");
            var ticket = order("order-1", 1);
            fixture.publish(tickets.inlet(), ticket);

            // When
            scheduler.poll();

            // Then
            assertThat(ticketBilling).containsExactly(ticket.payload());
            assertThat(invoiceBilling).isEmpty();
            assertThat(fixture.pendingEvents("invoice-events")).isEmpty();
        }
    }

    private static Consumer<EventMessage<? extends Event>> failingOn(EventMessage<?> failing, int times, Consumer<EventMessage<? extends Event>> handler) {
        var failuresLeft = new AtomicInteger(times);
        return message -> {
            if (message.messageId().equals(failing.messageId()) && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("Billing unavailable");
            }
            handler.accept(message);
        };
    }

    private static Metadata metadataOfNewMessage() {
        return new MetadataFactory(() -> null, Instant::now).createScope().metadataSupplier().get();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private record HandledEvent(Object payload, String errorCount) {
    }
}
