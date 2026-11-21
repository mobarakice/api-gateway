package apigw.controlplane.api;

import apigw.config.etcd.KeyValue;
import apigw.controlplane.Json;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.auth.Actor;
import apigw.controlplane.auth.AdminAuth;
import apigw.controlplane.auth.Role;
import apigw.controlplane.changes.ChangeSet;
import apigw.controlplane.changes.ChangeSets;
import apigw.controlplane.changes.Envelopes;
import apigw.controlplane.changes.Etags;
import apigw.controlplane.changes.Publisher;
import apigw.controlplane.store.Kind;
import apigw.controlplane.store.KvStore;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.PluginRegistry;
import apigw.model.ApiKeyHash;
import apigw.model.Cidr;
import apigw.model.ConfigError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Admin API, {@code /admin/v1} (docs/api/admin-v1.yaml). Reads go to etcd directly; every write
 * is staged in a change set and reaches the gateways only through {@link Publisher}. Every request
 * but {@code health} and {@code ready} needs a bearer token, and every operation checks the caller's
 * role here, server-side; the console's role-aware UI is a convenience, not the control.
 */
public final class AdminApi implements Handler<HttpServerRequest> {

    private static final String BASE = "/admin/v1/";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder CURSOR_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder CURSOR_DEC = Base64.getUrlDecoder();

    private final String prefix;
    private final KvStore kv;
    private final AdminAuth auth;
    private final ChangeSets changeSets;
    private final Publisher publisher;
    private final AuditLog audit;
    private final Clock clock;
    private final List<Cidr> trustedProxies;
    private final Supplier<Future<Boolean>> ready;
    private final Map<String, PluginFactory> plugins = new LinkedHashMap<>();
    private final Map<String, JsonNode> schemas = new LinkedHashMap<>();

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AdminApi(String prefix, KvStore kv, AdminAuth auth, ChangeSets changeSets, Publisher publisher,
            AuditLog audit, PluginRegistry registry, Clock clock, List<Cidr> trustedProxies,
            Supplier<Future<Boolean>> ready) {
        this.prefix = prefix;
        this.kv = kv;
        this.auth = auth;
        this.changeSets = changeSets;
        this.publisher = publisher;
        this.audit = audit;
        this.clock = clock;
        this.trustedProxies = List.copyOf(trustedProxies);
        this.ready = ready;
        List<PluginFactory> sorted = new ArrayList<>(registry.all());
        sorted.sort(Comparator.comparing(PluginFactory::name));
        for (PluginFactory f : sorted) {
            plugins.put(f.name(), f);
            schemas.put(f.name(), loadSchema(f));
        }
    }

    private static JsonNode loadSchema(PluginFactory f) {
        try (InputStream in = AdminApi.class.getClassLoader().getResourceAsStream(f.schemaResource())) {
            if (in == null) {
                throw new IllegalStateException("plugin " + f.name() + " ships no schema " + f.schemaResource());
            }
            return Json.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read schema of plugin " + f.name(), e);
        }
    }

    @Override
    public void handle(HttpServerRequest req) {
        byte[] rnd = new byte[8];
        RANDOM.nextBytes(rnd);
        String requestId = HexFormat.of().formatHex(rnd);
        String path = req.path();
        if (!path.startsWith(BASE)) {
            Http.problem(req, requestId, ApiException.notFound("unknown path"));
            return;
        }
        String[] seg = path.substring(BASE.length()).split("/", -1);
        if (seg.length == 1 && ("health".equals(seg[0]) || "ready".equals(seg[0]))) {
            if (req.method() != HttpMethod.GET) {
                Http.problem(req, requestId, methodNotAllowed());
                return;
            }
            probe(req, requestId, "ready".equals(seg[0]));
            return;
        }
        String ip = Http.clientIp(req, trustedProxies);
        // Pause until authenticated so a body is not lost while the token is checked.
        req.pause();
        auth.authenticate(req.getHeader("authorization"), ip, requestId)
                .compose(actor -> {
                    if (actor.role() == null) {
                        audit.refused(AuditLog.Event.refused(req.method().name() + " " + path, "denied", actor, path,
                                "no admin role"));
                        return Future.failedFuture(forbidden());
                    }
                    return route(req, seg, actor);
                })
                .onSuccess(r -> Http.send(req, requestId, r))
                .onFailure(e -> Http.problem(req, requestId, e))
                // Bodies are read by Http.json (which resumes); drain whatever is left.
                .onComplete(ignored -> req.resume());
    }

