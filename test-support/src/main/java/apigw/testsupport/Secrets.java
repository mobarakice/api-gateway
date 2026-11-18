package apigw.testsupport;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Random throwaway credentials for test fixtures. */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /** 32 hex characters from a CSPRNG. */
    public static String randomPassword() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
