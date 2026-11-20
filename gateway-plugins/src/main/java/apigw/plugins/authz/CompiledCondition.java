package apigw.plugins.authz;

import apigw.core.auth.Principal;
import apigw.core.plugin.Exchange;
import apigw.model.Condition;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** An ABAC condition compiled to an evaluator tree; attribute names are pre-parsed. */
sealed interface CompiledCondition {

    boolean test(Exchange ex);

    static CompiledCondition of(Condition c) {
        return switch (c) {
            case Condition.All a -> new All(children(a.of()));
            case Condition.Any a -> new Any(children(a.of()));
            case Condition.Not n -> new Not(of(n.of()));
            case Condition.Compare cmp -> new Compare(cmp.op(), Attr.parse(cmp.attr()), cmp.value(),
                    Set.copyOf(cmp.values()), cmp.ref() == null ? null : Attr.parse(cmp.ref()));
        };
    }

    private static CompiledCondition[] children(List<Condition> of) {
        return of.stream().map(CompiledCondition::of).toArray(CompiledCondition[]::new);
    }

    record All(CompiledCondition[] of) implements CompiledCondition {
        @Override
        public boolean test(Exchange ex) {
            for (CompiledCondition c : of) {
                if (!c.test(ex)) {
                    return false;
                }
            }
            return true;
        }
    }

    record Any(CompiledCondition[] of) implements CompiledCondition {
        @Override
        public boolean test(Exchange ex) {
            for (CompiledCondition c : of) {
                if (c.test(ex)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Not(CompiledCondition of) implements CompiledCondition {
        @Override
        public boolean test(Exchange ex) {
            return !of.test(ex);
        }
    }

    record Compare(Condition.Op op, Attr attr, String value, Set<String> values, Attr ref)
            implements CompiledCondition {
        @Override
        public boolean test(Exchange ex) {
            List<String> left = attr.values(ex);
            return switch (op) {
                case EXISTS -> !left.isEmpty();
                case IN -> left.size() == 1 && values.contains(left.getFirst());
                case PREFIX -> left.size() == 1 && left.getFirst().startsWith(value);
                case EQ -> equal(left, ex);
                case NE -> !equal(left, ex);
                case CONTAINS -> {
                    if (ref == null) {
                        yield left.contains(value);
                    }
                    List<String> right = ref.values(ex);
                    yield right.size() == 1 && left.contains(right.getFirst());
                }
            };
        }

        /** Single-valued equality; a missing side never equals anything. */
        private boolean equal(List<String> left, Exchange ex) {
            if (left.size() != 1) {
                return false;
            }
            if (ref == null) {
                return left.getFirst().equals(value);
            }
            List<String> right = ref.values(ex);
            return right.size() == 1 && left.getFirst().equals(right.getFirst());
        }
    }

    /** A pre-parsed attribute reference. */
    record Attr(Kind kind, String name) {

        enum Kind {
            SUBJECT, TENANT, CONSUMER, ROLES, PRINCIPAL_ATTR, METHOD, PATH, HEADER, PARAM, ROUTE_ID, ROUTE_TENANT
        }

        static Attr parse(String a) {
            return switch (a) {
                case "principal.subject" -> new Attr(Kind.SUBJECT, null);
                case "principal.tenant" -> new Attr(Kind.TENANT, null);
                case "principal.consumer" -> new Attr(Kind.CONSUMER, null);
                case "principal.roles" -> new Attr(Kind.ROLES, null);
                case "request.method" -> new Attr(Kind.METHOD, null);
                case "request.path" -> new Attr(Kind.PATH, null);
                case "route.id" -> new Attr(Kind.ROUTE_ID, null);
                case "route.tenant" -> new Attr(Kind.ROUTE_TENANT, null);
                default -> {
                    if (a.startsWith("principal.attr.")) {
                        yield new Attr(Kind.PRINCIPAL_ATTR, a.substring("principal.attr.".length()));
                    }
                    if (a.startsWith("request.header.")) {
                        yield new Attr(Kind.HEADER, a.substring("request.header.".length()).toLowerCase(Locale.ROOT));
                    }
                    if (a.startsWith("request.param.")) {
                        yield new Attr(Kind.PARAM, a.substring("request.param.".length()));
                    }
                    throw new IllegalArgumentException("unknown attribute " + a);
                }
            };
        }

        List<String> values(Exchange ex) {
            Principal p = ex.principal();
            return switch (kind) {
                case SUBJECT -> one(p == null ? null : p.subject());
                case TENANT -> one(p == null ? null : p.tenantId());
                case CONSUMER -> one(p == null || p.consumer() == null ? null : p.consumer().id());
                case ROLES -> p == null ? List.of() : List.copyOf(p.roles());
                case PRINCIPAL_ATTR -> p == null ? List.of() : p.attributes().get(name);
                case METHOD -> one(ex.request().method().name());
                case PATH -> one(ex.request().path());
                case HEADER -> ex.request().headers().getAll(name);
                case PARAM -> one(ex.pathParam(name));
                case ROUTE_ID -> one(ex.route().id());
                case ROUTE_TENANT -> one(ex.route().tenantId());
            };
        }

        private static List<String> one(String v) {
            return v == null ? List.of() : List.of(v);
        }
    }
}
