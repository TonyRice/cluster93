package cluster93.core.job;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobEnvTest {

    @Test
    void declarationsAreValuesOrSecretsByName() {
        assertThat(JobEnv.invalid(null)).isNull();
        assertThat(JobEnv.invalid(new JsonObject().put("API_URL", "https://x").put("_K2", new JsonObject().put("secret", "stripe-live")))).isNull();
        assertThat(JobEnv.invalid("API_URL=x")).contains("must map names");
        assertThat(JobEnv.invalid(new JsonObject().put("2BAD", "x"))).contains("isn't an environment variable name");
        assertThat(JobEnv.invalid(new JsonObject().put("A-B", "x"))).contains("isn't an environment variable name");
        assertThat(JobEnv.invalid(new JsonObject().put("N", 3))).contains("must be a string");
        assertThat(JobEnv.invalid(new JsonObject().put("K", new JsonObject().put("secret", "x").put("extra", 1)))).contains("must be a string");
        assertThat(JobEnv.invalid(new JsonObject().put("K", new JsonObject().put("secret", "no spaces")))).contains("isn't a secret's name");
        assertThat(JobEnv.invalid(new JsonObject().put("BIG", "x".repeat(JobEnv.MAX_VALUE_CHARS + 1)))).contains("longer than");
        JsonObject many = new JsonObject();
        for (int i = 0; i <= JobEnv.MAX_ENTRIES; i++) {
            many.put("V" + i, "x");
        }
        assertThat(JobEnv.invalid(many)).contains("at most");
    }

    @Test
    void onlySecretReferencesNeedSecretsUse() {
        assertThat(JobEnv.usesSecrets(new JsonObject().put("A", "x"))).isFalse();
        assertThat(JobEnv.usesSecrets(new JsonObject().put("A", "x").put("K", new JsonObject().put("secret", "k")))).isTrue();
        assertThat(JobEnv.usesSecrets(null)).isFalse();
    }

    @Test
    void secretValuesAreMaskedInLogs() {
        java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
        secrets.put("sk_live_abcdef123", "stripe");
        secrets.put("hunter2!", "db-password");
        JobEnv.Resolved env = new JobEnv.Resolved(Map.of(), secrets);
        assertThat(env.mask("using key sk_live_abcdef123 and hunter2! now"))
                .isEqualTo("using key [secret:stripe] and [secret:db-password] now");
        assertThat(JobEnv.Resolved.NONE.mask("nothing to hide")).isEqualTo("nothing to hide");
    }

    @Test
    void withoutADeclarationTheScriptSeesOnlyNodeEnv() throws Exception {
        JobEnv.Resolved env = JobEnv.resolve(null, "acme", false, null).toCompletionStage().toCompletableFuture().get();
        assertThat(env.values()).containsExactly(Map.entry("NODE_ENV", "production"));
        JobEnv.Resolved plain = JobEnv.resolve(new JsonObject().put("API_URL", "https://x"), "acme", false, null)
                .toCompletionStage().toCompletableFuture().get();
        assertThat(plain.values()).containsEntry("API_URL", "https://x").containsEntry("NODE_ENV", "production").hasSize(2);
    }

    @Test
    void namingASecretWithoutTheRightFailsTheRun() {
        var refused = JobEnv.resolve(new JsonObject().put("K", new JsonObject().put("secret", "k")), "acme", false, null);
        assertThat(refused.failed()).isTrue();
        assertThat(refused.cause()).hasMessageContaining("secrets:use");
    }
}
