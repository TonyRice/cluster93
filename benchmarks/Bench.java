import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cluster93 benchmarks (phase 5, milestone 3). One file, no dependencies: {@code java Bench.java <scenario> ...}.
 * Talks to a running cluster's API with a tenant token, like any client; prints one Markdown table row per scenario.
 *
 * <pre>
 *   java Bench.java coldstart   API TOKEN [jobs]            submit-to-finished latency of a trivial job, one at a time
 *   java Bench.java throughput  API TOKEN [clients] [secs]  finished jobs per second with N concurrent submitters
 *   java Bench.java ingress     API TOKEN INGRESS HOST [clients] [secs]  requests/s and latency through the ingress
 *   java Bench.java files       API TOKEN [MB]              upload and download speed of one large file
 * </pre>
 * API is like http://localhost:8081, INGRESS like http://localhost:8001, HOST the service's host (echo--tenant.localhost).
 */
public final class Bench {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java Bench.java coldstart|throughput|ingress|files API TOKEN ...");
            System.exit(2);
        }
        String api = args[1];
        String token = args[2];
        switch (args[0]) {
            case "coldstart" -> coldStart(api, token, intArg(args, 3, 50));
            case "throughput" -> throughput(api, token, intArg(args, 3, 16), intArg(args, 4, 20));
            case "ingress" -> ingress(args[3], args[4], intArg(args, 5, 32), intArg(args, 6, 15));
            case "files" -> files(api, token, intArg(args, 3, 200));
            default -> throw new IllegalArgumentException("Unknown scenario " + args[0]);
        }
    }

    // --- scenarios ---

    /** Submit, then wait on its event stream until it ends: the latency a user sees for a trivial script, including a fresh isolate. */
    static void coldStart(String api, String token, int jobs) throws Exception {
        List<Double> millis = new ArrayList<>();
        for (int i = 0; i < jobs + 3; i++) {
            long start = System.nanoTime();
            String id = submit(api, token, "export default () => 42");
            awaitEnd(api, token, id);
            if (i >= 3) {
                millis.add((System.nanoTime() - start) / 1e6);   // the first few warm the JIT and connections
            }
        }
        row("Cold start (submit → finished, trivial job)", jobs + " sequential jobs",
                "p50 " + ms(pct(millis, 50)) + ", p95 " + ms(pct(millis, 95)) + ", p99 " + ms(pct(millis, 99)));
    }

    /** N clients each submitting and waiting, for a fixed time: jobs finished per second across the cluster. */
    static void throughput(String api, String token, int clients, int seconds) throws Exception {
        AtomicLong done = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch finished = new CountDownLatch(clients);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < clients; c++) {
                pool.submit(() -> {
                    try {
                        while (!stop.get()) {
                            long start = System.nanoTime();
                            String status = awaitEnd(api, token, submit(api, token, "export default (input) => input.n * 2"));
                            latencies.add((System.nanoTime() - start) / 1e6);
                            (status.equals("SUCCEEDED") ? done : failed).incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                    return null;
                });
            }
            Thread.sleep(seconds * 1000L);
            stop.set(true);
            finished.await();
        }
        List<Double> sorted = new ArrayList<>(latencies);
        row("Job throughput", clients + (clients == 1 ? " client, " : " concurrent clients, ") + seconds + " s",
                String.format(Locale.ROOT, "%.1f jobs/s (p95 %s, %d failed)", done.get() / (double) seconds, ms(pct(sorted, 95)), failed.get()));
    }

    /** N clients hammering a service through the ingress: requests per second and latency. */
    static void ingress(String ingress, String host, int clients, int seconds) throws Exception {
        AtomicLong ok = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();
        CountDownLatch finished = new CountDownLatch(clients);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ingress + "/hello")).header("Host", host).GET().build();
        // Warm up: the service must be up and the connections open.
        for (int i = 0; i < 200; i++) {
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < clients; c++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        long start = System.nanoTime();
                        try {
                            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                            latencies.add((System.nanoTime() - start) / 1e6);
                            (status == 200 ? ok : errors).incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    finished.countDown();
                    return null;
                });
            }
            Thread.sleep(seconds * 1000L);
            stop.set(true);
            finished.await();
        }
        List<Double> sorted = new ArrayList<>(latencies);
        row("Ingress → service", clients + " concurrent clients, " + seconds + " s",
                String.format(Locale.ROOT, "%.0f req/s, p50 %s, p99 %s (%d errors)", ok.get() / (double) seconds, ms(pct(sorted, 50)),
                        ms(pct(sorted, 99)), errors.get()));
    }

    /** A large upload and download through the API (the cluster's default backend); the median of three rounds. */
    static void files(String api, String token, int mb) throws Exception {
        byte[] data = new byte[mb * 1024 * 1024];
        new java.util.Random(1).nextBytes(data);
        List<Double> ups = new ArrayList<>();
        List<Double> downs = new ArrayList<>();
        URI uri = URI.create(api + "/v1/files/bench/large.bin");
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            HttpResponse<String> up = HTTP.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofString());
            ups.add(mb / ((System.nanoTime() - start) / 1e9));
            if (up.statusCode() != 201) {
                throw new IllegalStateException("upload: " + up.statusCode() + " " + up.body());
            }
            start = System.nanoTime();
            HttpResponse<byte[]> down = HTTP.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
            downs.add(mb / ((System.nanoTime() - start) / 1e9));
            if (!java.util.Arrays.equals(down.body(), data)) {
                throw new IllegalStateException("download didn't match the upload");
            }
            HTTP.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        }
        row("Files (cluster backend, replicated)", mb + " MB, one stream each way, median of 3",
                String.format(Locale.ROOT, "upload %.0f MB/s, download %.0f MB/s", pct(ups, 50), pct(downs, 50)));
    }

    // --- plumbing ---

    static String submit(String api, String token, String script) throws Exception {
        String body = "{\"script\":" + quote(script) + ",\"input\":{\"n\":21}}";
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/v1/jobs")).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 202) {
                Matcher id = ID.matcher(response.body());
                if (id.find()) {
                    return id.group(1);
                }
            }
            if (response.statusCode() != 429 || attempt > 50) {
                throw new IllegalStateException("submit: " + response.statusCode() + " " + response.body());
            }
            Thread.sleep(100);
        }
    }

    /** Waits on the job's event stream for its "end" event, as the CLI does (no polling); returns its status. */
    static String awaitEnd(String api, String token, String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/v1/jobs/" + id + "/events")).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofMinutes(2)).GET().build();
        try (var lines = HTTP.send(request, HttpResponse.BodyHandlers.ofLines()).body()) {
            return lines.filter(line -> line.startsWith("data:") && line.contains("\"type\":\"end\""))
                    .map(line -> {
                        Matcher status = STATUS.matcher(line);
                        return status.find() ? status.group(1) : "UNKNOWN";
                    })
                    .findFirst().orElse("UNKNOWN");
        }
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static double pct(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1));
    }

    static String ms(double value) {
        return String.format(Locale.ROOT, "%.1f ms", value);
    }

    static int intArg(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    static void row(String what, String how, String result) {
        System.out.println("| " + what + " | " + how + " | " + result + " |");
    }
}
