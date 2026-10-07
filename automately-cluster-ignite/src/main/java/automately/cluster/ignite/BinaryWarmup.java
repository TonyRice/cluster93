package automately.cluster.ignite;

import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.NodeInfo;
import io.vertx.core.spi.cluster.RegistrationInfo;
import io.vertx.spi.cluster.ignite.impl.ClusterSerializationUtils;
import io.vertx.spi.cluster.ignite.impl.IgniteNodeInfo;
import io.vertx.spi.cluster.ignite.impl.IgniteRegistrationInfo;
import org.apache.ignite.Ignite;

/**
 * Registers, while the node starts, the binary schemas of the types Vert.x's cluster manager sends between nodes.
 * Ignite registers a schema cluster-wide the first time a node sends it; Vert.x first sends
 * {@code RemoveRegistrationProcessor} while a node is shutting down, and if the node leaves before the registration
 * completes, every node that received the message waits 30 s for the schema, holding a striped thread (D-042).
 * Registering here, before the node serves anything, means nothing is ever first sent on the way out.
 */
final class BinaryWarmup {

    private static final String SUBS = "io.vertx.spi.cluster.ignite.impl.SubsMapHelper$";

    private BinaryWarmup() {
    }

    static void vertxTypes(Ignite ignite) {
        IgniteRegistrationInfo registration = new IgniteRegistrationInfo("warmup", new RegistrationInfo("warmup", 0, false));
        register(ignite, registration);
        register(ignite, new IgniteNodeInfo(new NodeInfo("warmup", 1, new JsonObject())));
        register(ignite, new ClusterSerializationUtils.ClusterSerializableValue(JsonObject.class.getName(), new byte[0]));
        register(ignite, instance(SUBS + "AddRegistrationProcessor", IgniteRegistrationInfo.class, registration));
        register(ignite, instance(SUBS + "RemoveRegistrationProcessor", IgniteRegistrationInfo.class, registration));
        register(ignite, instance(SUBS + "RemoveNodeRegistrationsProcessor", String.class, "warmup"));
    }

    /** Marshalling registers the type's schema with the whole cluster, and waits until it's accepted. */
    static void register(Ignite ignite, Object value) {
        ignite.binary().toBinary(value);
    }

    /** The processors are package-private in vertx-ignite. */
    private static Object instance(String className, Class<?> argumentType, Object argument) {
        try {
            var constructor = Class.forName(className).getDeclaredConstructor(argumentType);
            constructor.setAccessible(true);
            return constructor.newInstance(argument);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("vertx-ignite changed: can't find " + className + " to register its schema (D-042)", e);
        }
    }
}
