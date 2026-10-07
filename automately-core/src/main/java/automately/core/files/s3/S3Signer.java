package automately.core.files.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SortedMap;
import java.util.stream.Collectors;

/** AWS Signature Version 4 for S3, per AWS's specification; checked against its published example. */
final class S3Signer {

    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private S3Signer() {
    }

    /**
     * The Authorization header for a request whose headers to sign are {@code headers} (lowercase names, in order;
     * they must include host, x-amz-content-sha256 and x-amz-date).
     */
    static String authorization(String method, String canonicalUri, String canonicalQuery, SortedMap<String, String> headers,
                                String payloadHash, String amzDate, String region, String accessKeyId, String secretAccessKey) {
        String canonicalHeaders = headers.entrySet().stream().map(h -> h.getKey() + ":" + h.getValue().trim() + "\n").collect(Collectors.joining());
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** RFC 3986 encoding as S3 wants it: everything but unreserved characters, and '/' kept in paths when asked. */
    static String encode(String value, boolean keepSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~'
                    || (keepSlash && c == '/')) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** Sorted by name, each name and value encoded; a parameter without a value is "name=". */
    static String canonicalQuery(SortedMap<String, String> query) {
        return query.entrySet().stream().map(e -> encode(e.getKey(), false) + "=" + encode(e.getValue(), false)).collect(Collectors.joining("&"));
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
