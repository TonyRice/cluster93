package automately.core.host.dns;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsPtrRecord;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * DNS records as Node's {@code dns} reports them (D-122), each with its {@code type} and {@code ttl}; the isolate shapes
 * them per call (a bare address for {@code resolve4}, the full object for {@code resolveAny}). Names lose their final
 * dot, as Node's do.
 *
 * <p>Netty decodes PTR records, and decompresses the names in CNAME, NS and MX; every other record's content is a view
 * onto the whole message, so a compressed name inside it (SRV, SOA, NAPTR) is followed by its absolute offset.
 */
public final class DnsRecords {

    /** The record types {@code dns} can ask for, by Node's names. */
    public static final java.util.Map<String, Integer> TYPES = java.util.Map.ofEntries(
            java.util.Map.entry("A", 1), java.util.Map.entry("NS", 2), java.util.Map.entry("CNAME", 5), java.util.Map.entry("SOA", 6),
            java.util.Map.entry("PTR", 12), java.util.Map.entry("MX", 15), java.util.Map.entry("TXT", 16), java.util.Map.entry("AAAA", 28),
            java.util.Map.entry("SRV", 33), java.util.Map.entry("NAPTR", 35), java.util.Map.entry("TLSA", 52), java.util.Map.entry("CAA", 257),
            java.util.Map.entry("ANY", 255));

    private DnsRecords() {
    }

    /** The record as JSON, or null for a type {@code dns} doesn't report (in an ANY answer, say RRSIG). */
    static JsonObject decode(DnsRecord record) {
        DnsRecordType type = record.type();
        JsonObject out = new JsonObject().put("type", type.name()).put("ttl", record.timeToLive());
        if (record instanceof DnsPtrRecord ptr) {
            return out.put("value", strip(ptr.hostname()));
        }
        if (!(record instanceof DnsRawRecord raw)) {
            return null;
        }
        ByteBuf content = raw.content();
        int at = content.readerIndex();
        int end = content.writerIndex();
        try {
            switch (type.intValue()) {
                case 1, 28 -> {
                    byte[] bytes = new byte[end - at];
                    content.getBytes(at, bytes);
                    return out.put("address", text(InetAddress.getByAddress(bytes)));
                }
                case 2, 5 -> {
                    return out.put("value", name(content, at));
                }
                case 15 -> {
                    return out.put("exchange", name(content, at + 2)).put("priority", content.getUnsignedShort(at));
                }
                case 16 -> {
                    JsonArray entries = new JsonArray();
                    for (int i = at; i < end; ) {
                        int length = content.getUnsignedByte(i);
                        entries.add(content.toString(i + 1, length, StandardCharsets.ISO_8859_1));
                        i += 1 + length;
                    }
                    return out.put("entries", entries);
                }
                case 33 -> {
                    return out.put("name", name(content, at + 6)).put("port", content.getUnsignedShort(at + 4))
                            .put("priority", content.getUnsignedShort(at)).put("weight", content.getUnsignedShort(at + 2));
                }
                case 6 -> {
                    int[] next = new int[1];
                    String nsname = name(content, at, next);
                    String hostmaster = name(content, next[0], next);
                    int i = next[0];
                    return out.put("nsname", nsname).put("hostmaster", hostmaster).put("serial", content.getUnsignedInt(i))
                            .put("refresh", content.getUnsignedInt(i + 4)).put("retry", content.getUnsignedInt(i + 8))
                            .put("expire", content.getUnsignedInt(i + 12)).put("minttl", content.getUnsignedInt(i + 16));
                }
                case 35 -> {
                    int order = content.getUnsignedShort(at);
                    int preference = content.getUnsignedShort(at + 2);
                    int i = at + 4;
                    String flags = characterString(content, i);
                    i += 1 + content.getUnsignedByte(i);
                    String service = characterString(content, i);
                    i += 1 + content.getUnsignedByte(i);
                    String regexp = characterString(content, i);
                    i += 1 + content.getUnsignedByte(i);
                    return out.put("flags", flags).put("service", service).put("regexp", regexp).put("replacement", name(content, i))
                            .put("order", order).put("preference", preference);
                }
                case 257 -> {
                    int flags = content.getUnsignedByte(at);
                    int tagLength = content.getUnsignedByte(at + 1);
                    String tag = content.toString(at + 2, tagLength, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                    String value = content.toString(at + 2 + tagLength, end - at - 2 - tagLength, StandardCharsets.ISO_8859_1);
                    return out.put("critical", flags).put(tag, value);
                }
                case 52 -> {
                    byte[] data = new byte[end - at - 3];
                    content.getBytes(at + 3, data);
                    return out.put("certUsage", content.getUnsignedByte(at)).put("selector", content.getUnsignedByte(at + 1))
                            .put("match", content.getUnsignedByte(at + 2)).put("data", Base64.getEncoder().encodeToString(data));
                }
                default -> {
                    return null;
                }
            }
        } catch (UnknownHostException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** A character-string as Node gives it: one byte to a character (c-ares hands Node bytes, read as Latin-1). */
    private static String characterString(ByteBuf content, int at) {
        return content.toString(at + 1, content.getUnsignedByte(at), StandardCharsets.ISO_8859_1);
    }

    /** An address as Node writes it: IPv6 compressed as RFC 5952 has it ("::1", not "0:0:0:0:0:0:0:1"). */
    public static String text(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return address.getHostAddress();
        }
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }

    static String name(ByteBuf buf, int at) {
        return name(buf, at, new int[1]);
    }

    /**
     * The (possibly compressed) name at {@code at}, without its final dot; {@code next[0]} gets the offset just after it
     * as written there. Pointers are offsets into the whole message, which is the buffer's index 0.
     */
    static String name(ByteBuf buf, int at, int[] next) {
        StringBuilder out = new StringBuilder();
        int i = at;
        int end = -1;
        int jumps = 0;
        while (true) {
            int length = buf.getUnsignedByte(i);
            if ((length & 0xC0) == 0xC0) {
                if (end < 0) {
                    end = i + 2;
                }
                if (++jumps > 64) {
                    throw new IndexOutOfBoundsException("a compression loop");
                }
                i = ((length & 0x3F) << 8) | buf.getUnsignedByte(i + 1);
                continue;
            }
            if (length == 0) {
                if (end < 0) {
                    end = i + 1;
                }
                break;
            }
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(buf.toString(i + 1, length, StandardCharsets.US_ASCII));
            i += 1 + length;
        }
        next[0] = end;
        return out.toString();
    }

    static String strip(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
