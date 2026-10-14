package cluster93.worker;

import cluster93.runtime.js.HostFunction;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A native image only lets the runtime see the methods and resources it was told about (D-126). The worker's shared
 * metadata registers every binding's public methods and all the runtime's JavaScript, so this checks that every class
 * with {@link HostFunction}s is listed: a binding added without it would be missing from the native workers only.
 */
class NativeImageMetadataTest {

    private static final String METADATA = "META-INF/native-image/cluster93/worker/reachability-metadata.json";

    @Test
    void everyBindingAndTheRuntimesJavaScriptAreRegistered() throws Exception {
        JsonObject metadata;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(METADATA)) {
            assertThat(in).as(METADATA).isNotNull();
            metadata = new JsonObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        List<String> registered = new ArrayList<>();
        for (Object entry : metadata.getJsonArray("reflection", new JsonArray())) {
            JsonObject type = (JsonObject) entry;
            if (type.getBoolean("allPublicMethods", false)) {
                registered.add(type.getString("type"));
            }
        }
        assertThat(bindings()).isNotEmpty().allSatisfy(binding -> assertThat(registered).as("registered for reflection").contains(binding));
        assertThat(metadata.getJsonArray("resources").stream().map(r -> ((JsonObject) r).getString("glob")))
                .contains("cluster93/runtime/js/**");
    }

    /** Every class in cluster93-runtime-js with a {@link HostFunction} method. */
    private static List<String> bindings() throws IOException, URISyntaxException, ClassNotFoundException {
        Path location = Path.of(HostFunction.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> found = new ArrayList<>();
        try (FileSystem jar = Files.isDirectory(location) ? null : FileSystems.newFileSystem(location)) {
            Path root = jar == null ? location : jar.getPath("/");
            Path classes = root.resolve("cluster93/runtime/js");
            try (Stream<Path> files = Files.list(classes)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = "cluster93.runtime.js." + file.getFileName().toString().replace(".class", "");
                    Class<?> type = Class.forName(name, false, NativeImageMetadataTest.class.getClassLoader());
                    if (Arrays.stream(type.getDeclaredMethods()).anyMatch(NativeImageMetadataTest::isHostFunction)) {
                        found.add(name);
                    }
                }
            }
        }
        return found;
    }

    private static boolean isHostFunction(Method method) {
        return method.isAnnotationPresent(HostFunction.class);
    }
}
