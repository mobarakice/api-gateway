package apigw.plugins.support;

import apigw.model.ConfigError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict field readers for plugin configs; every problem is collected with its path. */
public final class Configs {

    private Configs() {
    }

    private static String path(String p, String field) {
        return p.isEmpty() ? field : p + "." + field;
    }

    public static void known(JsonNode n, String p, Set<String> allowed, List<ConfigError> errors) {
        if (n == null || !n.isObject()) {
            errors.add(new ConfigError(p, "must be an object"));
            return;
        }
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (!allowed.contains(e.getKey())) {
                errors.add(new ConfigError(path(p, e.getKey()), "unknown field"));
            }
        }
    }

    /** {@code fallback == null} means required. */
    public static String string(JsonNode n, String p, String field, String fallback, List<ConfigError> errors) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            if (fallback == null) {
                errors.add(new ConfigError(path(p, field), "is required"));
            }
            return fallback;
        }
        if (!v.isTextual()) {
            errors.add(new ConfigError(path(p, field), "must be a string"));
            return fallback;
        }
        return v.textValue();
    }

    public static int integer(JsonNode n, String p, String field, int fallback, int min, int max,
            List<ConfigError> errors) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max) {
            errors.add(new ConfigError(path(p, field), "must be an integer between " + min + " and " + max));
            return fallback;
        }
        return v.intValue();
    }

    public static boolean bool(JsonNode n, String p, String field, boolean fallback, List<ConfigError> errors) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isBoolean()) {
            errors.add(new ConfigError(path(p, field), "must be a boolean"));
            return fallback;
        }
        return v.booleanValue();
    }

    public static List<String> strings(JsonNode n, String p, String field, List<ConfigError> errors) {
        JsonNode v = n == null ? null : n.get(field);
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            errors.add(new ConfigError(path(p, field), "must be an array of strings"));
            return out;
        }
        for (int i = 0; i < v.size(); i++) {
            if (!v.get(i).isTextual()) {
                errors.add(new ConfigError(path(p, field) + "[" + i + "]", "must be a string"));
            } else {
                out.add(v.get(i).textValue());
            }
        }
        return out;
    }

    /** RFC 9110 token (header name, query key characters are checked separately). */
    public static boolean token(String s) {
        if (s.isEmpty() || s.length() > 256) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Lower-cased header names from {@code field}; each must be a token. */
    public static List<String> headerNames(JsonNode n, String p, String field, List<ConfigError> errors) {
        List<String> raw = strings(n, p, field, errors);
        List<String> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            if (!token(raw.get(i))) {
                errors.add(new ConfigError(path(p, field) + "[" + i + "]", "not a valid header name"));
            } else {
                out.add(raw.get(i).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }
}
