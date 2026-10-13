package cluster93.core.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.zip.CRC32;

/**
 * Token strings (D-045): {@code atly_} + 43 base62 characters (32 random bytes) + a 6-character base62 CRC32 of
 * the random part. The prefix lets secret scanners recognise a leaked token; the checksum lets them, and us, reject
 * a mistyped or made-up string without a lookup. Only {@link #hash} is ever stored.
 */
public final class TokenFormat {

    public static final String PREFIX = "atly_";
    static final int BODY_LENGTH = 43;
    static final int CHECKSUM_LENGTH = 6;
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private TokenFormat() {
    }

    public static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String body = base62(new BigInteger(1, bytes), BODY_LENGTH);
        return PREFIX + body + checksum(body);
    }

    /** Shape and checksum only; says nothing about whether the token exists. */
    public static boolean looksValid(String token) {
        if (token == null || token.length() != PREFIX.length() + BODY_LENGTH + CHECKSUM_LENGTH || !token.startsWith(PREFIX)) {
            return false;
        }
        String body = token.substring(PREFIX.length(), PREFIX.length() + BODY_LENGTH);
        for (char c : body.toCharArray()) {
            if (BASE62.indexOf(c) < 0) {
                return false;
            }
        }
        return checksum(body).equals(token.substring(PREFIX.length() + BODY_LENGTH));
    }

    /** Hex SHA-256: tokens are 256-bit random, so a slow hash would add nothing (D-045). */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares two hashes in constant time. */
    public static boolean sameHash(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String checksum(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.US_ASCII));
        return base62(BigInteger.valueOf(crc.getValue()), CHECKSUM_LENGTH);
    }

    private static String base62(BigInteger value, int length) {
        StringBuilder out = new StringBuilder();
        BigInteger base = BigInteger.valueOf(62);
        while (value.signum() > 0) {
            BigInteger[] division = value.divideAndRemainder(base);
            out.append(BASE62.charAt(division[1].intValue()));
            value = division[0];
        }
        while (out.length() < length) {
            out.append('0');
        }
        return out.reverse().toString();
    }
}
