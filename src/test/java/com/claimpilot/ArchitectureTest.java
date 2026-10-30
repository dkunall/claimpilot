package com.claimpilot;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The hexagonal layering, checked against the compiled classes rather than trusted to review. */
@AnalyzeClasses(packages = "com.claimpilot", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapter..", "org.springframework..", "org.apache.kafka..",
                    "jakarta..", "java.sql..", "javax.sql..", "software.amazon..", "com.fasterxml..",
                    "org.openpdf..", "com.lowagie..")
            .because("domain code is pure rules: no framework, no I/O, no serialisation");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("application services talk to ports; adapters implement them");

    @ArchTest
    static final ArchRule applicationHasNoInfrastructureTypes = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.apache.kafka..",
                    "org.springframework.jdbc..", "jakarta.servlet..", "software.amazon..", "com.lowagie..",
                    "org.springframework.batch..")
            .because("S3, Kafka, JDBC, PDF and Batch stay behind ports");
}
