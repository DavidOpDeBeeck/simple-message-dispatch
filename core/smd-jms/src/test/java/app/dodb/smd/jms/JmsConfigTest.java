package app.dodb.smd.jms;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.test.NoOpTransactionProvider;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmsConfigTest {

    @Test
    void build_withoutConnectionFactory_fails() {
        assertThatThrownBy(() -> JmsConfig.withDefaults()
            .eventSerializer(serializer())
            .transactionProvider(new NoOpTransactionProvider())
            .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void build_zeroConcurrency_fails() {
        assertThatThrownBy(() -> complete().concurrency(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("JMS concurrency must be greater than zero; got 0");
    }

    @Test
    void build_zeroReceiveTimeout_fails() {
        assertThatThrownBy(() -> complete().receiveTimeout(Duration.ZERO).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("JMS receive timeout must be greater than zero; got PT0S");
    }

    @Test
    void build_blankSubscriberName_fails() {
        assertThatThrownBy(() -> complete().subscriberName(" ").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("JMS subscriber name must not be blank; got ' '");
    }

    @Test
    void destination_blankName_fails() {
        assertThatThrownBy(() -> JmsDestination.topic(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("JMS destination requires a non-blank name; got ''");
    }

    @Test
    void subscribe_topicWithoutSubscriberName_failsBeforeConnecting() {
        var source = new JmsEventSource(complete().build(), JmsDestination.topic("billing.events"));

        assertThatThrownBy(() -> source.subscribe(subscriber("billing-projection")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("JMS topic 'billing.events' needs JmsConfig.subscriberName to name the durable subscription for processing group 'billing-projection'");
    }

    private static JmsConfig.Builder complete() {
        return JmsConfig.withDefaults()
            .connectionFactory(new UnusedConnectionFactory())
            .eventSerializer(serializer())
            .transactionProvider(new NoOpTransactionProvider());
    }

    private static JacksonEventSerializer serializer() {
        return new JacksonEventSerializer(JsonMapper.builder().build());
    }

    private static EventSubscriber subscriber(String processingGroup) {
        return new EventSubscriber() {
            @Override
            public String processingGroup() {
                return processingGroup;
            }

            @Override
            public <E extends Event> void on(EventMessage<E> eventMessage) {
            }
        };
    }

    private static final class UnusedConnectionFactory implements ConnectionFactory {

        @Override
        public Connection createConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection createConnection(String userName, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext(String userName, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext(String userName, String password, int sessionMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext(int sessionMode) {
            throw new UnsupportedOperationException();
        }
    }
}
