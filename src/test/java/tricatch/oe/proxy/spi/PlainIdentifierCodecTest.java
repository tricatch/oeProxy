package tricatch.oe.proxy.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlainIdentifierCodecTest {

    private final PlainIdentifierCodec codec = new PlainIdentifierCodec();

    @Test
    void encodeThenDecode_returnsOriginalOwnerId() {
        for (long ownerId : new long[]{0L, 1L, 42L, Long.MAX_VALUE}) {
            assertThat(codec.decode(codec.encode(ownerId))).isEqualTo(ownerId);
        }
    }

    @Test
    void decode_nullOrBlank_returnsNull() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("")).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void decode_nonNumericGarbage_returnsNull() {
        assertThat(codec.decode("not-a-number")).isNull();
        assertThat(codec.decode("12x")).isNull();
    }

    @Test
    void decode_trimsWhitespace() {
        assertThat(codec.decode(" 42 ")).isEqualTo(42L);
    }
}
