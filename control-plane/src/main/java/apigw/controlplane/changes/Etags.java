package apigw.controlplane.changes;

import apigw.controlplane.api.ApiException;

/** Strong ETags carrying an etcd {@code mod_revision}: {@code "42"}. */
public final class Etags {

    private Etags() {
    }

    public static String of(long revision) {
        return "\"" + revision + "\"";
    }

    /** Parses one strong ETag; weak or malformed tags are a 400. */
    public static long parse(String header) {
        String v = header.strip();
        if (v.length() < 3 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
            throw ApiException.badRequest("If-Match must be a single strong ETag such as \"42\"");
        }
        try {
            long r = Long.parseLong(v.substring(1, v.length() - 1));
            if (r < 1) {
                throw new NumberFormatException();
            }
            return r;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("If-Match must be a single strong ETag such as \"42\"");
        }
    }
}
