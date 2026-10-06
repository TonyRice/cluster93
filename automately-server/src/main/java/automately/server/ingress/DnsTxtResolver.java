package automately.server.ingress;

import automately.core.host.TxtResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;

import java.util.List;

/** TXT lookups through Vert.x's DNS client (the system's resolvers). A name that doesn't exist resolves to no records. */
public final class DnsTxtResolver implements TxtResolver {

    private final DnsClient dns;

    public DnsTxtResolver(Vertx vertx) {
        this.dns = vertx.createDnsClient();
    }

    @Override
    public Future<List<String>> txt(String name) {
        return dns.resolveTXT(name).recover(err -> Future.succeededFuture(List.of()));
    }
}
