package automately.server;

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
                .importPackages("automately");
    }

    static final ArchRule igniteStaysInsideItsModule = noClasses()
            .that().resideOutsideOfPackage("automately.cluster.ignite..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.ignite..", "io.vertx.spi.cluster..", "com.hazelcast..")
            .because("business code talks to store interfaces and Vert.x, never to the cluster manager or database directly (D-030)");

    static final ArchRule noMutableStaticState = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("automately..")
            .and().areStatic()
            // Compilers add their own static fields (an IDE's Eclipse compiler adds $SWITCH_TABLE$ caches).
            .and().doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.SYNTHETIC)
            .should().beFinal()
            .because("static mutable state made the old JobServer untestable and singleton-bound");

    static final ArchRule noHandRolledThreads = noClasses()
            .that().resideInAPackage("automately..")
            .and().doNotHaveFullyQualifiedName(Main.class.getName())
            .should().callConstructor(Thread.class, Runnable.class)
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
            .because("concurrency goes through Vert.x contexts, executeBlocking or virtual threads");

    static final ArchRule coreDoesNotKnowAboutEnginesOrTransport = noClasses()
            .that().resideInAPackage("automately.core..")
            .should().dependOnClassesThat().resideInAnyPackage("automately.runtime..", "automately.server..", "com.caoccao..", "io.vertx.ext.web..")
            .because("core defines the SPI; engines and the HTTP edge plug into it");

    static Stream<ArchRule> rules() {
        return Stream.of(igniteStaysInsideItsModule, noMutableStaticState, noHandRolledThreads, coreDoesNotKnowAboutEnginesOrTransport);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void holds(ArchRule rule) {
        rule.check(classes);
    }
}
