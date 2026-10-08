package automately.core.packages;

import automately.core.files.BlobStore;
import automately.core.files.BlobWriter;
import automately.core.identity.Records;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Published packages (D-083): immutable versions, each a manifest naming its files by SHA-256, with each unique file
 * stored once per owner in the cluster's own blob store. Package files are never in a tenant's file tree.
 * <ul>
 *   <li>An owner is a tenant, or {@link #CLUSTER} for operators' packages under {@code @cluster/}, which every tenant
 *       can use and no tenant can publish (D-085).</li>
 *   <li>Files are deduplicated per owner only: a store shared across tenants would let anyone test whether another
 *       tenant holds a given file.</li>
 *   <li>A version is checked whole when it's published: every file uploaded, within the limits, and every dependency
 *       range met by a published version.</li>
 * </ul>
 */
public final class PackageStore {

    /** One record per version, keyed {@code owner/name@version}. */
    public static final Records.Kind VERSIONS = new Records.Kind("PACKAGE_VERSIONS", List.of("tenant", "package"));
    /** One record per unique file per owner, keyed {@code owner/sha256}. */
    public static final Records.Kind BLOBS = new Records.Kind("PACKAGE_BLOBS", List.of("tenant"));

    /** The owner of {@code @cluster/} packages; not a possible tenant id. */
    public static final String CLUSTER = "@cluster";
    public static final String CLUSTER_PREFIX = "@cluster/";
    /** Per version, and so per file: a job's whole lock is loaded within the same limits (D-083 §5). */
    public static final int MAX_FILES = 1_000;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_DEPENDENCIES = 100;
    public static final int MAX_HASHES_PER_QUERY = MAX_FILES;
    /** Packages in one job's lock, to keep job records small (D-083 §4). */
    public static final int MAX_LOCKED = 500;
    /** Rounds of re-picking versions before resolution gives up on settling. */
    private static final int MAX_ROUNDS = 25;

    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FILE_REF = Pattern.compile("sha256:([0-9a-f]{64})");

    private final Records versions;
    private final Records blobs;
    private final BlobStore store;

    public PackageStore(Records versions, Records blobs, BlobStore store) {
        this.versions = versions;
        this.blobs = blobs;
        this.store = store;
    }

    // --- names and owners ---

    /** Null if {@code name} is a valid package name ({@code strings}, {@code @cluster/strings}); otherwise why not. */
    public static String invalidName(String name) {
        if (name == null || name.isEmpty()) {
            return "A package needs a name";
        }
        String local = name.startsWith(CLUSTER_PREFIX) ? name.substring(CLUSTER_PREFIX.length()) : name;
        if (local.startsWith("@")) {
            return "\"" + name + "\" isn't a package name: the only scope is @cluster/, for operators' packages";
        }
        if (!NAME.matcher(local).matches()) {
            return "\"" + name + "\" isn't a package name: lowercase letters, digits, '.', '_' and '-', up to 64, starting and ending with a letter or digit";
        }
        return null;
    }

    /** Who owns a package by this name, for a caller acting for {@code tenant} (null for operators). */
    public static String ownerOf(String name, String tenant) {
        return name.startsWith(CLUSTER_PREFIX) ? CLUSTER : tenant;
    }

    /** Whether {@code hash} is a lowercase hex SHA-256. */
    public static boolean isSha256(String hash) {
        return hash != null && SHA256.matcher(hash).matches();
    }

    private static String versionKey(String owner, String name, String version) {
        return owner + "/" + name + "@" + version;
    }

    private static String blobKey(String owner, String sha256) {
        return owner + "/" + sha256;
    }

    // --- files ---

    /** Which of {@code hashes} {@code owner} hasn't uploaded yet, in the order given. */
    public Future<List<String>> missing(String owner, Collection<String> hashes) {
        if (hashes.size() > MAX_HASHES_PER_QUERY) {
            return Future.failedFuture(HostException.limit("At most " + MAX_HASHES_PER_QUERY + " hashes at a time"));
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(hashes));
        for (String hash : unique) {
            if (!isSha256(hash)) {
                return Future.failedFuture(HostException.invalid("Not a lowercase hex SHA-256: " + hash));
            }
        }
        List<Future<JsonObject>> found = unique.stream().map(hash -> blobs.get(blobKey(owner, hash))).toList();
        return Future.all(found).map(all -> {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < unique.size(); i++) {
                if (found.get(i).result() == null) {
                    missing.add(unique.get(i));
                }
            }
            return missing;
        });
    }

    /** {@code {sha256, size}} if {@code owner} already has this file, else null. */
    public Future<JsonObject> file(String owner, String sha256) {
        return blobs.get(blobKey(owner, sha256))
                .map(found -> found == null ? null : new JsonObject().put("sha256", sha256).put("size", found.getLong("size")));
    }

    /**
     * Starts storing one file that should hash to {@code sha256}, refusing it past {@code allowance} bytes. The upload
     * finishes with {@code {sha256, size, created}}; a file whose bytes don't match the hash is refused and discarded.
     */
    public Future<Upload> upload(String owner, String sha256, long allowance) {
        if (!isSha256(sha256)) {
            return Future.failedFuture(HostException.invalid("Not a lowercase hex SHA-256: " + sha256));
        }
        String blobId = "pkg-" + UUID.randomUUID();
        return store.create(blobId, "application/octet-stream")
                .map(writer -> new Upload(owner, sha256, Math.min(allowance, MAX_BYTES), blobId, writer));
    }

    /** One file being uploaded. */
    public final class Upload {

        private final String owner;
        private final String sha256;
        private final long allowance;
        private final String blobId;
        private final BlobWriter writer;
        private final MessageDigest digest;
        private long size;
        private boolean done;

        private Upload(String owner, String sha256, long allowance, String blobId, BlobWriter writer) {
            this.owner = owner;
            this.sha256 = sha256;
            this.allowance = allowance;
            this.blobId = blobId;
            this.writer = writer;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public synchronized Future<Void> write(Buffer data) {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            size += data.length();
            if (size > allowance) {
                done = true;
                return writer.abort().transform(ar -> Future.failedFuture(tooBig(allowance)));
            }
            digest.update(data.getBytes());
            return writer.write(data);
        }

        public synchronized Future<JsonObject> finish() {
            if (done) {
                return Future.failedFuture("The upload is already finished");
            }
            done = true;
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(sha256)) {
                return writer.abort().transform(ar -> Future.failedFuture(HostException.invalid(
                        "The file's SHA-256 is " + actual + ", not " + sha256 + "; it was refused")));
            }
            JsonObject record = new JsonObject().put("tenant", owner).put("sha256", sha256).put("blobId", blobId)
                    .put("backend", store.name()).put("size", size).put("createdAt", System.currentTimeMillis());
            // Two uploads of the same file can race: the first to register wins, and the other's bytes are deleted.
            return writer.finish()
                    .compose(v -> blobs.putIfAbsent(blobKey(owner, sha256), record))
                    .compose(stored -> stored ? Future.succeededFuture(true) : store.delete(blobId).map(false))
                    .map(created -> new JsonObject().put("sha256", sha256).put("size", size).put("created", created));
        }

        public synchronized Future<Void> abort() {
            if (done) {
                return Future.succeededFuture();
            }
            done = true;
            return writer.abort();
        }
    }

    private static HostException tooBig(long allowance) {
        return allowance < MAX_BYTES
                ? HostException.limit("The file doesn't fit in the tenant's storage quota")
                : HostException.limit("A package file can be at most " + MAX_BYTES + " bytes");
    }

    // --- versions ---

    /**
     * Publishes a version from its manifest: {@code {name, version, files: {path: "sha256:…"}, exports?, dependencies?}}.
     * {@code owner} is who the caller publishes as (a tenant, or {@link #CLUSTER} for operators); the name must belong
     * to it. Answers with the stored version, files included.
     */
    public Future<JsonObject> publish(String owner, JsonObject manifest, String by, String byName) {
        Manifest m;
        try {
            m = Manifest.check(manifest);
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
        if (m.name.startsWith(CLUSTER_PREFIX) != CLUSTER.equals(owner)) {
            return Future.failedFuture(new HostException("Error", "forbidden", CLUSTER.equals(owner)
                    ? "Operators publish cluster-wide packages, named @cluster/<name>"
                    : "Only operators publish under @cluster/"));
        }
        String key = versionKey(owner, m.name, m.version.toString());
        return versions.get(key).compose(existing -> {
            if (existing != null) {
                return Future.failedFuture(new HostException("Error", "already_exists",
                        m.name + "@" + m.version + " is already published; a version never changes, so publish a new one"));
            }
            return sizes(owner, m.files.values()).compose(total -> dependenciesMet(owner, m).compose(v -> {
                JsonObject record = new JsonObject().put("tenant", owner).put("package", owner + "/" + m.name)
                        .put("name", m.name).put("version", m.version.toString())
                        .put("files", m.filesJson()).put("exports", m.exports).put("dependencies", m.dependencies)
                        .put("size", total).put("fileCount", m.files.size())
                        .put("publishedAt", System.currentTimeMillis()).put("publishedBy", by).put("publishedByName", byName)
                        .put("yanked", false);
                return versions.putIfAbsent(key, record).compose(stored -> stored ? Future.succeededFuture(record)
                        : Future.failedFuture(new HostException("Error", "already_exists", m.name + "@" + m.version + " is already published")));
            }));
        });
    }

    /** The total size of these files, failing if any isn't uploaded or they're over {@link #MAX_BYTES}. */
    private Future<Long> sizes(String owner, Collection<String> hashes) {
        List<String> unique = List.copyOf(new LinkedHashSet<>(hashes));
        List<Future<JsonObject>> found = unique.stream().map(hash -> blobs.get(blobKey(owner, hash))).toList();
        return Future.all(found).compose(all -> {
            List<String> missing = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < unique.size(); i++) {
                JsonObject blob = found.get(i).result();
                if (blob == null) {
                    missing.add(unique.get(i));
                } else {
                    total += blob.getLong("size", 0L);
                }
            }
            if (!missing.isEmpty()) {
                return Future.failedFuture(new HostException("Error", "missing_files", missing.size() + " file(s) aren't uploaded yet: "
                        + String.join(", ", missing.subList(0, Math.min(5, missing.size()))) + (missing.size() > 5 ? ", …" : "")));
            }
            if (total > MAX_BYTES) {
                return Future.failedFuture(HostException.limit("A package version can be at most " + MAX_BYTES + " bytes; this one is " + total));
            }
            return Future.succeededFuture(total);
        });
    }

    /** Every dependency must be met by a published version that isn't yanked; cluster packages depend on cluster packages only. */
    private Future<Void> dependenciesMet(String owner, Manifest m) {
        List<Future<Void>> checks = new ArrayList<>();
        for (Map.Entry<String, Semver.Range> dep : m.ranges.entrySet()) {
            String depOwner = ownerOf(dep.getKey(), owner);
            if (CLUSTER.equals(owner) && !dep.getKey().startsWith(CLUSTER_PREFIX)) {
                return Future.failedFuture(HostException.invalid("A cluster-wide package can only depend on @cluster/ packages, not " + dep.getKey()));
            }
            checks.add(versions.where("package", depOwner + "/" + dep.getKey()).compose(published -> {
                boolean met = published.stream().anyMatch(v -> !v.getBoolean("yanked", false)
                        && dep.getValue().matches(Semver.Version.parse(v.getString("version"))));
                return met ? Future.succeededFuture() : Future.failedFuture(new HostException("Error", "unmet_dependency",
                        "No published version of " + dep.getKey() + " matches \"" + m.dependencies.getString(dep.getKey())
                                + "\"; publish it first"));
            }));
        }
        return Future.all(checks).mapEmpty();
    }

    /**
     * What a tenant can use: its own packages and the cluster's, newest first by name. For operators (null tenant),
     * the cluster's only. Each is {@code {name, latest, versions, updatedAt, cluster}}.
     */
    public Future<List<JsonObject>> list(String tenant) {
        Future<List<JsonObject>> own = tenant == null ? Future.succeededFuture(List.of()) : versions.where("tenant", tenant);
        return own.compose(mine -> versions.where("tenant", CLUSTER).map(cluster -> {
            Map<String, List<JsonObject>> byName = new LinkedHashMap<>();
            java.util.stream.Stream.concat(mine.stream(), cluster.stream())
                    .forEach(v -> byName.computeIfAbsent(v.getString("name"), n -> new ArrayList<>()).add(v));
            return byName.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
                List<JsonObject> all = e.getValue();
                JsonObject latest = latest(all);
                return new JsonObject().put("name", e.getKey()).put("latest", latest == null ? null : latest.getString("version"))
                        .put("versions", all.size())
                        .put("updatedAt", all.stream().mapToLong(v -> v.getLong("publishedAt", 0L)).max().orElse(0L))
                        .put("cluster", e.getKey().startsWith(CLUSTER_PREFIX));
            }).toList();
        }));
    }

    /** The highest release that isn't yanked; failing that, the highest version of all. */
    private static JsonObject latest(List<JsonObject> all) {
        Comparator<JsonObject> byVersion = Comparator.comparing(v -> Semver.Version.parse(v.getString("version")));
        return all.stream().filter(v -> !v.getBoolean("yanked", false) && Semver.Version.parse(v.getString("version")).pre() == null)
                .max(byVersion).orElseGet(() -> all.stream().max(byVersion).orElse(null));
    }

    /** A package's versions, newest first, without their file lists; null if the caller can't see one by that name. */
    public Future<JsonObject> info(String tenant, String name) {
        String owner = ownerOf(name, tenant);
        if (owner == null) {
            return Future.succeededFuture();
        }
        return versions.where("package", owner + "/" + name).map(all -> {
            if (all.isEmpty()) {
                return null;
            }
            JsonObject latest = latest(all);
            JsonArray list = new JsonArray();
            all.stream().sorted(Comparator.comparing((JsonObject v) -> Semver.Version.parse(v.getString("version"))).reversed())
                    .forEach(v -> list.add(summary(v)));
            return new JsonObject().put("name", name).put("latest", latest.getString("version"))
                    .put("cluster", CLUSTER.equals(owner)).put("versions", list);
        });
    }

    /** One version, with its files; null if there's none the caller can see. */
    public Future<JsonObject> version(String tenant, String name, String version) {
        String owner = ownerOf(name, tenant);
        if (owner == null || Semver.Version.parse(version) == null) {
            return Future.succeededFuture();
        }
        return versions.get(versionKey(owner, name, version)).map(v -> v == null ? null : strip(v));
    }

    private static JsonObject summary(JsonObject v) {
        JsonObject s = strip(v);
        s.remove("files");
        s.remove("exports");
        return s;
    }

    /** A stored version as the API shows it: without the store's own keys. */
    private static JsonObject strip(JsonObject v) {
        JsonObject s = v.copy();
        s.remove("tenant");
        s.remove("package");
        return s;
    }

    // --- locks: what a job imports, as exact versions (D-089) ---

    /**
     * Resolves what a script imports to exact versions: each package it names, newest release first, and every
     * dependency of those, one version per package that meets every range on it. {@code wanted} maps each package
     * the script imports to the exports it asks for ({@code "."}, {@code "./report"}). Answers {@code {name: version}},
     * sorted by name; fails with {@code unresolved_import} saying what couldn't be met.
     */
    public Future<JsonObject> lock(String tenant, Map<String, Set<String>> wanted) {
        if (wanted.isEmpty()) {
            return Future.succeededFuture(new JsonObject());
        }
        Map<String, Semver.Range> roots = new LinkedHashMap<>();
        wanted.keySet().forEach(name -> roots.put(name, Semver.Range.parse("*")));
        return settle(tenant, roots, Map.of(), new java.util.HashMap<>(), 0).compose(chosen -> {
            for (Map.Entry<String, Set<String>> want : wanted.entrySet()) {
                JsonObject version = chosen.get(want.getKey());
                for (String subpath : want.getValue()) {
                    if (!version.getJsonObject("exports", new JsonObject()).containsKey(subpath)) {
                        return Future.failedFuture(unresolved(want.getKey() + "@" + version.getString("version") + " doesn't export "
                                + (subpath.equals(".") ? "a main module (add \"exports\" or an index.js)" : "\"" + subpath + "\"")));
                    }
                }
            }
            JsonObject lock = new JsonObject();
            new java.util.TreeMap<>(chosen).forEach((name, version) -> lock.put(name, version.getString("version")));
            return Future.succeededFuture(lock);
        });
    }

    /**
     * The lock for a script sent as source (D-087): its imports found by reading it, each resolved to the newest release.
     * Refuses an {@code import()} whose name isn't a literal, a name that can't be a package, and, when {@code allowed}
     * is false (a job's {@code "packages": false}), any package at all. Answers an empty lock if it imports none.
     */
    public Future<JsonObject> lockScript(String tenant, String script, boolean allowed) {
        Map<String, Set<String>> wanted;
        try {
            wanted = imported(script);
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
        if (!wanted.isEmpty() && !allowed) {
            return Future.failedFuture(unresolved("This job has \"packages\": false, so it can't import " + String.join(", ", wanted.keySet())));
        }
        return lock(tenant, wanted);
    }

    /**
     * The lock for a job started by another job's script ({@code jobs.run}): it can import exactly what its parent
     * could (D-083 §4), so it gets the parent's lock, or none if it imports nothing.
     */
    public static JsonObject inherit(String script, JsonObject parentLock) {
        Map<String, Set<String>> wanted = imported(script);
        if (wanted.isEmpty()) {
            return null;
        }
        List<String> outside = wanted.keySet().stream().filter(name -> parentLock == null || !parentLock.containsKey(name)).toList();
        if (!outside.isEmpty()) {
            throw unresolved("A job started by a script can import only the packages its parent could, and "
                    + String.join(", ", outside) + (outside.size() == 1 ? " isn't" : " aren't") + " among them");
        }
        return parentLock.copy();
    }

    /** The packages a script imports, and the exports of each; refusing what can't be found before it runs. */
    private static Map<String, Set<String>> imported(String script) {
        Imports.Scan scan = Imports.scan(script);
        if (!scan.computed().isEmpty()) {
            int at = scan.computed().getFirst();
            int line = 1 + (int) script.substring(0, at).chars().filter(c -> c == '\n').count();
            throw unresolved("import() on line " + line + " doesn't name a package with a string literal. Everything a job imports "
                    + "is fetched before it starts, so write import('name') or a static import");
        }
        try {
            return Imports.packages(scan);
        } catch (IllegalArgumentException e) {
            throw unresolved(e.getMessage());
        }
    }

    /** One round: every range from the roots and from the versions picked last round, then the newest version meeting each. */
    private Future<Map<String, JsonObject>> settle(String tenant, Map<String, Semver.Range> roots, Map<String, JsonObject> chosen,
                                                   Map<String, List<JsonObject>> published, int round) {
        if (round >= MAX_ROUNDS) {
            return Future.failedFuture(unresolved("The packages' versions didn't settle after " + MAX_ROUNDS + " rounds; their ranges conflict"));
        }
        // name -> each range on it, with who asked for it
        Map<String, List<Map.Entry<String, Semver.Range>>> ranges = new LinkedHashMap<>();
        roots.forEach((name, range) -> ranges.computeIfAbsent(name, n -> new ArrayList<>()).add(Map.entry("your script", range)));
        chosen.forEach((name, version) -> version.getJsonObject("dependencies", new JsonObject()).forEach(dep ->
                ranges.computeIfAbsent(dep.getKey(), n -> new ArrayList<>())
                        .add(Map.entry(name + "@" + version.getString("version"), Semver.Range.parse(String.valueOf(dep.getValue()))))));
        if (ranges.size() > MAX_LOCKED) {
            return Future.failedFuture(HostException.limit("A job can use at most " + MAX_LOCKED + " packages; this one needs " + ranges.size()));
        }
        List<String> unseen = ranges.keySet().stream().filter(name -> !published.containsKey(name)).toList();
        List<Future<List<JsonObject>>> fetched = unseen.stream()
                .map(name -> versions.where("package", ownerOf(name, tenant) + "/" + name)).toList();
        return Future.all(fetched).compose(all -> {
            for (int i = 0; i < unseen.size(); i++) {
                published.put(unseen.get(i), fetched.get(i).result());
            }
            Map<String, JsonObject> picked = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map.Entry<String, Semver.Range>>> entry : ranges.entrySet()) {
                String name = entry.getKey();
                List<JsonObject> candidates = published.get(name);
                if (candidates.isEmpty()) {
                    return Future.failedFuture(unresolved("No package named \"" + name + "\"" + (name.startsWith(CLUSTER_PREFIX) ? ""
                            : " (a tenant's packages are its own; cluster-wide ones are named @cluster/<name>)")
                            + asked(entry.getValue())));
                }
                JsonObject best = candidates.stream()
                        .filter(v -> !v.getBoolean("yanked", false))
                        .filter(v -> entry.getValue().stream().allMatch(r -> r.getValue().matches(Semver.Version.parse(v.getString("version")))))
                        .max(Comparator.comparing(v -> Semver.Version.parse(v.getString("version"))))
                        .orElse(null);
                if (best == null) {
                    return Future.failedFuture(unresolved("No version of " + name + " meets " + entry.getValue().stream()
                            .map(r -> describe(r.getValue()) + " (from " + r.getKey() + ")").collect(java.util.stream.Collectors.joining(" and "))));
                }
                picked.put(name, best);
            }
            boolean same = picked.size() == chosen.size() && picked.entrySet().stream().allMatch(e -> chosen.containsKey(e.getKey())
                    && chosen.get(e.getKey()).getString("version").equals(e.getValue().getString("version")));
            return same ? Future.succeededFuture(picked) : settle(tenant, roots, picked, published, round + 1);
        });
    }

    private static String asked(List<Map.Entry<String, Semver.Range>> ranges) {
        List<String> by = ranges.stream().map(Map.Entry::getKey).filter(who -> !who.equals("your script")).toList();
        return by.isEmpty() ? "" : "; needed by " + String.join(", ", by);
    }

    private static String describe(Semver.Range range) {
        if (range.exact() != null) {
            return range.exact().toString();
        }
        if (range.min() == null && range.max() == null) {
            return "any release";
        }
        return (range.min() == null ? "" : ">=" + range.min()) + (range.min() != null && range.max() != null ? " " : "")
                + (range.max() == null ? "" : "<" + range.max());
    }

    private static HostException unresolved(String message) {
        return new HostException("Error", "unresolved_import", message);
    }

    /** A version named by a lock, with its files, exports and dependencies; null if it's gone. */
    public Future<JsonObject> locked(String tenant, String name, String version) {
        return versions.get(versionKey(ownerOf(name, tenant), name, version));
    }

    /** The bytes of one of {@code owner}'s package files, as stored; null if there's no such file. The caller checks the hash. */
    public Future<Buffer> read(String owner, String sha256) {
        return blobs.get(blobKey(owner, sha256)).compose(blob -> {
            if (blob == null) {
                return Future.succeededFuture();
            }
            long size = blob.getLong("size", 0L);
            if (size == 0) {
                return Future.succeededFuture(Buffer.buffer());
            }
            io.vertx.core.Promise<Buffer> read = io.vertx.core.Promise.promise();
            Buffer bytes = Buffer.buffer((int) size);
            automately.core.runtime.BodyStream stream = store.open(blob.getString("blobId"), size, 0, size - 1);
            stream.handler(bytes::appendBuffer, () -> read.tryComplete(bytes), read::tryFail);
            stream.resume();
            return read.future();
        });
    }

    // --- accounting ---

    /** The bytes a tenant's package files take, each unique file once (they count against its quota, D-085). */
    public Future<Long> usage(String tenant) {
        return blobs.where("tenant", tenant).map(all -> all.stream().mapToLong(b -> b.getLong("size", 0L)).sum());
    }

    /** Deletes everything a tenant published: its versions, its files' records and their bytes. */
    public Future<Void> forget(String tenant) {
        if (tenant == null || CLUSTER.equals(tenant)) {
            return Future.failedFuture(new IllegalArgumentException("Not a tenant: " + tenant));
        }
        return versions.where("tenant", tenant)
                .compose(all -> Future.all(all.stream().map(v -> versions.remove(versionKey(tenant, v.getString("name"), v.getString("version")))).toList()))
                .compose(v -> blobs.where("tenant", tenant))
                .compose(all -> Future.all(all.stream().map(b -> store.delete(b.getString("blobId"))
                        .compose(d -> blobs.remove(blobKey(tenant, b.getString("sha256"))))).toList()))
                .mapEmpty();
    }

    // --- the manifest ---

    /** A manifest, checked: everything but whether its files are uploaded and its dependencies published. */
    private record Manifest(String name, Semver.Version version, Map<String, String> files, JsonObject exports, JsonObject dependencies,
                            Map<String, Semver.Range> ranges) {

        JsonObject filesJson() {
            JsonObject json = new JsonObject();
            files.forEach((path, hash) -> json.put(path, "sha256:" + hash));
            return json;
        }

        static Manifest check(JsonObject json) {
            if (json == null) {
                throw HostException.invalid("The body must be a package manifest: {name, version, files}");
            }
            String name = json.getValue("name") instanceof String s ? s : null;
            String invalid = invalidName(name);
            if (invalid != null) {
                throw HostException.invalid(invalid);
            }
            Semver.Version version = Semver.Version.parse(json.getValue("version") instanceof String s ? s : null);
            if (version == null) {
                throw HostException.invalid("\"version\" must be a version like 1.4.0 or 2.0.0-beta.1");
            }
            if (!(json.getValue("files") instanceof JsonObject filesJson) || filesJson.isEmpty()) {
                throw HostException.invalid("\"files\" must map each file's path to \"sha256:<hex>\"");
            }
            if (filesJson.size() > MAX_FILES) {
                throw HostException.limit("A package version can have at most " + MAX_FILES + " files; this one has " + filesJson.size());
            }
            Map<String, String> files = new LinkedHashMap<>();
            for (String path : new java.util.TreeSet<>(filesJson.fieldNames())) {
                String bad = invalidFilePath(path);
                if (bad != null) {
                    throw HostException.invalid(bad);
                }
                var ref = filesJson.getValue(path) instanceof String s ? FILE_REF.matcher(s) : null;
                if (ref == null || !ref.matches()) {
                    throw HostException.invalid("The file " + path + " must be given as \"sha256:<64 lowercase hex>\"");
                }
                files.put(path, ref.group(1));
            }
            JsonObject exports = new JsonObject();
            Object exportsValue = json.getValue("exports");
            if (exportsValue instanceof String main) {
                exportsValue = new JsonObject().put(".", main);
            }
            if (exportsValue instanceof JsonObject declared) {
                for (String subpath : declared.fieldNames()) {
                    if (!subpath.equals(".") && !(subpath.startsWith("./") && invalidFilePath(subpath.substring(2)) == null)) {
                        throw HostException.invalid("An export is \".\" or \"./<subpath>\", not " + subpath);
                    }
                    String target = declared.getValue(subpath) instanceof String t ? t.startsWith("./") ? t.substring(2) : t : null;
                    if (target == null || !files.containsKey(target)) {
                        throw HostException.invalid("The export " + subpath + " names " + declared.getValue(subpath) + ", which isn't one of the files");
                    }
                    exports.put(subpath, target);
                }
            } else if (exportsValue != null) {
                throw HostException.invalid("\"exports\" must be a file, or map \".\" and \"./<subpath>\" to files");
            } else if (files.containsKey("index.js")) {
                exports.put(".", "index.js");
            }
            JsonObject dependencies = new JsonObject();
            Map<String, Semver.Range> ranges = new LinkedHashMap<>();
            Object depsValue = json.getValue("dependencies");
            if (depsValue != null && !(depsValue instanceof JsonObject)) {
                throw HostException.invalid("\"dependencies\" must map package names to version ranges");
            }
            if (depsValue instanceof JsonObject deps) {
                if (deps.size() > MAX_DEPENDENCIES) {
                    throw HostException.limit("A package can have at most " + MAX_DEPENDENCIES + " dependencies");
                }
                for (String dep : new java.util.TreeSet<>(deps.fieldNames())) {
                    String badName = invalidName(dep);
                    if (badName != null) {
                        throw HostException.invalid("Dependency: " + badName);
                    }
                    if (dep.equals(name)) {
                        throw HostException.invalid("A package can't depend on itself");
                    }
                    String text = deps.getValue(dep) instanceof String s ? s : null;
                    Semver.Range range = Semver.Range.parse(text);
                    if (range == null) {
                        throw HostException.invalid("The range for " + dep + " (\"" + text + "\") isn't one this understands: "
                                + "an exact version, ^, ~, or x ranges like 1.x");
                    }
                    dependencies.put(dep, text);
                    ranges.put(dep, range);
                }
            }
            return new Manifest(name, version, files, exports, dependencies, ranges);
        }
    }

    private static final Set<String> DOT_SEGMENTS = Set.of(".", "..");

    /** Null if {@code path} can name a file in a package: relative, forward slashes, no empty or dot segments. */
    static String invalidFilePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) {
            return "A file path must be 1 to 512 characters";
        }
        if (path.startsWith("/") || path.contains("\\") || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "The file path " + path + " must be relative, with forward slashes and no control characters";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || DOT_SEGMENTS.contains(segment)) {
                return "The file path " + path + " can't have empty, '.' or '..' parts";
            }
        }
        return null;
    }
}
