package sched.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Protege la arquitectura hexagonal (ADR 0001): las dependencias van hacia adentro. Importa las
 * clases compiladas de todos los módulos ({@code sched.classes.dirs}, ver el pom) y comprueba las
 * reglas de dependencia entre paquetes.
 *
 * <pre>
 *   domain  <-  solver  <-  application  <-  adapters  <-  server
 *                                  proto (conversiones y contratos gRPC/protobuf)
 * </pre>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        String property = System.getProperty("sched.classes.dirs");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("falta sched.classes.dirs: corra las pruebas con Maven (./mvnw verify)");
        }
        List<Path> dirs = Arrays.stream(property.split(",")).map(Path::of).map(Path::normalize).toList();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException(dir + " no existe: compile todos los módulos antes "
                        + "(./mvnw verify desde la raíz, o -pl quality -am sin saltar simulation y benchmarks)");
            }
        }
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(dirs);
        if (classes.size() < 100) {
            throw new IllegalStateException("se importaron muy pocas clases (" + classes.size() + "): revise sched.classes.dirs");
        }
    }

    @Test
    void domainDependsOnNothingOutsideJava() {
        ArchRule rule = classes().that().resideInAPackage("sched.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("sched.domain..", "java..");
        rule.check(classes);
    }

    @Test
    void solverDependsOnlyOnDomain() {
        ArchRule rule = classes().that().resideInAPackage("sched.solver..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("sched.solver..", "sched.domain..", "java..");
        rule.check(classes);
    }

    @Test
    void applicationDoesNotKnowAdaptersFrameworksOrTransports() {
        ArchRule rule = noClasses().that().resideInAPackage("sched.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "sched.adapters..", "sched.server..", "sched.simulation..", "sched.proto..",
                        "org.springframework..",
                        "io.grpc..", "com.google.protobuf..",
                        "io.nats..",
                        "tools.jackson..", "com.fasterxml.jackson..",
                        "io.micrometer..");
        rule.check(classes);
    }

    @Test
    void applicationOnlyUsesDomainSolverAndLogging() {
        ArchRule rule = classes().that().resideInAPackage("sched.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "sched.application..", "sched.domain..", "sched.solver..", "java..", "org.slf4j..");
        rule.check(classes);
    }

    @Test
    void adaptersDoNotDependOnEachOther() {
        slices().assignedFrom(adapters()).should().notDependOnEachOther().check(classes);
    }

    @Test
    void nothingDependsOnServerOrSimulation() {
        ArchRule rule = noClasses().that().resideOutsideOfPackages("sched.server..", "sched.simulation..")
                .should().dependOnClassesThat().resideInAnyPackage("sched.server..", "sched.simulation..");
        rule.check(classes);
    }

    @Test
    void nothingDependsOnBenchmarks() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("sched.benchmarks..")
                .should().dependOnClassesThat().resideInAPackage("sched.benchmarks..");
        rule.check(classes);
    }

    @Test
    void topLevelPackagesHaveNoCycles() {
        slices().matching("sched.(*)..").should().beFreeOfCycles().check(classes);
    }

    /** Raíces de los adaptadores: cada una es un módulo y una "rebanada" de la regla. */
    private static final List<String> ADAPTERS =
            List.of("http", "grpc.catalog", "grpc.events", "nats", "inmemory");

    private static SliceAssignment adapters() {
        return new SliceAssignment() {
            @Override
            public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                String pkg = javaClass.getPackageName();
                for (String adapter : ADAPTERS) {
                    String root = "sched.adapters." + adapter;
                    if (pkg.equals(root) || pkg.startsWith(root + ".")) {
                        return SliceIdentifier.of(adapter);
                    }
                }
                return SliceIdentifier.ignore();
            }

            @Override
            public String getDescription() {
                return "adaptadores (sched.adapters.*)";
            }
        };
    }
}
