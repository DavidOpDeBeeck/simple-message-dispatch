package app.dodb.smd.ticket.utils;

public class StringUtils {
    public static String requireText(String value, String field, String command) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(command + "." + field + " must not be blank");
        }
        return value.trim();
    }
}
