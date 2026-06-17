package dev.jediscore.engine;

/**
 * TLS settings for client connections. When {@link #enabled}, the network layer
 * fronts the listening socket with an SSL handler. If no certificate/key path is
 * given, a self-signed certificate is generated (development only).
 *
 * @param enabled  whether TLS is on
 * @param certPath the PEM certificate-chain path, or {@code null} for self-signed
 * @param keyPath  the PEM private-key path, or {@code null} for self-signed
 */
public record TlsConfig(boolean enabled, String certPath, String keyPath) {

    /** @return a disabled TLS configuration */
    public static TlsConfig disabled() {
        return new TlsConfig(false, null, null);
    }

    /** @return whether a real certificate/key pair was supplied (vs. self-signed) */
    public boolean hasCertificate() {
        return certPath != null && keyPath != null;
    }
}
