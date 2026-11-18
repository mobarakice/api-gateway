package apigw.core.plugin;

/** Runs in {@link Phase#REWRITE}, {@link Phase#ACCESS} or {@link Phase#BEFORE_PROXY}. Must not block. */
public interface RequestPlugin extends Plugin {

    Outcome onRequest(Exchange exchange);
}
