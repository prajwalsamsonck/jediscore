package dev.jediscore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Verifies our CRC-64 matches the CRC-64/redis catalogue check value. */
class Crc64Test {

    @Test
    void matchesRedisCheckVector() {
        // CRC-64/redis: check("123456789") == 0xe9c6d914c4b8d9ca, init 0, no xorout.
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        long crc = Crc64.update(0, data, 0, data.length);
        assertThat(crc).isEqualTo(0xe9c6d914c4b8d9caL);
    }

    @Test
    void singleByteAndBulkAgree() {
        byte[] data = "hello world, this is a CRC test".getBytes(StandardCharsets.US_ASCII);
        long bulk = Crc64.update(0, data, 0, data.length);
        long oneByOne = 0;
        for (byte b : data) {
            oneByOne = Crc64.update(oneByOne, b);
        }
        assertThat(oneByOne).isEqualTo(bulk);
    }
}
