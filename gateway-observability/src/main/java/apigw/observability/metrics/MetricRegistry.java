package apigw.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Named metric families with Prometheus text exposition (format 0.0.4). Creating a series takes a map
 * lookup; recording on a held {@link Counter} or {@link Histogram} does not. Series can be removed
 * (e.g. meters of deleted routes) so cardinality follows the live configuration.
 */
public final class MetricRegistry {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private enum Type { COUNTER, GAUGE, HISTOGRAM }

    private final Map<String, Family> families = new ConcurrentSkipListMap<>();

    /** Gets or creates the counter series {@code name{labels}}; labels are name/value pairs. */
    public Counter counter(String name, String help, String... labels) {
        return (Counter) family(name, help, Type.COUNTER, null).series(labels, Counter::new);
    }

    /** Gets or creates the histogram series {@code name{labels}} with the given bounds (seconds). */
    public Histogram histogram(String name, String help, double[] bounds, String... labels) {
        return (Histogram) family(name, help, Type.HISTOGRAM, null).series(labels, () -> new Histogram(bounds));
    }

    /** A gauge whose value is read at scrape time. */
    public void gauge(String name, String help, DoubleSupplier value, String... labels) {
        family(name, help, Type.GAUGE, null).series(labels, () -> value);
    }

    /** A counter whose value is read at scrape time (e.g. from a JDK bean). */
    public void counterFn(String name, String help, DoubleSupplier value, String... labels) {
        family(name, help, Type.COUNTER, null).series(labels, () -> value);
    }

    /** A gauge family whose series are produced at scrape time (e.g. one per upstream). */
    public void gaugeFamily(String name, String help, List<String> labelNames, Consumer<GaugeSink> collector) {
        for (String l : labelNames) {
            requireLabel(l);
        }
        family(name, help, Type.GAUGE, new Dynamic(List.copyOf(labelNames), collector));
    }

    /** Removes the series of {@code name} whose label {@code label} has a value matching {@code remove}. */
    public void removeIf(String name, String label, Predicate<String> remove) {
        Family f = families.get(name);
        if (f == null) {
            return;
        }
        String prefix = label + "=\"";
        f.series.keySet().removeIf(key -> {
            int at = key.startsWith(prefix) ? 0 : key.indexOf("," + prefix);
            if (at < 0) {
                return false;
            }
            int from = key.indexOf('"', at) + 1;
            return remove.test(unescape(key, from));
        });
    }

    private Family family(String name, String help, Type type, Dynamic dynamic) {
        Family f = families.computeIfAbsent(name, n -> {
            if (!NAME.matcher(n).matches()) {
                throw new IllegalArgumentException("invalid metric name: " + n);
            }
            return new Family(n, help, type, dynamic);
        });
        if (f.type != type || (dynamic == null) != (f.dynamic == null)) {
            throw new IllegalArgumentException("metric " + name + " already registered as another type");
        }
        return f;
    }

    /** The whole registry in Prometheus text format. Call off the event loop for large registries. */
    public String scrape() {
        StringBuilder b = new StringBuilder(16_384);
        for (Family f : families.values()) {
            f.write(b);
        }
        return b.toString();
    }

    private static void requireLabel(String l) {
        if (!LABEL.matcher(l).matches() || l.startsWith("__") || "le".equals(l)) {
            throw new IllegalArgumentException("invalid label name: " + l);
        }
    }

    /** {@code a="x",b="y"} from name/value pairs, values escaped. */
    static String render(String... labels) {
        if (labels.length % 2 != 0) {
            throw new IllegalArgumentException("labels must be name/value pairs");
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < labels.length; i += 2) {
            requireLabel(labels[i]);
            if (i > 0) {
                b.append(',');
            }
            b.append(labels[i]).append("=\"");
            escape(b, Objects.requireNonNull(labels[i + 1], "label value"));
            b.append('"');
        }
        return b.toString();
    }

    private static void escape(StringBuilder b, String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
    }

    private static String unescape(String key, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '"') {
                break;
            }
            if (c == '\\' && i + 1 < key.length()) {
                char n = key.charAt(++i);
                b.append(n == 'n' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    static String number(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private record Dynamic(List<String> labelNames, Consumer<GaugeSink> collector) {
    }

    private static final class Family {
        private final String name;
        private final String help;
        private final Type type;
        private final Dynamic dynamic;
        private final Map<String, Object> series = new ConcurrentHashMap<>();

        Family(String name, String help, Type type, Dynamic dynamic) {
            this.name = name;
            this.help = help;
            this.type = type;
            this.dynamic = dynamic;
        }

        Object series(String[] labels, java.util.function.Supplier<Object> create) {
            return series.computeIfAbsent(render(labels), k -> create.get());
        }

        void write(StringBuilder b) {
            List<Map.Entry<String, Object>> rows = new ArrayList<>(series.entrySet());
            List<String[]> dyn = new ArrayList<>();
            if (dynamic != null) {
                dynamic.collector().accept((value, values) -> {
                    if (values.length != dynamic.labelNames().size()) {
                        throw new IllegalArgumentException(name + ": expected " + dynamic.labelNames().size()
                                + " label values");
                    }
                    String[] pairs = new String[values.length * 2];
                    for (int i = 0; i < values.length; i++) {
                        pairs[2 * i] = dynamic.labelNames().get(i);
                        pairs[2 * i + 1] = values[i];
                    }
                    dyn.add(new String[] {render(pairs), number(value)});
                });
            }
            if (rows.isEmpty() && dyn.isEmpty()) {
                return;
            }
            rows.sort(Map.Entry.comparingByKey());
            b.append("# HELP ").append(name).append(' ');
            escapeHelp(b, help);
            b.append("\n# TYPE ").append(name).append(' ').append(type.name().toLowerCase(java.util.Locale.ROOT))
                    .append('\n');
            for (Map.Entry<String, Object> row : rows) {
                String labels = row.getKey();
                switch (row.getValue()) {
                    case Counter c -> sample(b, name, labels, Long.toString(c.get()));
                    case Histogram h -> writeHistogram(b, labels, h);
                    case DoubleSupplier s -> sample(b, name, labels, number(s.getAsDouble()));
                    default -> throw new IllegalStateException("unknown series type");
                }
            }
            for (String[] d : dyn) {
                sample(b, name, d[0], d[1]);
            }
        }

        private void writeHistogram(StringBuilder b, String labels, Histogram h) {
            double[] bounds = h.bounds();
            long[] cumulative = h.cumulative();
            String sep = labels.isEmpty() ? "" : labels + ",";
            for (int i = 0; i < bounds.length; i++) {
                sample(b, name + "_bucket", sep + "le=\"" + number(bounds[i]) + "\"", Long.toString(cumulative[i]));
            }
            long count = cumulative[cumulative.length - 1];
            sample(b, name + "_bucket", sep + "le=\"+Inf\"", Long.toString(count));
            sample(b, name + "_sum", labels, number(h.sumSeconds()));
            sample(b, name + "_count", labels, Long.toString(count));
        }

        private static void sample(StringBuilder b, String name, String labels, String value) {
            b.append(name);
            if (!labels.isEmpty()) {
                b.append('{').append(labels).append('}');
            }
            b.append(' ').append(value).append('\n');
        }

        private static void escapeHelp(StringBuilder b, String help) {
            for (int i = 0; i < help.length(); i++) {
                char c = help.charAt(i);
                switch (c) {
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    default -> b.append(c);
                }
            }
        }
    }
}
