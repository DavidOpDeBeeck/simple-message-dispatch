package app.dodb.smd.jms;

import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.Message;
import jakarta.jms.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class JmsSubscription {

    private static final Logger LOGGER = LoggerFactory.getLogger(JmsSubscription.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final JmsConfig config;
    private final JmsDestination destination;
    private final Optional<String> subscriptionName;
    private final String subscriber;
    private final MessageHandler handler;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final List<Thread> consumers = new ArrayList<>();

    JmsSubscription(JmsConfig config, JmsDestination destination, Optional<String> subscriptionName, String subscriber, MessageHandler handler) {
        this.config = requireNonNull(config);
        this.destination = requireNonNull(destination);
        this.subscriptionName = requireNonNull(subscriptionName);
        this.subscriber = requireNonNull(subscriber);
        this.handler = requireNonNull(handler);
    }

    synchronized void start() {
        for (int index = 1; index <= config.getConcurrency(); index++) {
            consumers.add(Thread.ofVirtual()
                .name("smd-jms-" + subscriber + "-" + index)
                .start(this::consume));
        }
        LOGGER.info("JMS subscription started: destination={}, subscription={}, subscriber={}, concurrency={}",
            destination, subscriptionName.orElse(null), subscriber, config.getConcurrency());
    }

    synchronized void close() {
        if (isStopped()) {
            return;
        }
        stopped.countDown();
        for (var consumer : consumers) {
            try {
                if (!consumer.join(CLOSE_TIMEOUT)) {
                    consumer.interrupt();
                }
            } catch (InterruptedException e) {
                consumer.interrupt();
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("JMS subscription closed: destination={}, subscription={}, subscriber={}", destination, subscriptionName.orElse(null), subscriber);
    }

    private void consume() {
        while (!isStopped() && !Thread.currentThread().isInterrupted()) {
            try (var context = config.getConnectionFactory().createContext(JMSContext.SESSION_TRANSACTED);
                 var consumer = openConsumer(context)) {
                while (!isStopped() && !Thread.currentThread().isInterrupted()) {
                    var message = consumer.receive(config.getReceiveTimeout().toMillis());
                    if (message != null) {
                        handle(context, message);
                    }
                }
            } catch (JMSRuntimeException e) {
                if (isStopped()) {
                    return;
                }
                LOGGER.warn("JMS consumer failed, reconnecting after {}: destination={}, subscription={}, subscriber={}",
                    config.getReceiveTimeout(), destination, subscriptionName.orElse(null), subscriber, e);
                awaitStop(config.getReceiveTimeout());
            }
        }
    }

    private JMSConsumer openConsumer(JMSContext context) {
        var jmsDestination = destination.createIn(context);
        return subscriptionName
            .map(name -> context.createSharedDurableConsumer((Topic) jmsDestination, name))
            .orElseGet(() -> context.createConsumer(jmsDestination));
    }

    // The handler's database work commits before the broker acknowledgement: a crash in between redelivers, it never loses.
    private void handle(JMSContext context, Message message) {
        try {
            handler.handle(message);
        } catch (Exception e) {
            LOGGER.warn("JMS message handling failed, rolling back for redelivery: destination={}, subscription={}, subscriber={}, messageId={}, "
                    + "eventType={}, deliveryCount={}",
                destination, subscriptionName.orElse(null), subscriber, property(message, JmsWireFormat.MESSAGE_ID_PROPERTY), type(message),
                property(message, "JMSXDeliveryCount"), e);
            context.rollback();
            return;
        }
        context.commit();
    }

    private boolean isStopped() {
        return stopped.getCount() == 0;
    }

    private void awaitStop(Duration timeout) {
        try {
            stopped.await(timeout.toMillis(), MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String property(Message message, String name) {
        try {
            return message.getStringProperty(name);
        } catch (JMSException | RuntimeException e) {
            return null;
        }
    }

    private static String type(Message message) {
        try {
            return message.getJMSType();
        } catch (JMSException e) {
            return null;
        }
    }

    @FunctionalInterface
    interface MessageHandler {

        void handle(Message message) throws Exception;
    }
}
