package automately.core.service;

import automately.core.host.HostRouter;
import automately.core.host.SharedDataDomainStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** How a service's ports are reached (D-112, D-123): hosts per port, path prefixes, ports kept off the network. */
class ServicePortsTest {

    private static List<ServicePorts.Port> ports(String json) {
        return ServicePorts.of(new JsonArray(json));
    }

    @Test
    void portsAreValidated() {
        assertThat(ServicePorts.invalid(null)).isNull();
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001,\"path\":\"/admin\"},{\"port\":9090,\"expose\":false}]"))).isNull();
        assertThat(ServicePorts.invalid(new JsonObject())).contains("must be a list");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":0}]"))).contains("1 to 65535");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001},{\"port\":3001}]"))).contains("declared twice");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001,\"path\":\"admin\"}]"))).contains("prefix such as /admin");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001,\"path\":\"/admin/\"}]"))).contains("no trailing slash");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001,\"path\":\"/a/../b\"}]"))).contains(". or ..");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":1,\"path\":\"/a\"},{\"port\":2,\"path\":\"/A\"}]"))).contains("used by two ports");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001,\"domains\":[]}]"))).contains("not \"domains\"");
        assertThat(ServicePorts.invalid(new JsonArray("[{\"port\":3001,\"expose\":\"no\"}]"))).contains("true or false");
    }

    @Test
    void theLongestPrefixWinsAndIsTakenOff() {
        List<ServicePorts.Port> ports = ports("[{\"port\":3001,\"path\":\"/admin\"},{\"port\":3002,\"path\":\"/admin/metrics\"}]");
        assertThat(ServicePorts.match(ports, "/admin/users?x=1")).isEqualTo(new ServicePorts.Match(3001, "/admin", "/users?x=1"));
        assertThat(ServicePorts.match(ports, "/admin")).isEqualTo(new ServicePorts.Match(3001, "/admin", "/"));
        assertThat(ServicePorts.match(ports, "/admin?x=1")).isEqualTo(new ServicePorts.Match(3001, "/admin", "/?x=1"));
        assertThat(ServicePorts.match(ports, "/admin/metrics/cpu")).isEqualTo(new ServicePorts.Match(3002, "/admin/metrics", "/cpu"));
        // A prefix is a whole segment: /administer isn't under /admin.
        assertThat(ServicePorts.match(ports, "/administer")).isNull();
        assertThat(ServicePorts.match(ports, "/")).isNull();
        assertThat(ServicePorts.declared(ports, 3001).path()).isEqualTo("/admin");
        assertThat(ServicePorts.declared(ports, 4000)).isNull();
    }

    @Test
    void eachPortHasAHostOneLabelDeep() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            HostRouter router = new HostRouter("apps.example.com", new SharedDataDomainStore(vertx));
            assertThat(router.portHost("acme", "shop", 3001)).isEqualTo("shop--acme--3001.apps.example.com");
            assertThat(route(router, "shop--acme.apps.example.com")).isEqualTo(new HostRouter.Route("acme", "shop", null));
            assertThat(route(router, "shop--acme--3001.apps.example.com")).isEqualTo(new HostRouter.Route("acme", "shop", 3001));
            assertThat(route(router, "shop--acme--3001.apps.example.com:443")).isEqualTo(new HostRouter.Route("acme", "shop", 3001));
            for (String bad : List.of("shop--acme--0.apps.example.com", "shop--acme--65536.apps.example.com", "shop--acme--03001.apps.example.com",
                    "shop--acme--x.apps.example.com", "shop--acme--1--2.apps.example.com", "shop.apps.example.com", "--acme.apps.example.com")) {
                assertThat(route(router, bad)).as(bad).isNull();
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static HostRouter.Route route(HostRouter router, String host) throws Exception {
        return router.route(host).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
