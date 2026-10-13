package cluster93.core.host.dns;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetServer;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A DNS server for tests (D-122), over UDP and TCP on one port, answering from records given to it. It compresses names
 * as real servers do (owner names, and the names inside CNAME, NS, PTR, MX and SOA), so decoders meet pointers. Names
 * starting with these answer specially: {@code servfail.}, {@code refused.} and {@code notimp.} with that code,
 * {@code silent.} not at all, and {@code big.} truncated over UDP (so the client asks again over TCP). A name it has no
 * records for is NXDOMAIN; one with records of other types is NODATA.
 */
public final class TestDnsServer implements AutoCloseable {

    private record Answer(String name, int type, long ttl, RData data) {
    }

    /** Writes a record's data into the message, which it may point back into. */
    @FunctionalInterface
    private interface RData {
        void write(Message message);
    }

    private final Vertx vertx;
    /** Records can be added while it runs (a container's port, say); queries read them from the event loop. */
    private final Map<String, List<Answer>> records = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger queries = new AtomicInteger();
    private DatagramSocket udp;
    private NetServer tcp;
    private int port;

    public TestDnsServer(Vertx vertx) {
        this.vertx = vertx;
    }

    /** Listens on a free port of 127.0.0.1, over UDP and TCP. */
    public TestDnsServer start() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            udp = vertx.createDatagramSocket();
            udp.handler(packet -> {
                Buffer reply = answer(packet.data(), true);
                if (reply != null) {
                    udp.send(reply, packet.sender().port(), packet.sender().host());
                }
            });
            udp.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            port = udp.localAddress().port();
            try {
                tcp = vertx.createNetServer().connectHandler(socket -> {
                    Buffer[] pending = {Buffer.buffer()};
                    socket.handler(data -> {
                        pending[0] = pending[0].copy().appendBuffer(data);
                        while (pending[0].length() >= 2 && pending[0].length() >= 2 + pending[0].getUnsignedShort(0)) {
                            int length = pending[0].getUnsignedShort(0);
                            Buffer reply = answer(pending[0].getBuffer(2, 2 + length), false);
                            pending[0] = pending[0].getBuffer(2 + length, pending[0].length());
                            if (reply != null) {
                                socket.write(Buffer.buffer().appendUnsignedShort(reply.length()).appendBuffer(reply));
                            }
                        }
                    });
                }).listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                return this;
            } catch (Exception taken) {
                udp.close();
            }
        }
        throw new IllegalStateException("no free port for UDP and TCP together");
    }

    public int port() {
        return port;
    }

    /** As Node's setServers takes it. */
    public String address() {
        return "127.0.0.1:" + port;
    }

    public int queries() {
        return queries.get();
    }

    @Override
    public void close() {
        if (udp != null) {
            udp.close();
        }
        if (tcp != null) {
            tcp.close();
        }
    }

    // --- records ---

    private TestDnsServer add(String name, int type, long ttl, RData data) {
        records.computeIfAbsent(name.toLowerCase(Locale.ROOT), n -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new Answer(name, type, ttl, data));
        return this;
    }

    public TestDnsServer a(String name, String address) {
        return add(name, 1, 300, m -> m.bytes(ip(address)));
    }

    public TestDnsServer aaaa(String name, String address) {
        return add(name, 28, 300, m -> m.bytes(ip(address)));
    }

    public TestDnsServer cname(String name, String target) {
        return add(name, 5, 300, m -> m.name(target));
    }

    public TestDnsServer ns(String name, String target) {
        return add(name, 2, 300, m -> m.name(target));
    }

    public TestDnsServer ptr(String name, String target) {
        return add(name, 12, 300, m -> m.name(target));
    }

    public TestDnsServer mx(String name, int priority, String exchange) {
        return add(name, 15, 300, m -> {
            m.u16(priority);
            m.name(exchange);
        });
    }

    public TestDnsServer txt(String name, String... strings) {
        return add(name, 16, 300, m -> {
            for (String s : strings) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                m.u8(bytes.length);
                m.bytes(bytes);
            }
        });
    }

    /** SRV targets go uncompressed, as RFC 2782 has them. */
    public TestDnsServer srv(String name, int priority, int weight, int port, String target) {
        return add(name, 33, 300, m -> {
            m.u16(priority);
            m.u16(weight);
            m.u16(port);
            m.plainName(target);
        });
    }

    public TestDnsServer soa(String name, String mname, String rname, long serial, long refresh, long retry, long expire, long minimum) {
        return add(name, 6, 300, m -> {
            m.name(mname);
            m.name(rname);
            m.u32(serial);
            m.u32(refresh);
            m.u32(retry);
            m.u32(expire);
            m.u32(minimum);
        });
    }

    public TestDnsServer naptr(String name, int order, int preference, String flags, String service, String regexp, String replacement) {
        return add(name, 35, 300, m -> {
            m.u16(order);
            m.u16(preference);
            for (String s : new String[]{flags, service, regexp}) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                m.u8(bytes.length);
                m.bytes(bytes);
            }
            m.plainName(replacement);
        });
    }

    public TestDnsServer caa(String name, int flags, String tag, String value) {
        return add(name, 257, 300, m -> {
            m.u8(flags);
            m.u8(tag.length());
            m.bytes(tag.getBytes(StandardCharsets.US_ASCII));
            m.bytes(value.getBytes(StandardCharsets.UTF_8));
        });
    }

    public TestDnsServer tlsa(String name, int usage, int selector, int match, byte[] data) {
        return add(name, 52, 300, m -> {
            m.u8(usage);
            m.u8(selector);
            m.u8(match);
            m.bytes(data);
        });
    }

    /** The same record again with another TTL: the last one added for the name. */
    public TestDnsServer ttl(String name, long ttl) {
        List<Answer> list = records.get(name.toLowerCase(Locale.ROOT));
        Answer last = list.remove(list.size() - 1);
        list.add(new Answer(last.name, last.type, ttl, last.data));
        return this;
    }

    private static byte[] ip(String address) {
        try {
            return InetAddress.getByName(address).getAddress();
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(address);
        }
    }

    // --- answering ---

    private Buffer answer(Buffer query, boolean overUdp) {
        queries.incrementAndGet();
        if (query.length() < 12) {
            return null;
        }
        int id = query.getUnsignedShort(0);
        int flags = query.getUnsignedShort(2);
        StringBuilder name = new StringBuilder();
        int i = 12;
        while (query.getUnsignedByte(i) != 0) {
            int length = query.getUnsignedByte(i);
            if (!name.isEmpty()) {
                name.append('.');
            }
            name.append(query.getString(i + 1, i + 1 + length, "US-ASCII"));
            i += 1 + length;
        }
        int type = query.getUnsignedShort(i + 1);
        String lower = name.toString().toLowerCase(Locale.ROOT);
        if (lower.startsWith("silent.")) {
            return null;
        }
        int rcode = lower.startsWith("servfail.") ? 2 : lower.startsWith("refused.") ? 5 : lower.startsWith("notimp.") ? 4 : 0;
        List<Answer> found = records.get(lower);
        if (rcode == 0 && found == null) {
            rcode = 3;
        }
        List<Answer> answers = new ArrayList<>();
        if (rcode == 0) {
            for (Answer a : found) {
                if (type == 255 || a.type == type) {
                    answers.add(a);
                }
            }
        }
        boolean truncated = overUdp && lower.startsWith("big.");
        Message m = new Message();
        m.u16(id);
        m.u16(0x8000 | (flags & 0x0100) | 0x0080 | (truncated ? 0x0200 : 0) | rcode);
        m.u16(1);
        m.u16(truncated ? 0 : answers.size());
        m.u16(0);
        m.u16(0);
        m.name(name.toString());
        m.u16(type);
        m.u16(1);
        if (!truncated) {
            for (Answer a : answers) {
                m.name(a.name);
                m.u16(a.type);
                m.u16(1);
                m.u32(a.ttl);
                int lengthAt = m.size();
                m.u16(0);
                a.data.write(m);
                m.set16(lengthAt, m.size() - lengthAt - 2);
            }
        }
        return Buffer.buffer(m.toBytes());
    }

    /** A DNS message being written, compressing names against those already in it. */
    private static final class Message {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final Map<String, Integer> written = new HashMap<>();

        int size() {
            return out.size();
        }

        void u8(int value) {
            out.write(value & 0xff);
        }

        void u16(int value) {
            u8(value >> 8);
            u8(value);
        }

        void u32(long value) {
            u16((int) (value >> 16));
            u16((int) value);
        }

        void bytes(byte[] bytes) {
            out.writeBytes(bytes);
        }

        void set16(int at, int value) {
            byte[] all = out.toByteArray();
            all[at] = (byte) (value >> 8);
            all[at + 1] = (byte) value;
            out.reset();
            out.writeBytes(all);
        }

        /** A name, pointing back to an earlier copy of the longest suffix it shares with one. */
        void name(String name) {
            String rest = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
            while (!rest.isEmpty()) {
                Integer at = written.get(rest.toLowerCase(Locale.ROOT));
                if (at != null) {
                    u16(0xC000 | at);
                    return;
                }
                if (out.size() < 0x3FFF) {
                    written.put(rest.toLowerCase(Locale.ROOT), out.size());
                }
                int dot = rest.indexOf('.');
                String label = dot < 0 ? rest : rest.substring(0, dot);
                u8(label.length());
                bytes(label.getBytes(StandardCharsets.US_ASCII));
                rest = dot < 0 ? "" : rest.substring(dot + 1);
            }
            u8(0);
        }

        void plainName(String name) {
            String rest = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
            for (String label : rest.isEmpty() ? new String[0] : rest.split("\\.")) {
                u8(label.length());
                bytes(label.getBytes(StandardCharsets.US_ASCII));
            }
            u8(0);
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }
}
