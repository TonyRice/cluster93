package cluster93.core.files.s3;

import io.vertx.core.Vertx;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * A real S3-compatible server for tests: Versity's S3 gateway, which checks SigV4 signatures and implements
 * multipart uploads and ranges. One container per test JVM; a bucket per caller.
 */
public final class S3TestServer {

    public static final String ACCESS_KEY = "cluster93-test";
    public static final String SECRET_KEY = "cluster93-test-secret-0123456789";
    private static final String IMAGE = "versity/versitygw:latest";

    private static GenericContainer<?> container;

    private S3TestServer() {
    }

    public static boolean available() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static synchronized URI endpoint() {
        if (container == null) {
            container = new GenericContainer<>(IMAGE)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                    .withCommand("-c", "mkdir -p /tmp/s3 && exec versitygw --access " + ACCESS_KEY + " --secret " + SECRET_KEY
                            + " --port :7070 posix /tmp/s3")
                    .withExposedPorts(7070)
                    .waitingFor(Wait.forListeningPort());
            container.start();
        }
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(7070));
    }

    /** Settings for a new, empty bucket on the shared server. */
    public static S3Settings bucket(String bucket) throws Exception {
        S3Settings settings = new S3Settings(endpoint(), "us-east-1", bucket, "cluster93/", true, ACCESS_KEY, SECRET_KEY, null);
        Vertx vertx = Vertx.vertx();
        try {
            // Vert.x 5 closes an HTTP client once it's garbage-collected, so this one is kept reachable until done.
            S3Client client = new S3Client(vertx, settings);
            client.createBucket().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            java.lang.ref.Reference.reachabilityFence(client);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        return settings;
    }
}
