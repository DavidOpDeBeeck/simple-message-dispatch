package app.dodb.smd.jms;

import jakarta.jms.Destination;
import jakarta.jms.JMSContext;

import static java.util.Objects.requireNonNull;

public record JmsDestination(Kind kind, String name) {

    public enum Kind {
        TOPIC,
        QUEUE
    }

    public static JmsDestination topic(String name) {
        return new JmsDestination(Kind.TOPIC, name);
    }

    public static JmsDestination queue(String name) {
        return new JmsDestination(Kind.QUEUE, name);
    }

    public JmsDestination {
        requireNonNull(kind);
        requireNonNull(name);
        if (name.isBlank()) {
            throw new IllegalArgumentException("JMS destination requires a non-blank name; got '" + name + "'");
        }
    }

    Destination createIn(JMSContext context) {
        return kind == Kind.TOPIC ? context.createTopic(name) : context.createQueue(name);
    }

    @Override
    public String toString() {
        return "JMS " + kind.name().toLowerCase() + " '" + name + "'";
    }
}
