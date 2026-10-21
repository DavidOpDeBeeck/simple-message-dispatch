package app.dodb.smd.spring.jms;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static app.dodb.smd.jms.JmsConfig.DEFAULT_CONCURRENCY;
import static app.dodb.smd.jms.JmsConfig.DEFAULT_RECEIVE_TIMEOUT;

@ConfigurationProperties(prefix = "smd.jms")
public class SMDJmsProperties {

    private boolean enabled = false;
    private String subscriberName;
    private OutletProperties outlet = new OutletProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSubscriberName() {
        return subscriberName;
    }

    public void setSubscriberName(String subscriberName) {
        this.subscriberName = subscriberName;
    }

    public OutletProperties getOutlet() {
        return outlet;
    }

    public void setOutlet(OutletProperties outlet) {
        this.outlet = outlet;
    }

    public static class OutletProperties {

        private int concurrency = DEFAULT_CONCURRENCY;
        private Duration receiveTimeout = DEFAULT_RECEIVE_TIMEOUT;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public Duration getReceiveTimeout() {
            return receiveTimeout;
        }

        public void setReceiveTimeout(Duration receiveTimeout) {
            this.receiveTimeout = receiveTimeout;
        }
    }
}