    private void probe(HttpServerRequest req, String requestId, boolean readiness) {
        if (!readiness) {
            Http.send(req, requestId, Http.Response.ok(Json.MAPPER.createObjectNode().put("status", "up")));
            return;
        }
        ready.get().onComplete(ar -> {
            boolean ok = ar.succeeded() && Boolean.TRUE.equals(ar.result());
            Http.send(req, requestId, new Http.Response(ok ? 200 : 503,
                    Json.MAPPER.createObjectNode().put("status", ok ? "ready" : "not ready"), null));
        });
    }

    // ------------------------------------------------------------------ routing

    private Future<Http.Response> route(HttpServerRequest req, String[] seg, Actor a) {
        HttpMethod m = req.method();
        String s0 = seg[0];
        int n = seg.length;
        return switch (s0) {
            case "me" -> only(m, HttpMethod.GET, n == 1, () -> me(a));
            case "plugins" -> n == 1 ? only(m, HttpMethod.GET, true, () -> catalog(a))
                    : only(m, HttpMethod.GET, n == 3 && "schema".equals(seg[2]), () -> schema(a, seg[1]));
            case "gateways" -> only(m, HttpMethod.GET, n == 1, () -> gateways(a));
            case "audit" -> only(m, HttpMethod.GET, n == 1, () -> auditLog(req, a));
            case "history" -> history(req, seg, a);
            case "changesets" -> changesets(req, seg, a);
            case "settings" -> n != 1 ? Future.failedFuture(ApiException.notFound("unknown path"))
                    : objectRoute(req, Kind.SETTINGS, Kind.SETTINGS.keyPrefix(), "settings", a);
            case "consumers" -> n >= 3 && "credentials".equals(seg[2]) ? credentials(req, seg, a)
                    : collection(req, Kind.CONSUMERS, seg, 1, a);
            case "policies" -> n >= 2 ? Kind.byApiPath("policies/" + seg[1])
                    .map(k -> collection(req, k, seg, 2, a))
                    .orElseGet(() -> Future.failedFuture(ApiException.notFound("unknown path")))
                    : Future.failedFuture(ApiException.notFound("unknown path"));
            default -> Kind.byApiPath(s0).map(k -> collection(req, k, seg, 1, a))
                    .orElseGet(() -> Future.failedFuture(ApiException.notFound("unknown path")));
        };
    }

    private static Future<Http.Response> only(HttpMethod m, HttpMethod allowed, boolean pathOk,
            Supplier<Future<Http.Response>> h) {
        if (!pathOk) {
            return Future.failedFuture(ApiException.notFound("unknown path"));
        }
        return m == allowed ? h.get() : Future.failedFuture(methodNotAllowed());
    }

    private static ApiException methodNotAllowed() {
        return new ApiException(405, "Method not allowed", "this method is not supported here");
    }

    private static ApiException forbidden() {
        return new ApiException(403, "Forbidden", "your role does not allow this operation");
    }

    private Future<Void> require(Actor a, Role role, String action, String target) {
        if (a.may(role)) {
            return Future.succeededFuture();
        }
        audit.refused(AuditLog.Event.refused(action, "denied", a, target, "needs " + role.claimName()));
        return Future.failedFuture(forbidden());
    }

    // ------------------------------------------------------------------ identity, catalog, status

    private Future<Http.Response> me(Actor a) {
        ObjectNode n = Json.MAPPER.createObjectNode().put("subject", a.subject()).put("role", a.role().claimName());
        ArrayNode roles = n.putArray("roles");
        a.roles().stream().sorted().forEach(r -> roles.add(r.claimName()));
        ArrayNode may = n.putArray("permissions");
        for (Role r : Role.values()) {
            if (a.may(r)) {
                may.add(r.claimName());
            }
        }
        return Future.succeededFuture(Http.Response.ok(n));
    }

    private Future<Http.Response> catalog(Actor a) {
        ArrayNode items = Json.MAPPER.createArrayNode();
        for (PluginFactory f : plugins.values()) {
            JsonNode schema = schemas.get(f.name());
            ObjectNode p = items.addObject().put("name", f.name()).put("version", f.version())
                    .put("priority", f.priority()).put("authenticates", f.authenticates())
                    .put("title", schema.path("title").asText(f.name()))
                    .put("description", schema.path("description").asText(""))
                    .put("schema", BASE + "plugins/" + f.name() + "/schema");
            ArrayNode phases = p.putArray("phases");
            f.phases().stream().sorted().forEach(ph -> phases.add(ph.name()));
        }
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.set("items", items);
        return Future.succeededFuture(Http.Response.ok(n));
    }

