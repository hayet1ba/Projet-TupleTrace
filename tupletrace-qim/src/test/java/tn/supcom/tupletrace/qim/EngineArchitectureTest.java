package tn.supcom.tupletrace.qim;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineArchitectureTest {
    private static final ArchRule ISOLATION = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "jakarta.ws.rs..",
                    "jakarta.security..", "javax.persistence..", "javax.ws.rs..", "java.sql..")
            .because("le moteur QIM doit pouvoir fonctionner en Java pur");

    @Test
    void productionCodeIsIndependentOfPersistenceAndRest() {
        var production = new ClassFileImporter().importPath(Path.of("target", "classes"));
        ISOLATION.check(production);
    }

    @Test
    void detectsAnAccidentalDatabaseDependency() {
        var witness = new ClassFileImporter().importClasses(DatabaseDependent.class);
        assertThrows(AssertionError.class, () -> ISOLATION.check(witness));
    }

    private record DatabaseDependent(Connection connection) {}
}
