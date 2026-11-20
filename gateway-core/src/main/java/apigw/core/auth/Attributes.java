package apigw.core.auth;

import java.util.List;
import java.util.Map;

/** Named string attributes of a principal (consumer attributes or token claims), for ABAC. */
@FunctionalInterface
public interface Attributes {

    Attributes NONE = name -> List.of();

    /** Values of {@code name}; empty when absent. Arrays yield one value per element. */
    List<String> get(String name);

    static Attributes of(Map<String, String> map) {
        if (map.isEmpty()) {
            return NONE;
        }
        return name -> {
            String v = map.get(name);
            return v == null ? List.of() : List.of(v);
        };
    }
}
