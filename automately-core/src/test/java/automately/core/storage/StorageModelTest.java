package automately.core.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageModelTest {

    @Test
    void theLongestMatchingPrefixWinsThenTheDefault() {
        StorageRoutes routes = new StorageRoutes("acme", "main", Map.of("media/", "videos-bucket", "media/thumbs/", StorageRoutes.DEFAULT,
                "backups/", "disk1"));

        assertThat(routes.providerFor("media/intro.mp4")).isEqualTo("videos-bucket");
        assertThat(routes.providerFor("media/thumbs/a.png")).as("default means the cluster's own").isNull();
        assertThat(routes.providerFor("backups/db.gz")).isEqualTo("disk1");
        assertThat(routes.providerFor("notes.txt")).isEqualTo("main");
        assertThat(StorageRoutes.none("acme").providerFor("anything")).isNull();
        assertThat(StorageRoutes.fromJson(routes.toJson())).isEqualTo(routes);
    }

    @Test
    void secretsOpenOnlyWithTheSameKeyAndContext() {
        SecretBox box = new SecretBox("a-cluster-secret-key-that-is-long-enough");
        String sealed = box.seal("wJalrXUtnFEMI/K7MDENG", "acme/prv_1");

        assertThat(sealed).doesNotContain("wJalr");
        assertThat(box.open(sealed, "acme/prv_1")).isEqualTo("wJalrXUtnFEMI/K7MDENG");
        assertThat(box.seal("same", "acme/prv_1")).as("a fresh nonce each time").isNotEqualTo(box.seal("same", "acme/prv_1"));
        assertThatThrownBy(() -> box.open(sealed, "globex/prv_1")).as("moved to another tenant").hasMessageContaining("can't be opened");
        assertThatThrownBy(() -> new SecretBox("another-cluster-secret-key-long-enough").open(sealed, "acme/prv_1"))
                .hasMessageContaining("AUTOMATELY_SECRETS_KEY");
        assertThatThrownBy(() -> new SecretBox("short")).hasMessageContaining("at least 32");
    }
}
