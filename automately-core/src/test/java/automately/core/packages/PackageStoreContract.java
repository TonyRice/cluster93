package automately.core.packages;

import automately.core.files.DiskBlobStore;
import automately.core.identity.Records;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour {@link PackageStore} must have on every {@link Records} implementation (D-083); each one's test extends this. */
public abstract class PackageStoreContract {

    /** Set by the implementation's test before each test: {@link PackageStore#VERSIONS} and {@link PackageStore#BLOBS}. */
    protected Records versions;
    protected Records blobs;
    protected Vertx vertx;

    @TempDir
    Path dir;

    private PackageStore store() {
        return new PackageStore(versions, blobs, new DiskBlobStore(vertx, dir, 0));
    }

    @Test
    public void filesAreStoredOncePerOwnerAndCheckedAgainstTheirHash() throws Exception {
        PackageStore store = store();
        String hash = sha("export const x = 1;");

        assertThat(await(store.missing("acme", List.of(hash, hash)))).containsExactly(hash);
        JsonObject first = upload(store, "acme", "export const x = 1;");
        assertThat(first.getBoolean("created")).isTrue();
        assertThat(first.getLong("size")).isEqualTo(19);
        assertThat(upload(store, "acme", "export const x = 1;").getBoolean("created")).as("a second copy isn't stored").isFalse();
        assertThat(await(store.missing("acme", List.of(hash)))).isEmpty();
        assertThat(await(store.missing("globex", List.of(hash)))).as("never shared across tenants").containsExactly(hash);
        assertThat(await(store.usage("acme"))).isEqualTo(19);
        assertThat(await(store.usage("globex"))).isZero();

        PackageStore.Upload lying = await(store.upload("acme", sha("something else"), PackageStore.MAX_BYTES));
        await(lying.write(Buffer.buffer("not that")));
        assertThatThrownBy(() -> await(lying.finish())).hasMessageContaining("SHA-256");

        PackageStore.Upload big = await(store.upload("acme", sha("0123456789"), 5));
        assertThatThrownBy(() -> await(big.write(Buffer.buffer("0123456789")))).hasMessageContaining("quota");
        assertThat(await(store.usage("acme"))).isEqualTo(19);
    }

