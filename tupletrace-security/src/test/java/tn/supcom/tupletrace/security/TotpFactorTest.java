package tn.supcom.tupletrace.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TotpFactorTest {
    @Test
    void rfc6238AppendixBSha1TruncatedToSixDigits() throws Exception {
        byte[] seed = new byte[20];
        for (int i = 0; i < seed.length; i++) seed[i] = (byte) ('0' + (i + 1) % 10);
        long[] seconds = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int i = 0; i < seconds.length; i++) assertEquals(expected[i], TotpFactor.code(seed, seconds[i] / 30));
    }
}
