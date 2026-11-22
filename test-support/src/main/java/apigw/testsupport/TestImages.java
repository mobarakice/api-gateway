package apigw.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Container images pinned in {@code gradle/libs.versions.toml} (e.g. {@code prometheus.image}). */
public final class TestImages {

    private TestImages() {
    }

    public static String get(String key) {
        Properties p = new Properties();
        try (InputStream in = TestImages.class.getResourceAsStream("/test-support.properties")) {
            if (in == null) {
                throw new IllegalStateException("test-support.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String v = p.getProperty(key);
        if (v == null) {
            throw new IllegalArgumentException("no image " + key);
        }
        return v;
    }
}
