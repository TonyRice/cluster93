package automately.core.files.s3;

import java.net.URI;
import java.util.Map;

/**
 * An S3-compatible bucket (D-056): AWS S3, MinIO, Cloudflare R2, Backblaze B2, Wasabi and the like.
 *
 * @param endpoint  e.g. https://s3.eu-west-1.amazonaws.com, http://minio:9000, https://ACCOUNT.r2.cloudflarestorage.com
 * @param pathStyle bucket in the path (MinIO and most others) rather than in the host name (AWS's default)
 * @param prefix    prepended to every object key, e.g. "automately/"
 */
public record S3Settings(URI endpoint, String region, String bucket, String prefix, boolean pathStyle, String accessKeyId,
                         String secretAccessKey, String sessionToken) {

    public S3Settings {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("AUTOMATELY_S3_BUCKET is required with AUTOMATELY_FILES_BACKEND=s3");
        }
        if (accessKeyId == null || secretAccessKey == null) {
            throw new IllegalArgumentException("S3 credentials are required: AUTOMATELY_S3_ACCESS_KEY_ID and AUTOMATELY_S3_SECRET_ACCESS_KEY "
                    + "(or AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY)");
        }
    }

    /** From the environment: AUTOMATELY_S3_*, falling back to the standard AWS_* variables for credentials and region. */
    public static S3Settings fromEnv(Map<String, String> env) {
        String region = first(env.get("AUTOMATELY_S3_REGION"), env.get("AWS_REGION"), env.get("AWS_DEFAULT_REGION"), "us-east-1");
        String endpoint = env.get("AUTOMATELY_S3_ENDPOINT");
        boolean custom = endpoint != null && !endpoint.isBlank();
        URI uri = URI.create(custom ? stripSlash(endpoint.trim()) : "https://s3." + region + ".amazonaws.com");
        boolean pathStyle = Boolean.parseBoolean(env.getOrDefault("AUTOMATELY_S3_PATH_STYLE", String.valueOf(custom)));
        String prefix = env.getOrDefault("AUTOMATELY_S3_PREFIX", "automately/");
        return new S3Settings(uri, region, env.get("AUTOMATELY_S3_BUCKET"), prefix, pathStyle,
                first(env.get("AUTOMATELY_S3_ACCESS_KEY_ID"), env.get("AWS_ACCESS_KEY_ID")),
                first(env.get("AUTOMATELY_S3_SECRET_ACCESS_KEY"), env.get("AWS_SECRET_ACCESS_KEY")),
                first(env.get("AUTOMATELY_S3_SESSION_TOKEN"), env.get("AWS_SESSION_TOKEN")));
    }

    /** No secrets, for logs. */
    public String describe() {
        return "s3://" + bucket + "/" + prefix + " at " + endpoint + " (" + region + (pathStyle ? ", path-style" : "") + ")";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
