package app.dodb.smd.artifactexport;

import java.util.Collection;
import java.util.Map;

// Writes maps, lists, strings and booleans the way JSON.stringify(value, null, 2) does, so the file reads like the
// hand-written examples and the same value always gives the same bytes.
final class Json {

    private Json() {
    }

    static String write(Object value) {
        var out = new StringBuilder();
        write(value, out, "");
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, String indent) {
        switch (value) {
            case null -> out.append("null");
            case String text -> string(text, out);
            case Boolean bool -> out.append(bool);
            case Integer number -> out.append(number);
            case Map<?, ?> map -> object(map, out, indent);
            case Collection<?> list -> array(list, out, indent);
            default -> throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void object(Map<?, ?> map, StringBuilder out, String indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        var inner = indent + "  ";
        out.append("{\n");
        var first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                out.append(",\n");
            }
            first = false;
            out.append(inner);
            string(String.valueOf(entry.getKey()), out);
            out.append(": ");
            write(entry.getValue(), out, inner);
        }
        out.append('\n').append(indent).append('}');
    }

    private static void array(Collection<?> list, StringBuilder out, String indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        var inner = indent + "  ";
        out.append("[\n");
        var first = true;
        for (var item : list) {
            if (!first) {
                out.append(",\n");
            }
            first = false;
            out.append(inner);
            write(item, out, inner);
        }
        out.append('\n').append(indent).append(']');
    }

    private static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
