package app.dodb.smd.jms;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import jakarta.jms.ConnectionFactory;

import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class JmsConfig {

    public static final int DEFAULT_CONCURRENCY = 1;
    public static final Duration DEFAULT_RECEIVE_TIMEOUT = Duration.ofSeconds(1);

    private final ConnectionFactory connectionFactory;
    private final EventSerializer eventSerializer;
    private final TransactionProvider transactionProvider;
    private final Optional<String> subscriberName;
    private final int concurrency;
    private final Duration receiveTimeout;

    private JmsConfig(Builder builder) {
        this.connectionFactory = requireNonNull(builder.connectionFactory);
        this.eventSerializer = requireNonNull(builder.eventSerializer);
        this.transactionProvider = requireNonNull(builder.transactionProvider);
        this.subscriberName = Optional.ofNullable(builder.subscriberName);
        this.concurrency = requireNonNull(builder.concurrency);
        this.receiveTimeout = requireNonNull(builder.receiveTimeout);

        if (subscriberName.isPresent() && subscriberName.get().isBlank()) {
            throw new IllegalArgumentException("JMS subscriber name must not be blank; got '" + subscriberName.get() + "'");
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("JMS concurrency must be greater than zero; got " + concurrency);
        }
        if (receiveTimeout.isZero() || receiveTimeout.isNegative()) {
            throw new IllegalArgumentException("JMS receive timeout must be greater than zero; got " + receiveTimeout);
        }
    }

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public EventSerializer getEventSerializer() {
        return eventSerializer;
    }

    public TransactionProvider getTransactionProvider() {
        return transactionProvider;
    }

    public Optional<String> getSubscriberName() {
        return subscriberName;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public Duration getReceiveTimeout() {
        return receiveTimeout;
    }

    public static Builder withDefaults() {
        return new Builder()
            .concurrency(DEFAULT_CONCURRENCY)
            .receiveTimeout(DEFAULT_RECEIVE_TIMEOUT);
    }

    public static Builder withoutDefaults() {
        return new Builder();
    }

    public static class Builder {

        private ConnectionFactory connectionFactory;
        private EventSerializer eventSerializer;
        private TransactionProvider transactionProvider;
        private String subscriberName;
        private Integer concurrency;
        private Duration receiveTimeout;

        private Builder() {
        }

        public Builder connectionFactory(ConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
            return this;
        }

        public Builder eventSerializer(EventSerializer eventSerializer) {
            this.eventSerializer = eventSerializer;
            return this;
        }

        public Builder transactionProvider(TransactionProvider transactionProvider) {
            this.transactionProvider = transactionProvider;
            return this;
        }

        public Builder subscriberName(String subscriberName) {
            this.subscriberName = subscriberName;
            return this;
        }

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder receiveTimeout(Duration receiveTimeout) {
            this.receiveTimeout = receiveTimeout;
            return this;
        }

        public JmsConfig build() {
            return new JmsConfig(this);
        }
    }
}
