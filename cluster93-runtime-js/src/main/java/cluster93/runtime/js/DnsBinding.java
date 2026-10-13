package cluster93.runtime.js;

import cluster93.core.runtime.api.DnsApi;

/** {@code dns} (D-122): lookups and queries through the egress gate's resolver; js/node/dns.js gives them Node's shapes. */
public final class DnsBinding {

    private final HostBridge bridge;
    private final DnsApi dns;

    DnsBinding(HostBridge bridge, DnsApi dns) {
        this.bridge = bridge;
        this.dns = dns;
    }

    /** {@code {hostname, family}}: resolves with {@code [{address, family}]}. */
    @HostFunction(name = "lookup")
    public JsHandle lookup(JsArg request) throws JsEngineException {
        var options = request.options();
        return bridge.promise(() -> dns.lookup(options));
    }

    /** {@code {name, type, servers?, timeout?, tries?}}: resolves with the records, each with its type and ttl. */
    @HostFunction(name = "resolve")
    public JsHandle resolve(JsArg request) throws JsEngineException {
        var options = request.options();
        return bridge.promise(() -> dns.resolve(options));
    }

    /** {@code {address, servers?, timeout?, tries?}}: resolves with the address's names. */
    @HostFunction(name = "reverse")
    public JsHandle reverse(JsArg request) throws JsEngineException {
        var options = request.options();
        return bridge.promise(() -> dns.reverse(options));
    }

    /** {@code {address, port}}: resolves with {@code {hostname, service}}. */
    @HostFunction(name = "lookupService")
    public JsHandle lookupService(JsArg request) throws JsEngineException {
        var options = request.options();
        return bridge.promise(() -> dns.lookupService(options));
    }

    /** The default servers, as JSON. */
    @HostFunction(name = "servers")
    public String servers() {
        return dns.servers().encode();
    }
}
