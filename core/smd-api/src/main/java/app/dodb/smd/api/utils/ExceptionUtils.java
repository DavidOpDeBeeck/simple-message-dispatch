package app.dodb.smd.api.utils;

public class ExceptionUtils {

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T rethrow(Throwable t) throws T {
        return (T) t;
    }
}
