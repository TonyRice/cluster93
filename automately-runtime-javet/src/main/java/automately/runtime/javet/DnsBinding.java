package automately.runtime.javet;

import automately.core.runtime.api.DnsApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValuePromise;

/** {@code dns} (D-122): lookups and queries through the egress gate's resolver; js/node/dns.js gives them Node's shapes. */
public final class DnsBinding {

    private final HostBridge bridge;
    private final DnsApi dns;

    DnsBinding(HostBridge bridge, DnsApi dns) {
        this.bridge = bridge;
        this.dns = dns;
    }

    /** {@code {hostname, family}}: resolves with {@code [{address, family}]}. */
    @V8Function(name = "lookup")
    public V8ValuePromise lookup(V8Value request) throws JavetException {
        var options = bridge.values().options(request);
        return bridge.promise(() -> dns.lookup(options));
    }

    /** {@code {name, type, servers?, timeout?, tries?}}: resolves with the records, each with its type and ttl. */
    @V8Function(name = "resolve")
    public V8ValuePromise resolve(V8Value request) throws JavetException {
        var options = bridge.values().options(request);
        return bridge.promise(() -> dns.resolve(options));
    }

    /** {@code {address, servers?, timeout?, tries?}}: resolves with the address's names. */
    @V8Function(name = "reverse")
    public V8ValuePromise reverse(V8Value request) throws JavetException {
        var options = bridge.values().options(request);
        return bridge.promise(() -> dns.reverse(options));
    }

    /** {@code {address, port}}: resolves with {@code {hostname, service}}. */
    @V8Function(name = "lookupService")
    public V8ValuePromise lookupService(V8Value request) throws JavetException {
        var options = bridge.values().options(request);
        return bridge.promise(() -> dns.lookupService(options));
    }

    /** The default servers, as JSON. */
    @V8Function(name = "servers")
    public String servers() {
        return dns.servers().encode();
    }
}