    private Future<Http.Response> schema(Actor a, String name) {
        JsonNode s = schemas.get(name);
        if (s == null) {
            return Future.failedFuture(ApiException.notFound("no such plugin"));
        }
        return Future.succeededFuture(Http.Response.ok(s));
    }

    private Future<Http.Response> gateways(Actor a) {
        return kv.all(List.of(prefix + "nodes/", prefix + "history/head")).map(all -> {
            KeyValue head = all.kvs().get(prefix + "history/head");
            long configRevision = head == null ? 0 : head.modRevision();
            ArrayNode items = Json.MAPPER.createArrayNode();
            all.kvs().values().stream().filter(k -> k.key().startsWith(prefix + "nodes/"))
                    .sorted(Comparator.comparing(KeyValue::key)).forEach(k -> {
                        JsonNode node;
                        try {
                            node = Json.parse(k.value());
                        } catch (IllegalArgumentException e) {
                            return;
                        }
                        if (!node.isObject()) {
                            return;
                        }
                        ObjectNode o = ((ObjectNode) node).deepCopy();
                        long applied = o.path("appliedRevision").asLong(0);
                        o.put("behind", applied < configRevision);
                        o.put("heartbeatRevision", k.modRevision());
                        items.add(o);
                    });
            ObjectNode n = Json.MAPPER.createObjectNode().put("configRevision", configRevision)
                    .put("revision", all.revision());
            n.set("items", items);
            return Http.Response.ok(n);
        });
    }

    private Future<Http.Response> auditLog(HttpServerRequest req, Actor a) {
        return require(a, Role.ADMIN, "audit.read", "audit").compose(v -> audit.recent(limit(req),
                req.getParam("before"))).map(events -> {
                    ObjectNode n = Json.MAPPER.createObjectNode();
                    ArrayNode items = n.putArray("items");
                    events.forEach(items::add);
                    if (!events.isEmpty() && events.size() == limit(req)) {
                        n.put("nextBefore", events.getLast().path("id").asText());
                    }
                    return Http.Response.ok(n);
                });
    }

    // ------------------------------------------------------------------ live objects

    private Future<Http.Response> collection(HttpServerRequest req, Kind kind, String[] seg, int idAt, Actor a) {
        if (seg.length == idAt) {
            return only(req.method(), HttpMethod.GET, true, () -> list(req, prefix + kind.keyPrefix()));
        }
        if (seg.length != idAt + 1 || seg[idAt].isEmpty()) {
            return Future.failedFuture(ApiException.notFound("unknown path"));
        }
        String id = seg[idAt];
        Envelopes.requireId(id, "id");
        return objectRoute(req, kind, kind.key(id), id, a);
    }

    private Future<Http.Response> objectRoute(HttpServerRequest req, Kind kind, String rel, String id, Actor a) {
        HttpMethod m = req.method();
        if (m == HttpMethod.GET) {
            return read(rel);
        }
        if (m == HttpMethod.PUT) {
            String cs = changeSetParam(req);
            return require(a, kind.writeRole(), "stage.put", rel).compose(v -> Http.json(req)).compose(body -> {
                ObjectNode env = Envelopes.build(kind, id, body, a.subject(), clock.instant());
                List<ConfigError> errors = Envelopes.check(rel, env);
                if (!errors.isEmpty()) {
                    audit.refused(AuditLog.Event.refused("stage.put", "invalid", a, rel, errors.size() + " errors"));
                    throw ApiException.invalid(errors);
                }
                return stage(req, cs, rel, a, base -> ChangeSets.put(rel, env, base, a), false);
            });
        }
        if (m == HttpMethod.DELETE) {
            String cs = changeSetParam(req);
            return require(a, kind.writeRole(), "stage.delete", rel)
                    .compose(v -> stage(req, cs, rel, a, base -> ChangeSets.delete(rel, base, a), true));
        }
        return Future.failedFuture(methodNotAllowed());
    }

    private interface OpFor {
        ChangeSet.Op op(long base);
    }

