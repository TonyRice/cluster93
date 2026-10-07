package automately.core.files.s3;

import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class S3SignerTest {

    /** AWS's published SigV4 example for S3, "GET Object" with a Range header. */
    @Test
    void matchesAwsPublishedExample() {
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", S3Signer.EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        String authorization = S3Signer.authorization("GET", "/test.txt", "", headers, S3Signer.EMPTY_SHA256, "20130524T000000Z",
                "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

        assertThat(authorization).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void encodingFollowsTheSpec() {
        assertThat(S3Signer.encode("a b/c~d*e", true)).isEqualTo("a%20b/c~d%2Ae");
        assertThat(S3Signer.encode("a/b", false)).isEqualTo("a%2Fb");
        TreeMap<String, String> query = new TreeMap<>();
        query.put("uploads", "");
        query.put("prefix", "x y");
        assertThat(S3Signer.canonicalQuery(query)).isEqualTo("prefix=x%20y&uploads=");
    }
}
