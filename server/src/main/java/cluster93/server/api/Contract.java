package cluster93.server.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.mediatype.ContentAnalyser;
import io.vertx.openapi.mediatype.MediaTypeRegistration;
import io.vertx.openapi.mediatype.MediaTypeRegistry;
import io.vertx.openapi.validation.ValidationContext;

import java.util.List;

/** The API's OpenAPI contract (D-046), as the server and the tests load it. */
public final class Contract {

    public static final String PATH = "openapi.yaml";

    /** Server-sent events are a stream, never validated; the default registry doesn't know the type. */
    private static final MediaTypeRegistration EVENT_STREAM = new MediaTypeRegistration() {
        @Override
        public ContentAnalyser createContentAnalyser(String contentType, Buffer content, ValidationContext context) {
            // The stream's text, so a schema of {type: string} can check it.
            return new ContentAnalyser() {
                @Override
                public void checkSyntacticalCorrectness() {
                }

                @Override
                public Object transform() {
                    return content == null ? "" : content.toString();
                }
            };
        }

        @Override
        public boolean canHandle(String mediaType) {
            return mediaType != null && mediaType.startsWith("text/event-stream");
        }

        @Override
        public List<String> supportedTypes() {
            return List.of("text/event-stream");
        }
    };

    private Contract() {
    }

    public static Future<OpenAPIContract> load(Vertx vertx) {
        return OpenAPIContract.builder(vertx).setContractPath(PATH)
                .mediaTypeRegistry(MediaTypeRegistry.createDefault().register(EVENT_STREAM))
                .build();
    }
}