    private Future<Http.Response> stage(HttpServerRequest req, String cs, String rel, Actor a, OpFor op,
            boolean delete) {
        return kv.get(prefix + rel).compose(live -> {
            long liveRev = live.map(KeyValue::modRevision).orElse(0L);
            long base;
            try {
                base = ChangeSets.base(rel, liveRev, req.getHeader("if-match"), delete);
            } catch (ApiException e) {
                audit.refused(AuditLog.Event.refused(delete ? "stage.delete" : "stage.put",
                        e.status() == 412 ? "conflict" : "invalid", a, rel, e.title()));
                throw e;
            }
            if (delete && liveRev == 0) {
                throw ApiException.notFound("no such object");
            }
            return changeSets.stage(cs, op.op(base), a);
        }).map(stored -> {
            ChangeSet.Op staged = stored.changeSet().ops().stream().filter(o -> o.key().equals(rel)).findFirst()
                    .orElseThrow();
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.putObject("changeSet").put("id", stored.changeSet().id()).put("etag", Etags.of(stored.revision()))
                    .put("ops", stored.changeSet().ops().size());
            n.putObject("op").put("key", rel).put("action", staged.action().name())
                    .put("baseRevision", staged.baseRevision());
            return new Http.Response(200, n, Etags.of(stored.revision()));
        });
    }

    private static String changeSetParam(HttpServerRequest req) {
        String cs = req.getParam("changeset");
        if (cs == null || cs.isBlank()) {
            throw ApiException.badRequest("writes are staged: pass ?changeset={id} (create one with POST /changesets)");
        }
        Envelopes.requireId(cs, "changeset");
        return cs;
    }

    private Future<Http.Response> read(String rel) {
        return kv.get(prefix + rel).map(o -> {
            KeyValue k = o.orElseThrow(() -> ApiException.notFound("no such object"));
            return new Http.Response(200, item(rel, k), Etags.of(k.modRevision()));
        });
    }

    private JsonNode item(String rel, KeyValue k) {
        JsonNode env;
        try {
            env = Json.parse(k.value());
        } catch (IllegalArgumentException e) {
            env = Json.MAPPER.createObjectNode().put("invalid", true);
        }
        ObjectNode n = Json.MAPPER.createObjectNode().put("key", rel).put("revision", k.modRevision())
                .put("etag", Etags.of(k.modRevision()));
        JsonNode red = Envelopes.redact(rel, env);
        if (red.isObject()) {
            n.setAll((ObjectNode) red);
        }
        return n;
    }

    private Future<Http.Response> list(HttpServerRequest req, String absPrefix) {
        int limit = limit(req);
        String after = cursor(req.getParam("cursor"), absPrefix);
        return kv.page(absPrefix, after, limit).map(page -> {
            ObjectNode n = Json.MAPPER.createObjectNode().put("revision", page.revision());
            ArrayNode items = n.putArray("items");
            for (KeyValue k : page.kvs()) {
                items.add(item(k.key().substring(prefix.length()), k));
            }
            if (page.more() && !page.kvs().isEmpty()) {
                String last = page.kvs().getLast().key().substring(absPrefix.length());
                n.put("nextCursor", CURSOR_ENC.encodeToString(last.getBytes(StandardCharsets.UTF_8)));
            }
            return Http.Response.ok(n);
        });
    }

