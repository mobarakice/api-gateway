package apigw.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ABAC condition tree, decoded and syntax-checked here, compiled to an evaluator by the {@code authz}
 * plugin. Attributes (all values are strings; list-valued ones compare element-wise):
 * <ul>
 *   <li>{@code principal.subject}, {@code principal.tenant}, {@code principal.consumer},
 *       {@code principal.roles} (list), {@code principal.attr.<name>} (consumer attribute or token claim)</li>
 *   <li>{@code request.method}, {@code request.path}, {@code request.header.<name>},
 *       {@code request.param.<name>} (template path parameter)</li>
 *   <li>{@code route.id}, {@code route.tenant}</li>
 * </ul>
 * A missing attribute never equals anything (so {@code ne} on a missing attribute is true).
 */
public sealed interface Condition {

    Pattern ATTRIBUTE = Pattern.compile("principal\\.(subject|tenant|consumer|roles)"
            + "|principal\\.attr\\.[A-Za-z0-9_.:-]{1,128}"
            + "|request\\.(method|path)"
            + "|request\\.header\\.[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"
            + "|request\\.param\\.[A-Za-z_][A-Za-z0-9_]{0,63}"
            + "|route\\.(id|tenant)");

    /** Every child holds. */
    record All(List<Condition> of) implements Condition {
        public All {
            of = List.copyOf(of);
        }
    }

    /** At least one child holds. */
    record Any(List<Condition> of) implements Condition {
        public Any {
            of = List.copyOf(of);
        }
    }

    /** The child does not hold. */
    record Not(Condition of) implements Condition {
    }

    /**
     * {@code attr op operand}. Exactly one operand form is used, depending on {@code op}:
     * {@code value} (eq, ne, prefix, contains), {@code values} (in), {@code ref} — another attribute —
     * (eq, ne, contains), none (exists).
     */
    record Compare(Op op, String attr, String value, List<String> values, String ref) implements Condition {
        public Compare {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** Comparison operators. */
    enum Op {
        EQ, NE, IN, PREFIX, CONTAINS, EXISTS
    }
}
