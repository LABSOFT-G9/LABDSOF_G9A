package pt.isep.bmc.issue;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR-009 enforced: dependencies point inwards only, the domain and the
 * application are framework-free, and inbound and outbound adapters never
 * depend on each other. A failing rule fails {@code mvn verify}.
 */
@AnalyzeClasses(packages = "pt.isep.bmc.issue", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] FRAMEWORKS = {
        "org.springframework..", "jakarta..", "javax.persistence..", "org.hibernate..",
        "com.fasterxml..", "tools.jackson..", "org.slf4j..", "com.mysql..", "org.flywaydb.."
    };

    @ArchTest
    static final ArchRule onion = onionArchitecture()
            .domainModels("pt.isep.bmc.issue.domain..")
            .applicationServices("pt.isep.bmc.issue.application..")
            .adapter("in", "pt.isep.bmc.issue.adapters.in..")
            .adapter("out", "pt.isep.bmc.issue.adapters.out..")
            // Domain services (e.g. ReportIntake) live inside the domain packages, so
            // the separate "domain service" layer is empty by design.
            .withOptionalLayers(true)
            // The composition root is the one place allowed to see every layer.
            .ignoreDependency(resideInAPackage("pt.isep.bmc.issue.config.."), alwaysTrue());

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("pt.isep.bmc.issue.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .because("the domain depends on nothing but the Java standard library (ADR-009)");

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses()
            .that().resideInAPackage("pt.isep.bmc.issue.application..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .because("use cases are plain classes; transactions are a port (ADR-009)");

    @ArchTest
    static final ArchRule domainDoesNotSeeOuterLayers = noClasses()
            .that().resideInAPackage("pt.isep.bmc.issue.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("pt.isep.bmc.issue.application..", "pt.isep.bmc.issue.adapters..",
                    "pt.isep.bmc.issue.config..");

    @ArchTest
    static final ArchRule onlyTheCompositionRootWiresAdaptersAndUseCases = noClasses()
            .that().resideInAnyPackage("pt.isep.bmc.issue.domain..", "pt.isep.bmc.issue.application..",
                    "pt.isep.bmc.issue.adapters..")
            .should().dependOnClassesThat().resideInAPackage("pt.isep.bmc.issue.config..");
}
