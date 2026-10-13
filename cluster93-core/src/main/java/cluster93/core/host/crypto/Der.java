package cluster93.core.host.crypto;

import cluster93.core.runtime.HostException;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Just enough DER to read and write the key formats Node accepts (PKCS#1, PKCS#8, SEC1, SPKI). */
final class Der {

    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int NULL = 0x05;
    static final int OID = 0x06;
    static final int SEQUENCE = 0x30;

    private Der() {
    }

    /** One element: its tag and its contents. */
    record Element(int tag, byte[] content) {

        List<Element> children() {
            return read(content);
        }

        BigInteger integer() {
            expect(INTEGER);
            return new BigInteger(content);
        }

        String oid() {
            expect(OID);
            StringBuilder out = new StringBuilder();
            long value = 0;
            for (int i = 0; i < content.length; i++) {
                value = (value << 7) | (content[i] & 0x7f);
                if ((content[i] & 0x80) == 0) {
                    if (out.isEmpty()) {
                        long first = Math.min(value / 40, 2);
                        out.append(first).append('.').append(value - first * 40);
                    } else {
                        out.append('.').append(value);
                    }
                    value = 0;
                }
            }
            return out.toString();
        }

        /** A BIT STRING's bits, without the unused-bits byte. */
        byte[] bits() {
            expect(BIT_STRING);
            return java.util.Arrays.copyOfRange(content, 1, content.length);
        }

        void expect(int expected) {
            if (tag != expected) {
                throw malformed();
            }
        }
    }

    static HostException malformed() {
        return HostException.invalid("The key isn't in a format that can be read (malformed DER)");
    }

    /** The elements in {@code der}, in order. */
    static List<Element> read(byte[] der) {
        List<Element> out = new ArrayList<>();
        int at = 0;
        while (at < der.length) {
            if (at + 2 > der.length) {
                throw malformed();
            }
            int tag = der[at++] & 0xff;
            int length = der[at++] & 0xff;
            if (length > 0x7f) {
                int bytes = length & 0x7f;
                if (bytes == 0 || bytes > 4 || at + bytes > der.length) {
                    throw malformed();
                }
                length = 0;
                for (int i = 0; i < bytes; i++) {
                    length = (length << 8) | (der[at++] & 0xff);
                }
            }
            if (length < 0 || at + length > der.length) {
                throw malformed();
            }
            out.add(new Element(tag, java.util.Arrays.copyOfRange(der, at, at + length)));
            at += length;
        }
        return out;
    }

    /** The single element {@code der} holds. */
    static Element one(byte[] der) {
        List<Element> all = read(der);
        if (all.size() != 1) {
            throw malformed();
        }
        return all.getFirst();
    }

    static byte[] element(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        if (content.length < 0x80) {
            out.write(content.length);
        } else {
            byte[] length = BigInteger.valueOf(content.length).toByteArray();
            int skip = length[0] == 0 ? 1 : 0;
            out.write(0x80 | (length.length - skip));
            out.write(length, skip, length.length - skip);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        return element(SEQUENCE, concat(parts));
    }

    static byte[] integer(BigInteger value) {
        return element(INTEGER, value.toByteArray());
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] octets(byte[] value) {
        return element(OCTET_STRING, value);
    }

    static byte[] bits(byte[] value) {
        return element(BIT_STRING, concat(new byte[] {0}, value));
    }

    static byte[] nul() {
        return new byte[] {NULL, 0};
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBase128(out, Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            writeBase128(out, Long.parseLong(parts[i]));
        }
        return element(OID, out.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int groups = 1;
        for (long v = value >>> 7; v != 0; v >>>= 7) {
            groups++;
        }
        for (int i = groups - 1; i >= 0; i--) {
            int b = (int) ((value >>> (7 * i)) & 0x7f);
            out.write(i == 0 ? b : b | 0x80);
        }
    }

    /** {@code [n]} explicitly tagged, constructed. */
    static byte[] explicit(int n, byte[] content) {
        return element(0xa0 | n, content);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
