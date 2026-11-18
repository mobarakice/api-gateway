package apigw.plugins.headers;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import java.util.List;
import java.util.Map;

/** Precompiled edits: names are lower case and pre-encoded once, so applying them allocates nothing extra. */
final class HeaderEdits {

    static final HeaderEdits NONE = new HeaderEdits(Map.of(), Map.of(), List.of());

    private final CharSequence[] removeNames;
    private final CharSequence[] setNames;
    private final CharSequence[] setValues;
    private final CharSequence[] addNames;
    private final CharSequence[] addValues;

    HeaderEdits(Map<String, String> set, Map<String, String> add, List<String> remove) {
        this.removeNames = remove.stream().map(HttpHeaders::createOptimized).toArray(CharSequence[]::new);
        this.setNames = set.keySet().stream().map(HttpHeaders::createOptimized).toArray(CharSequence[]::new);
        this.setValues = set.values().stream().map(HttpHeaders::createOptimized).toArray(CharSequence[]::new);
        this.addNames = add.keySet().stream().map(HttpHeaders::createOptimized).toArray(CharSequence[]::new);
        this.addValues = add.values().stream().map(HttpHeaders::createOptimized).toArray(CharSequence[]::new);
    }

    boolean isEmpty() {
        return removeNames.length == 0 && setNames.length == 0 && addNames.length == 0;
    }

    void apply(MultiMap headers) {
        for (CharSequence n : removeNames) {
            headers.remove(n);
        }
        for (int i = 0; i < setNames.length; i++) {
            headers.set(setNames[i], setValues[i]);
        }
        for (int i = 0; i < addNames.length; i++) {
            headers.add(addNames[i], addValues[i]);
        }
    }
}
