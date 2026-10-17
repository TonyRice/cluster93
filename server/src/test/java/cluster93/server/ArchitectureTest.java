package cluster93.server;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Guards the fixes for the 2017 codebase's structural problems so they cannot creep back in. */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                // Core's test helpers arrive as a test-jar, which DO_NOT_INCLUDE_TESTS doesn't recognise.
                .withImportOption(location -> !location.contains("-tests.jar"))
                .importPackages("cluster93");
    }

    static final ArchRule igniteStaysInsideItsModule = noClasses()
            .that().resideOutsideOfPackage("cluster93.cluster.ignite..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.ignite..", "io.vertx.spi.cluster..", "com.hazelcast..")
            .because("business code talks to store interfaces and Vert.x, never to the cluster manager or database directly (D-030)");

    static final ArchRule noMutableStaticState = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("cluster93..")
            .and().areStatic()
            // Compilers add their own static fields (an IDE's Eclipse compiler adds $SWITCH_TABLE$ caches).
            .and().doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.SYNTHETIC)
            .should().beFinal()
            .because("static mutable state made the old JobServer untestable and singleton-bound");

    static final ArchRule noHandRolledThreads = noClasses()
            .that().resideInAPackage("cluster93..")
            .and().doNotHaveFullyQualifiedName(Main.class.getName())
            .should().callConstructor(Thread.class, Runnable.class)
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
            .because("concurrency goes through Vert.x contexts, executeBlocking or virtual threads");

    static final ArchRule coreDoesNotKnowAboutEnginesOrTransport = noClasses()
            .that().resideInAPackage("cluster93.core..")
            .should().dependOnClassesThat().resideInAnyPackage("cluster93.runtime..", "cluster93.server..", "com.caoccao..", "io.vertx.ext.web..")
            .because("core defines the SPI; engines and the HTTP edge plug into it");

    /**
     * The classes that make the egress decision (D-173). They run in a worker as well as on a node, so they may reach
     * nothing of the node's: no registry, no stores, no metrics, no Vert.x runtime handle. Everything node-side reaches
     * them through `PolicySource` and `AccountingSink` instead.
     */
    private static final java.util.Set<String> EGRESS_DECISION = java.util.Set.of(
            "Decision", "EgressRule", "EgressPolicy", "AddressSets", "Cidr", "Posture", "EgressLimits", "EgressProtocol",
            "NodeAddresses", "ClusterEgress", "TenantEgress");

    static final ArchRule theEgressDecisionStaysPure = noClasses()
            .that(com.tngtech.archunit.base.DescribedPredicate.describe("make the egress decision (D-173)",
                    javaClass -> EGRESS_DECISION.contains(javaClass.getSimpleName())))
            .should().dependOnClassesThat().resideInAnyPackage("cluster93.core.node..", "cluster93.core.identity..",
                    "cluster93.core.metrics..", "cluster93.core.storage..", "cluster93.core.scheduler..",
                    "cluster93.core.files..", "cluster93.core.callback..", "cluster93.core.service..",
                    "cluster93.cluster..", "cluster93.server..", "cluster93.runner..", "cluster93.runtime..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("io.vertx.core.Vertx")
            .because("the decision runs in a worker too (D-173): it takes its inputs from a PolicySource and its "
                    + "outcomes through an AccountingSink, so it cannot reach node-only code");

    static Stream<ArchRule> rules() {
        return Stream.of(igniteStaysInsideItsModule, noMutableStaticState, noHandRolledThreads,
                coreDoesNotKnowAboutEnginesOrTransport, theEgressDecisionStaysPure);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void holds(ArchRule rule) {
        rule.check(classes);
    }
}
