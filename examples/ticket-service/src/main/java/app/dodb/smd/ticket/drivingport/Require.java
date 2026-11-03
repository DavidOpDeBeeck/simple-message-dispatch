package app.dodb.smd.ticket.drivingport;

public final class Require {

    private Require() {
    }

    public static String text(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message + "." + field + " must not be blank");
        }
        return value.trim();
    }
}
