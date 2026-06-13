package dev.jediscore.persistence;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An {@link OutputStream} wrapper that maintains a running CRC-64 over everything
 * written through it, so the RDB body checksum can be computed in a single pass
 * without buffering the whole file.
 */
final class Crc64OutputStream extends OutputStream {

    private final OutputStream delegate;
    private long crc;

    Crc64OutputStream(OutputStream delegate) {
        this.delegate = delegate;
    }

    long crc() {
        return crc;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        crc = Crc64.update(crc, b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        crc = Crc64.update(crc, b, off, len);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }
}