    @Test
    public void aVersionIsPublishedOnceWithEveryFileUploaded() throws Exception {
        PackageStore store = store();
        String index = "export const slug = s => s.toLowerCase();";
        JsonObject manifest = manifest("strings", "1.4.0", index);

        assertThatThrownBy(() -> await(store.publish("acme", manifest, "usr_1", "alice"))).hasMessageContaining("aren't uploaded");
        upload(store, "acme", index);
        JsonObject published = await(store.publish("acme", manifest, "usr_1", "alice"));
        assertThat(published.getString("version")).isEqualTo("1.4.0");
        assertThat(published.getJsonObject("exports").getString(".")).isEqualTo("index.js");
        assertThat(published.getString("publishedByName")).isEqualTo("alice");
        assertThat(published.getInteger("fileCount")).isEqualTo(1);

        assertThatThrownBy(() -> await(store.publish("acme", manifest, "usr_1", "alice")))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("already_exists"));
        assertThat(await(store.version("acme", "strings", "1.4.0")).getJsonObject("files").getString("index.js")).startsWith("sha256:");
        assertThat(await(store.version("globex", "strings", "1.4.0"))).as("another tenant's package is invisible").isNull();
        assertThat(await(store.info("globex", "strings"))).isNull();
    }

    @Test
    public void dependenciesMustBePublishedFirst() throws Exception {
        PackageStore store = store();
        String code = "export default 1;";
        upload(store, "acme", code);
        JsonObject app = manifest("app", "1.0.0", code).put("dependencies", new JsonObject().put("dates", "^2.1.0"));

        assertThatThrownBy(() -> await(store.publish("acme", app, "u", "u")))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("unmet_dependency"));
        await(store.publish("acme", manifest("dates", "2.0.0", code), "u", "u"));
        assertThatThrownBy(() -> await(store.publish("acme", app, "u", "u"))).as("2.0.0 isn't ^2.1.0").hasMessageContaining("dates");
        await(store.publish("acme", manifest("dates", "2.3.1", code), "u", "u"));
        assertThat(await(store.publish("acme", app, "u", "u")).getJsonObject("dependencies").getString("dates")).isEqualTo("^2.1.0");

        assertThatThrownBy(() -> await(store.publish("acme", manifest("bad", "1.0.0", code).put("dependencies",
                new JsonObject().put("dates", ">=2 <3")), "u", "u"))).hasMessageContaining("isn't one this understands");
    }

    @Test
    public void clusterPackagesAreTheOperatorsAndEveryoneCanSeeThem() throws Exception {
        PackageStore store = store();
        String code = "export const slugify = s => s;";
        upload(store, "acme", code);
        upload(store, PackageStore.CLUSTER, code);

        assertThatThrownBy(() -> await(store.publish("acme", manifest("@cluster/strings", "1.0.0", code), "u", "u")))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("forbidden"));
        assertThatThrownBy(() -> await(store.publish(PackageStore.CLUSTER, manifest("strings", "1.0.0", code), "op", "op")))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("forbidden"));
        await(store.publish(PackageStore.CLUSTER, manifest("@cluster/strings", "1.0.0", code), "op", "op"));
        await(store.publish("acme", manifest("strings", "3.0.0", code), "u", "u"));

        assertThat(await(store.list("acme"))).extracting(p -> p.getString("name") + "@" + p.getString("latest"))
                .containsExactly("@cluster/strings@1.0.0", "strings@3.0.0");
        assertThat(await(store.list("globex"))).extracting(p -> p.getString("name")).containsExactly("@cluster/strings");
        assertThat(await(store.list(null))).extracting(p -> p.getString("name")).containsExactly("@cluster/strings");
        assertThat(await(store.info("globex", "@cluster/strings")).getBoolean("cluster")).isTrue();
        assertThat(await(store.info(null, "strings"))).as("operators see cluster packages only").isNull();

        await(store.publish("acme", manifest("app", "1.0.0", code).put("dependencies", new JsonObject().put("@cluster/strings", "1.x")), "u", "u"));
        assertThatThrownBy(() -> await(store.publish(PackageStore.CLUSTER, manifest("@cluster/app", "1.0.0", code)
                .put("dependencies", new JsonObject().put("strings", "*")), "op", "op"))).hasMessageContaining("only depend on @cluster/");
    }

    @Test
    public void versionsListNewestFirstAndLatestSkipsPreReleases() throws Exception {
        PackageStore store = store();
        String code = "export default 2;";
        upload(store, "acme", code);
        for (String v : List.of("1.0.0", "1.10.0", "1.9.0", "2.0.0-beta.1")) {
            await(store.publish("acme", manifest("lib", v, code), "u", "u"));
        }
        JsonObject info = await(store.info("acme", "lib"));
        assertThat(info.getString("latest")).isEqualTo("1.10.0");
        assertThat(info.getJsonArray("versions").stream().map(v -> ((JsonObject) v).getString("version")))
                .containsExactly("2.0.0-beta.1", "1.10.0", "1.9.0", "1.0.0");
        assertThat(info.getJsonArray("versions").getJsonObject(0).containsKey("files")).isFalse();
    }

    @Test
    public void badManifestsAreRefusedWithTheReason() throws Exception {
        PackageStore store = store();
        String code = "x";
        String hash = "sha256:" + sha(code);
        List<JsonObject> bad = List.of(
                new JsonObject().put("name", "Strings").put("version", "1.0.0").put("files", new JsonObject().put("a.js", hash)),
                new JsonObject().put("name", "@other/strings").put("version", "1.0.0").put("files", new JsonObject().put("a.js", hash)),
                new JsonObject().put("name", "s").put("version", "1.0").put("files", new JsonObject().put("a.js", hash)),
                new JsonObject().put("name", "s").put("version", "1.0.0").put("files", new JsonObject()),
                new JsonObject().put("name", "s").put("version", "1.0.0").put("files", new JsonObject().put("../a.js", hash)),
                new JsonObject().put("name", "s").put("version", "1.0.0").put("files", new JsonObject().put("/a.js", hash)),
                new JsonObject().put("name", "s").put("version", "1.0.0").put("files", new JsonObject().put("a.js", "md5:abc")),
                new JsonObject().put("name", "s").put("version", "1.0.0").put("files", new JsonObject().put("a.js", hash))
                        .put("exports", new JsonObject().put(".", "missing.js")),
                new JsonObject().put("name", "s").put("version", "1.0.0").put("files", new JsonObject().put("a.js", hash))
                        .put("dependencies", new JsonObject().put("s", "1.x")));
        for (JsonObject manifest : bad) {
            assertThatThrownBy(() -> await(store.publish("acme", manifest, "u", "u"))).as(manifest.encode())
                    .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("invalid_argument"));
        }
    }

    @Test
    public void forgettingATenantRemovesItsPackagesButNotTheClusters() throws Exception {
        PackageStore store = store();
        String code = "export default 3;";
        upload(store, "acme", code);
        upload(store, PackageStore.CLUSTER, code);
        await(store.publish("acme", manifest("mine", "1.0.0", code), "u", "u"));
        await(store.publish(PackageStore.CLUSTER, manifest("@cluster/shared", "1.0.0", code), "op", "op"));

        await(store.forget("acme"));
        assertThat(await(store.usage("acme"))).isZero();
        assertThat(await(store.list("acme"))).extracting(p -> p.getString("name")).containsExactly("@cluster/shared");
        assertThat(await(store.missing(PackageStore.CLUSTER, List.of(sha(code))))).isEmpty();
        assertThatThrownBy(() -> await(store.forget(PackageStore.CLUSTER))).isInstanceOf(IllegalArgumentException.class);
    }

    // --- locks and loading (D-089) ---

    /** Publishes {@code name@version} with an index.js, and dependencies given as name, range pairs. */
    private static void publish(PackageStore store, String owner, String name, String version, String... deps) throws Exception {
        String index = "export const v = '" + name + "@" + version + "';";
        upload(store, owner, index);
        JsonObject dependencies = new JsonObject();
        for (int i = 0; i < deps.length; i += 2) {
            dependencies.put(deps[i], deps[i + 1]);
        }
        await(store.publish(owner, manifest(name, version, index).put("dependencies", dependencies), "u", "user"));
    }

    @Test
    public void aLockPicksTheNewestReleaseThatMeetsEveryRange() throws Exception {
        PackageStore store = store();
        publish(store, "acme", "strings", "1.0.0");
        publish(store, "acme", "strings", "1.4.0");
        publish(store, "acme", "strings", "2.0.0");
        publish(store, "acme", "strings", "2.1.0-beta.1");
        publish(store, "acme", "report", "1.0.0", "strings", "^1.0.0");
        publish(store, PackageStore.CLUSTER, "@cluster/dates", "3.0.0");

        JsonObject alone = await(store.lock("acme", java.util.Map.of("strings", java.util.Set.of("."))));
        assertThat(alone).isEqualTo(new JsonObject().put("strings", "2.0.0"));

        // The script wants strings' newest, report needs ^1: one version meets both, so it's the newest 1.x.
        JsonObject both = await(store.lock("acme", java.util.Map.of("strings", java.util.Set.of("."), "report", java.util.Set.of("."),
                "@cluster/dates", java.util.Set.of("."))));
        assertThat(both).isEqualTo(new JsonObject().put("@cluster/dates", "3.0.0").put("report", "1.0.0").put("strings", "1.4.0"));

        assertThatThrownBy(() -> await(store.lock("acme", java.util.Map.of("nope", java.util.Set.of(".")))))
                .hasMessageContaining("No package named \"nope\"");
        assertThatThrownBy(() -> await(store.lock("globex", java.util.Map.of("strings", java.util.Set.of(".")))))
                .as("a tenant's packages are its own").hasMessageContaining("No package named \"strings\"");
        assertThatThrownBy(() -> await(store.lock("acme", java.util.Map.of("strings", java.util.Set.of("./report")))))
                .hasMessageContaining("strings@2.0.0 doesn't export \"./report\"");
    }

    @Test
    public void conflictingRangesAreReportedWithWhoAskedForEach() throws Exception {
        PackageStore store = store();
        publish(store, "acme", "strings", "1.0.0");
        publish(store, "acme", "strings", "2.0.0");
        publish(store, "acme", "old", "1.0.0", "strings", "^1.0.0");
        publish(store, "acme", "new", "1.0.0", "strings", "^2.0.0");
        assertThatThrownBy(() -> await(store.lock("acme", java.util.Map.of("old", java.util.Set.of("."), "new", java.util.Set.of(".")))))
                .hasMessageContaining("No version of strings meets")
                .hasMessageContaining("(from old@1.0.0)").hasMessageContaining("(from new@1.0.0)");
    }

    @Test
    public void scriptsAreLockedFromWhatTheyImport() throws Exception {
        PackageStore store = store();
        publish(store, "acme", "strings", "1.0.0");
        assertThat(await(store.lockScript("acme", "import { v } from 'strings'; import fs from 'fs'; export default () => v;", true)))
                .isEqualTo(new JsonObject().put("strings", "1.0.0"));
        assertThat(await(store.lockScript("acme", "export default () => 1;", true))).isEmpty();
        assertThatThrownBy(() -> await(store.lockScript("acme", "import 'strings'; export default () => 1;", false)))
                .hasMessageContaining("\"packages\": false");
        assertThatThrownBy(() -> await(store.lockScript("acme", "export default async () => {\n  await import(name);\n};", true)))
                .hasMessageContaining("import() on line 2 doesn't name a package");

        JsonObject lock = new JsonObject().put("strings", "1.0.0");
        assertThat(PackageStore.inherit("import 'strings';", lock)).isEqualTo(lock);
        assertThat(PackageStore.inherit("export default () => 1;", lock)).isNull();
        assertThatThrownBy(() -> PackageStore.inherit("import 'other';", lock)).hasMessageContaining("other isn't among them");
    }

    @Test
    public void loadingFetchesEveryFileChecksItAndCachesIt() throws Exception {
        PackageStore store = store();
        publish(store, "acme", "strings", "1.0.0");
        publish(store, PackageStore.CLUSTER, "@cluster/dates", "3.0.0");
        PackageLoader loader = new PackageLoader(store, 1024 * 1024);
        JsonObject lock = new JsonObject().put("strings", "1.0.0").put("@cluster/dates", "3.0.0");

        automately.core.runtime.Packages loaded = await(loader.load("acme", lock));
        assertThat(loaded.byName()).containsOnlyKeys("strings", "@cluster/dates");
        assertThat(new String(loaded.byName().get("strings").files().get("index.js"), StandardCharsets.UTF_8)).isEqualTo("export const v = 'strings@1.0.0';");
        assertThat(loaded.byName().get("strings").exports()).containsEntry(".", "index.js");
        assertThat(loader.cachedBytes()).isPositive();

        assertThatThrownBy(() -> await(loader.load("acme", new JsonObject().put("strings", "9.9.9")))).hasMessageContaining("no longer exists");
    }

    @Test
    public void aFileThatDoesntMatchItsHashFailsTheLoad() throws Exception {
        PackageStore store = store();
        publish(store, "acme", "strings", "1.0.0");
        try (var files = java.nio.file.Files.walk(dir)) {
            for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                java.nio.file.Files.writeString(file, "export const v = 'tampered';");
            }
        }
        PackageLoader loader = new PackageLoader(store, 1024 * 1024);
        assertThatThrownBy(() -> await(loader.load("acme", new JsonObject().put("strings", "1.0.0"))))
                .hasMessageContaining("strings@1.0.0/index.js doesn't match its SHA-256");
        assertThat(loader.cachedBytes()).as("nothing unchecked is cached").isZero();
    }

    // --- helpers ---

    private static JsonObject manifest(String name, String version, String index) throws Exception {
        return new JsonObject().put("name", name).put("version", version).put("files", new JsonObject().put("index.js", "sha256:" + sha(index)));
    }

    private static JsonObject upload(PackageStore store, String owner, String content) throws Exception {
        PackageStore.Upload upload = await(store.upload(owner, sha(content), PackageStore.MAX_BYTES));
        await(upload.write(Buffer.buffer(content)));
        return await(upload.finish());
    }

    protected static String sha(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    protected static <T> T await(Future<T> future) throws Exception {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
