package net.ai.gate;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// The package rules of the design, enforced: packages by noun with a navigation budget, runtime internals hidden
/// from vendors and hosts, vendors independent of each other, and a JSON and event API that stand on their own.
class ArchitectureTest {
    private static final int PACKAGE_BUDGET = 20;
    private static JavaClasses sdk;

    @BeforeAll
    static void importClasses() {
        sdk = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("net.ai.gate");
        assertTrue(sdk.size() > 200, "the SDK classes were imported: " + sdk.size());
    }

    @Test
    void vendorsToolsAndDataUseOnlyThePublicApi() {
        noClasses().that().resideInAnyPackage("net.ai.gate.vendors..", "net.ai.gate.catalog.internal..", "net.ai.gate.providers..", "net.ai.gate.testing..")
                .should().dependOnClassesThat().resideInAPackage("net.ai.gate.internal..")
                .check(sdk);
    }

    @Test
    void runtimeInternalsKnowNoVendor() {
        noClasses().that().resideInAPackage("net.ai.gate.internal..")
                .should().dependOnClassesThat().resideInAnyPackage("net.ai.gate.vendors..", "net.ai.gate.providers..")
                .check(sdk);
    }

    @Test
    void vendorFamiliesAreIndependentOfEachOther() {
        slices().matching("net.ai.gate.vendors.(*)..").should().notDependOnEachOther().check(sdk);
    }

    @Test
    void jsonApiAndItsImplementationStandOnTheirOwn() {
        classes().that().resideInAnyPackage("net.ai.gate.json", "net.ai.gate.internal.json")
                .should().onlyDependOnClassesThat().resideInAnyPackage("net.ai.gate.json", "net.ai.gate.internal.json", "java..", "org.jspecify..")
                .check(sdk);
    }

    @Test
    void eventsMentionOnlyValueTypes() {
        classes().that().resideInAPackage("net.ai.gate.event")
                .should().onlyDependOnClassesThat().resideInAnyPackage("net.ai.gate.event", "net.ai.gate.model", "net.ai.gate.metadata",
                        "net.ai.gate.error", "net.ai.gate.json", "java..", "org.jspecify..")
                .check(sdk);
    }

    @Test
    void codecsAreFreeOfTransportAndCredentials() {
        noClasses().that().resideInAPackage("net.ai.gate.vendors..").and().implement("net.ai.gate.spi.protocol.WireApi")
                .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "net.ai.gate.auth..")
                .check(sdk);
    }

    @Test
    void publicSignaturesNeverMentionInternalTypes() {
        methods().that().arePublic().and().areDeclaredInClassesThat(resideInAPackage("net.ai.gate..").and(JavaClass.Predicates.resideOutsideOfPackage("..internal..")))
                .and().areDeclaredInClassesThat().arePublic()
                .should(new ArchCondition<>("mention no internal type in their signature") {
                    @Override public void check(JavaMethod method, ConditionEvents events) {
                        var internal = method.getRawReturnType().getPackageName().contains(".internal")
                                || method.getRawParameterTypes().stream().anyMatch(t -> t.getPackageName().contains(".internal"));
                        if (internal) events.add(SimpleConditionEvent.violated(method, method.getFullName() + " exposes an internal type"));
                    }
                })
                .check(sdk);
    }

    @Test
    void noLayerOrDumpingGroundPackages() {
        noClasses().should().resideInAnyPackage("..util..", "..impl..", "..manager..", "..helper..", "..common..", "..misc..").check(sdk);
    }

    @Test
    void everyPackageStaysWithinTheNavigationBudget() {
        var sizes = new TreeMap<String, Integer>();
        for (var c : sdk) if (c.isTopLevelClass()) sizes.merge(c.getPackageName(), 1, Integer::sum);
        var over = sizes.entrySet().stream().filter(e -> e.getValue() > PACKAGE_BUDGET).map(Map.Entry::toString).toList();
        assertTrue(over.isEmpty(), "packages above " + PACKAGE_BUDGET + " top-level types: " + over);
    }
}
