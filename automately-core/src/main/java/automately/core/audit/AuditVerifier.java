package automately.core.audit;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks every node's audit chain (D-072): each entry's hash matches its contents, each links to the one before, and
 * no numbers are missing in between. A chain that starts after 1 was pruned at its start, which is expected; anything
 * else is reported. Keeps only a few fields per entry in memory.
 */
public final class AuditVerifier {

    private record Link(long seq, String prev, String hash, boolean intact, String id) {
    }

    private AuditVerifier() {
    }

    /** @param secretsKey the cluster's {@code AUTOMATELY_SECRETS_KEY}, or null: it must be the one the entries were sealed with */
    public static Future<JsonObject> verify(AuditLog log, String secretsKey) {
        byte[] key = secretsKey == null ? null : AuditChain.derive(secretsKey);
        Map<String, List<Link>> chains = new TreeMap<>();
        long[] counts = new long[2];
        return scan(log, null, key, chains, counts).map(v -> {
            JsonArray problems = new JsonArray();
            JsonArray summary = new JsonArray();
            chains.forEach((chain, links) -> {
                links.sort(Comparator.comparingLong(Link::seq));
                Link first = links.getFirst();
                for (int i = 0; i < links.size(); i++) {
                    Link link = links.get(i);
                    if (!link.intact()) {
                        problems.add(problem(chain, link.seq(), link.id(), "its contents don't match its hash: it was changed"));
                    }
                    if (i > 0) {
                        Link before = links.get(i - 1);
                        if (link.seq() == before.seq()) {
                            problems.add(problem(chain, link.seq(), link.id(), "two entries have this number"));
                        } else if (link.seq() != before.seq() + 1) {
                            problems.add(problem(chain, link.seq(), link.id(), "entries " + (before.seq() + 1) + " to " + (link.seq() - 1)
                                    + " are missing"));
                        } else if (link.prev() == null || !link.prev().equals(before.hash())) {
                            problems.add(problem(chain, link.seq(), link.id(), "it doesn't link to the entry before it"));
                        }
                    } else if (link.seq() == 1 && link.prev() != null) {
                        problems.add(problem(chain, link.seq(), link.id(), "the first entry links to something before it"));
                    }
                }
                summary.add(new JsonObject().put("chain", chain).put("entries", links.size()).put("first", first.seq())
                        .put("last", links.getLast().seq()).put("prunedBefore", first.seq() > 1 ? first.seq() : null));
            });
            return new JsonObject().put("ok", problems.isEmpty()).put("keyed", key != null).put("checked", counts[0])
                    .put("unchained", counts[1]).put("chains", summary).put("problems", problems);
        });
    }

    private static Future<Void> scan(AuditLog log, String after, byte[] key, Map<String, List<Link>> chains, long[] counts) {
        return log.scan(after, AuditLog.MAX_PAGE).compose(page -> {
            for (AuditEntry entry : page) {
                counts[0]++;
                if (entry.chain() == null) {
                    counts[1]++;
                    continue;
                }
                boolean intact = entry.hash() != null && entry.hash().equals(AuditChain.digest(key, entry));
                chains.computeIfAbsent(entry.chain(), c -> new ArrayList<>()).add(new Link(entry.seq(), entry.prev(), entry.hash(), intact, entry.id()));
            }
            return page.size() < AuditLog.MAX_PAGE ? Future.succeededFuture() : scan(log, page.getLast().id(), key, chains, counts);
        });
    }

    private static JsonObject problem(String chain, long seq, String id, String what) {
        return new JsonObject().put("chain", chain).put("seq", seq).put("id", id).put("problem", what);
    }
}
