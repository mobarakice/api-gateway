package apigw.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

final class Ports {

    private Ports() {
    }

    static int free() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
