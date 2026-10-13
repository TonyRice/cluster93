package cluster93.core.secrets;

import cluster93.core.host.KvStore;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.queue.QueueService;
import cluster93.core.storage.SecretBox;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How tenants' secrets are kept (D-068): sealed at rest, bound to their tenant and name, limited per tenant. */
class TenantSecretsTest {

    private static final String KEY = "tenant-secrets-test-key-0123456789abcdef";

    private Vertx vertx;
    private KvStore kv;
    private TenantSecrets secrets;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        kv = new SharedDataKvStore(vertx);
        secrets = new TenantSecrets(kv, new SecretBox(KEY));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void valuesAreSealedAtRestAndOpenOnlyUnderTheirOwnTenantAndName() throws Exception {
        await(secrets.put("acme", "db-url", "postgres://u:hunter2@db/app", "tok-1"));

        Object stored = await(kv.get(QueueService.SYSTEM_TENANT, TenantSecrets.namespace("acme"), "db-url"));
        assertThat(((JsonObject) stored).encode()).doesNotContain("hunter2");
        assertThat(await(secrets.value("acme", "db-url"))).isEqualTo("postgres://u:hunter2@db/app");

        // The sealed value copied under another name, or another tenant, doesn't open.
        await(kv.set(QueueService.SYSTEM_TENANT, TenantSecrets.namespace("acme"), "copied", stored, null));
        await(kv.set(QueueService.SYSTEM_TENANT, TenantSecrets.namespace("evil"), "db-url", stored, null));
        assertThatThrownBy(() -> await(secrets.value("acme", "copied"))).hasMessageContaining("can't be opened");
        assertThatThrownBy(() -> await(secrets.value("evil", "db-url"))).hasMessageContaining("can't be opened");

        // Nor under another secrets key.
        TenantSecrets otherKey = new TenantSecrets(kv, new SecretBox(KEY + "-changed"));
        assertThatThrownBy(() -> await(otherKey.value("acme", "db-url"))).hasMessageContaining("C93_SECRETS_KEY");
    }

    @Test
    void replacingKeepsCreationAndCountsVersions() throws Exception {
        TenantSecrets.Info first = await(secrets.put("acme", "token", "a", "tok-1"));
        TenantSecrets.Info second = await(secrets.put("acme", "token", "b", "tok-2"));

        assertThat(second.version()).isEqualTo(first.version() + 1);
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.updatedBy()).isEqualTo("tok-2");
        assertThat(await(secrets.list("acme"))).extracting(TenantSecrets.Info::name).containsExactly("token");
        assertThat(await(secrets.list("other"))).isEmpty();
    }

    @Test
    void aTenantHasALimitedNumberOfSecrets() throws Exception {
        for (int i = 0; i < TenantSecrets.MAX_PER_TENANT; i++) {
            await(secrets.put("busy", "s" + i, "v", null));
        }
        assertThatThrownBy(() -> await(secrets.put("busy", "one-more", "v", null))).hasMessageContaining("at most " + TenantSecrets.MAX_PER_TENANT);
        // Replacing one that exists still works.
        assertThat(await(secrets.put("busy", "s0", "new", null)).version()).isEqualTo(2);

        await(secrets.forget("busy"));
        assertThat(await(secrets.list("busy"))).isEmpty();
    }

    @Test
    void withoutASecretsKeyNothingIsStored() {
        TenantSecrets keyless = new TenantSecrets(kv, null);
        assertThat(keyless.available()).isFalse();
        assertThatThrownBy(() -> await(keyless.put("acme", "x", "y", null))).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(TenantSecrets.Unavailable.class);
    }

    @Test
    void namesAreChecked() {
        assertThat(TenantSecrets.validName("STRIPE_KEY")).isTrue();
        assertThat(TenantSecrets.validName("db.url-2")).isTrue();
        assertThat(TenantSecrets.validName(".hidden")).isFalse();
        assertThat(TenantSecrets.validName("a/b")).isFalse();
        assertThat(TenantSecrets.validName("x".repeat(129))).isFalse();
        assertThatThrownBy(() -> await(secrets.put("acme", "a b", "v", null))).hasMessageContaining("letters, digits");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
