package berryboy.desktop;

import java.util.Map;

/** Small report encoder; scenario configuration uses standard Java Properties. */
final class JsonReport {
    private JsonReport() {
    }

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var result = new StringBuilder("{");
            for (var entry : map.entrySet()) {
                if (result.length() > 1) result.append(',');
                result.append(encode(entry.getKey().toString())).append(':').append(encode(entry.getValue()));
            }
            return result.append('}').toString();
        }
        var result = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}
