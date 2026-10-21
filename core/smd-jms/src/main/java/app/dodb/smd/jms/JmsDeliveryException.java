package app.dodb.smd.jms;

public class JmsDeliveryException extends RuntimeException {

    public JmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