    private static String cursor(String raw, String absPrefix) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return absPrefix + new String(CURSOR_DEC.decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid cursor");
        }
    }

    private static int limit(HttpServerRequest req) {
        String raw = req.getParam("limit");
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int l = Integer.parseInt(raw);
            if (l < 1 || l > MAX_LIMIT) {
                throw new NumberFormatException();
            }
            return l;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("limit must be 1.." + MAX_LIMIT);
        }
    }

    // ------------------------------------------------------------------ credentials (write-only secrets)

    private Future<Http.Response> credentials(HttpServerRequest req, String[] seg, Actor a) {
        String consumer = seg[1];
        Envelopes.requireId(consumer, "consumer id");
        HttpMethod m = req.method();
        if (seg.length == 3) {
            if (m == HttpMethod.GET) {
                return list(req, prefix + Kind.CREDENTIALS.keyPrefix() + consumer + "/");
            }
            if (m == HttpMethod.POST) {
                String cs = changeSetParam(req);
                return require(a, Role.EDITOR, "credential.create", "consumers/" + consumer)
                        .compose(v -> Http.json(req)).compose(body -> createKey(req, cs, consumer, body, a));
            }
            return Future.failedFuture(methodNotAllowed());
        }
        if (seg.length != 4) {
            return Future.failedFuture(ApiException.notFound("unknown path"));
        }
        String id = seg[3];
        Envelopes.requireId(id, "credential id");
        String rel = Kind.credentialKey(consumer, id);
        if (m == HttpMethod.GET) {
            return read(rel);
        }
        if (m == HttpMethod.DELETE) {
            String cs = changeSetParam(req);
            return require(a, Role.EDITOR, "stage.delete", rel)
                    .compose(v -> stage(req, cs, rel, a, base -> ChangeSets.delete(rel, base, a), true));
        }
        return Future.failedFuture(methodNotAllowed());
    }

    /**
     * Generates an API key: only its salted hash is staged; the key itself is in this response and
     * nowhere else (not in etcd, logs or audit).
     */
    private Future<Http.Response> createKey(HttpServerRequest req, String cs, String consumer, JsonNode body,
            Actor a) {
        if (!body.isObject() || !"api-key".equals(body.path("type").asText("api-key"))) {
            throw ApiException.invalid(List.of(new ConfigError("type", "must be \"api-key\"")));
        }
        for (Map.Entry<String, JsonNode> e : body.properties()) {
            if (!"type".equals(e.getKey()) && !"tenantId".equals(e.getKey())) {
                throw ApiException.invalid(List.of(new ConfigError(e.getKey(),
                        "unknown field (allowed: type, tenantId); secrets are generated, never supplied")));
            }
        }
        byte[] rnd = new byte[8];
        RANDOM.nextBytes(rnd);
        String id = "key-" + HexFormat.of().formatHex(rnd);
        RANDOM.nextBytes(rnd);
        String keyId = HexFormat.of().formatHex(rnd);
        String secret = ApiKeyHash.newSecret();
        ObjectNode spec = Json.MAPPER.createObjectNode().put("type", "api-key").put("keyId", keyId)
                .put("hash", ApiKeyHash.hash(secret));
        ObjectNode apiBody = Json.MAPPER.createObjectNode();
        if (body.hasNonNull("tenantId")) {
            apiBody.set("tenantId", body.get("tenantId"));
        }
        apiBody.set("spec", spec);
        String rel = Kind.credentialKey(consumer, id);
        ObjectNode env = Envelopes.build(Kind.CREDENTIALS, id, apiBody, a.subject(), clock.instant());
        List<ConfigError> errors = Envelopes.check(rel, env);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return stage(req, cs, rel, a, base -> ChangeSets.put(rel, env, base, a), false).map(r -> {
            ObjectNode n = ((ObjectNode) r.body()).put("id", id).put("keyId", keyId)
                    .put("apiKey", keyId + "." + secret)
                    .put("note", "store the key now: it is not stored and cannot be shown again");
            return new Http.Response(201, n, r.etag());
        });
    }

    // ------------------------------------------------------------------ change sets

    private Future<Http.Response> changesets(HttpServerRequest req, String[] seg, Actor a) {
        HttpMethod m = req.method();
        int n = seg.length;
        if (n == 1) {
            if (m == HttpMethod.GET) {
                return changeSets.list().map(all -> {
                    ObjectNode out = Json.MAPPER.createObjectNode();
                    ArrayNode items = out.putArray("items");
                    all.forEach(s -> items.add(summary(s)));
                    return Http.Response.ok(out);
                });
            }
            if (m == HttpMethod.POST) {
                return require(a, Role.EDITOR, "changeset.create", "changesets").compose(v -> Http.json(req))
                        .compose(body -> changeSets.create(body.path("title").asText(null), a))
                        .map(s -> new Http.Response(201, full(s), Etags.of(s.revision())));
            }
            return Future.failedFuture(methodNotAllowed());
        }
        String id = seg[1];
        Envelopes.requireId(id, "change set id");
        if (n == 2) {
            if (m == HttpMethod.GET) {
                return changeSets.get(id).map(s -> new Http.Response(200, full(s), Etags.of(s.revision())));
            }
            if (m == HttpMethod.DELETE) {
                return require(a, Role.EDITOR, "changeset.discard", id)
                        .compose(v -> changeSets.discard(id, req.getHeader("if-match"), a))
                        .map(v -> new Http.Response(204, null, null));
            }
            return Future.failedFuture(methodNotAllowed());
        }
        if (n != 3) {
            return Future.failedFuture(ApiException.notFound("unknown path"));
        }
        return switch (seg[2]) {
            case "ops" -> only(m, HttpMethod.DELETE, true, () -> require(a, Role.EDITOR, "changeset.unstage", id)
                    .compose(v -> {
                        String key = req.getParam("key");
                        if (key == null || Kind.ofKey(key).isEmpty()) {
                            throw ApiException.badRequest("pass ?key= with the staged object's key, e.g. routes/a");
                        }
                        return changeSets.unstage(id, key, a);
                    })
                    .map(s -> new Http.Response(200, full(s), Etags.of(s.revision()))));
            case "validate" -> only(m, HttpMethod.POST, true, () -> require(a, Role.EDITOR, "changeset.validate", id)
                    .compose(v -> changeSets.get(id)).compose(s -> publisher.validate(s.changeSet()))
                    .map(v -> Http.Response.ok(validation(v))));
            case "diff" -> only(m, HttpMethod.GET, true, () -> changeSets.get(id)
                    .compose(s -> publisher.diff(s.changeSet())).map(d -> Http.Response.ok(diff(d))));
            case "publish" -> only(m, HttpMethod.POST, true, () -> require(a, Role.PUBLISHER, "publish", id)
                    .compose(v -> publisher.publish(id, req.getHeader("if-match"), a))
                    .map(p -> Http.Response.ok(published(p))));
            default -> Future.failedFuture(ApiException.notFound("unknown path"));
        };
    }

    private static ObjectNode summary(ChangeSets.Stored s) {
        ChangeSet cs = s.changeSet();
        return Json.MAPPER.createObjectNode().put("id", cs.id()).put("title", cs.title())
                .put("createdBy", cs.createdBy()).put("createdAt", cs.createdAt().toString())
                .put("updatedAt", cs.updatedAt().toString()).put("ops", cs.ops().size())
                .put("etag", Etags.of(s.revision()));
    }

    private static ObjectNode full(ChangeSets.Stored s) {
        ObjectNode n = summary(s);
        ArrayNode ops = n.putArray("items");
        for (ChangeSet.Op o : s.changeSet().ops()) {
            ObjectNode on = ops.addObject().put("key", o.key()).put("action", o.action().name())
                    .put("baseRevision", o.baseRevision()).put("stagedBy", o.stagedBy());
            on.set("value", Envelopes.redact(o.key(), o.value()));
        }
        return n;
    }

    private static ObjectNode validation(Publisher.Validation v) {
        ObjectNode n = Json.MAPPER.createObjectNode().put("valid", v.valid());
        ArrayNode errors = n.putArray("errors");
        v.errors().forEach(e -> errors.addObject().put("path", e.path()).put("message", e.message()));
        ArrayNode conflicts = n.putArray("conflicts");
        v.conflicts().forEach(c -> conflicts.addObject().put("key", c.key()).put("expectedRevision",
                c.expectedRevision()).put("actualRevision", c.actualRevision()));
        return n;
    }

    private static ObjectNode diff(List<Publisher.DiffEntry> entries) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        ArrayNode items = n.putArray("items");
        for (Publisher.DiffEntry d : entries) {
            ObjectNode o = items.addObject().put("key", d.key()).put("action", d.action().name())
                    .put("baseRevision", d.baseRevision()).put("liveRevision", d.liveRevision())
                    .put("stale", d.stale());
            o.set("before", d.before());
            o.set("after", d.after());
        }
        return n;
    }

    private static ObjectNode published(Publisher.Published p) {
        return Json.MAPPER.createObjectNode().put("seq", p.seq()).put("revision", p.revision())
                .put("changeSetId", p.changeSetId());
    }

    // ------------------------------------------------------------------ history

    private Future<Http.Response> history(HttpServerRequest req, String[] seg, Actor a) {
        HttpMethod m = req.method();
        if (seg.length == 1) {
            return only(m, HttpMethod.GET, true, () -> publisher.history().map(entries -> {
                ObjectNode n = Json.MAPPER.createObjectNode();
                ArrayNode items = n.putArray("items");
                for (JsonNode e : entries) {
                    ObjectNode s = ((ObjectNode) e).deepCopy();
                    s.put("ops", e.path("ops").size());
                    items.add(s);
                }
                return Http.Response.ok(n);
            }));
        }
        long seq;
        try {
            seq = Long.parseLong(seg[1]);
        } catch (NumberFormatException e) {
            return Future.failedFuture(ApiException.notFound("no such history entry"));
        }
        if (seg.length == 2) {
            return only(m, HttpMethod.GET, true, () -> publisher.entry(seq).map(Http.Response::ok));
        }
        return only(m, HttpMethod.POST, seg.length == 3 && "rollback".equals(seg[2]),
                () -> require(a, Role.PUBLISHER, "rollback", "history/" + seq)
                        .compose(v -> publisher.rollback(seq, a)).map(p -> Http.Response.ok(published(p))));
    }
}
