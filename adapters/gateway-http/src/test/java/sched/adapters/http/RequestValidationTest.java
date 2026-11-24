package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sched.adapters.http.CombinationsController.InvalidRequestException;
import sched.adapters.http.CombinationsController.SlotDto;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.SuggestAlternatives;
import sched.solver.SearchResult;
import sched.solver.Solver;

/**
 * Validación de la entrada: campos ausentes o nulos, tamaños y forma de los ids son {@code 400}
 * y nunca llegan a la búsqueda. Llama a los controladores directamente.
 */
class RequestValidationTest {

    private static final RequestLimits LIMITS = new RequestLimits(3, 2, 8, 1024);

    private final AtomicInteger searches = new AtomicInteger();
    private final CombinationsController combinations;
    private final AlternativesController alternatives;

    RequestValidationTest() {
        Solver solver = problem -> {
            searches.incrementAndGet();
            return new SearchResult(List.of(), true);
        };
        CatalogReplica replica = new CatalogReplica();
        combinations = new CombinationsController(new GenerateCombinations(solver, replica, 10), LIMITS);
        alternatives = new AlternativesController(new SuggestAlternatives(solver, replica, 5), LIMITS);
    }

    private static CombinationsController.StudentContextDto combo(
            String student, List<String> courses, List<SlotDto> avoid) {
        return new CombinationsController.StudentContextDto(student, courses, avoid);
    }

    private static AlternativesController.AlternativesRequestDto alt(
            String student, List<String> courses, Map<String, String> selection, List<String> enrolled) {
        return new AlternativesController.AlternativesRequestDto(student, courses, null, selection, enrolled);
    }

    /** Debe ser un 400 invalid_request cuyo mensaje contiene {@code fragment}, sin haber buscado. */
    private void assertInvalid(String fragment, org.junit.jupiter.api.function.Executable call) {
        InvalidRequestException error = assertThrows(InvalidRequestException.class, call);
        assertTrue(error.getMessage().contains(fragment), error.getMessage());
        var answer = combinations.handleInvalidRequest(error);
        assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode());
        assertEquals("invalid_request", answer.getBody().error());
        assertEquals(0, searches.get());
    }

    private static List<String> courses(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add("C" + i);
        }
        return list;
    }

    // ---- Campos ausentes o nulos ----

    @Test
    void combinationsWithoutBodyOrRequiredFieldsIsInvalid() {
        assertInvalid("cuerpo", () -> combinations.combinations(null));
        assertInvalid("falta student_id", () -> combinations.combinations(combo(null, List.of("A"), null)));
        assertInvalid("student_id", () -> combinations.combinations(combo(" ", List.of("A"), null)));
        assertInvalid("falta eligible_courses", () -> combinations.combinations(combo("s1", null, null)));
    }

    @Test
    void nullOrBlankCourseElementsAreInvalid() {
        List<String> withNull = new ArrayList<>();
        withNull.add("A");
        withNull.add(null);
        assertInvalid("eligible_courses[1]", () -> combinations.combinations(combo("s1", withNull, null)));
        assertInvalid("eligible_courses[0]", () -> combinations.combinations(combo("s1", List.of(""), null)));
    }

    @Test
    void alternativesWithoutBodyOrRequiredFieldsIsInvalid() {
        assertInvalid("cuerpo", () -> alternatives.alternatives(null));
        assertInvalid("falta student_id", () -> alternatives.alternatives(alt(null, List.of("A"), null, null)));
        assertInvalid("falta eligible_courses", () -> alternatives.alternatives(alt("s1", null, null, null)));
    }

    @Test
    void nullEntriesInSelectionAndEnrolledAreInvalid() {
        Map<String, String> nullGroup = new HashMap<>();
        nullGroup.put("A", null);
        Map<String, String> nullCourse = new HashMap<>();
        nullCourse.put(null, "A-1");
        List<String> nullEnrolled = new ArrayList<>();
        nullEnrolled.add(null);
        assertInvalid("selection[A]", () -> alternatives.alternatives(alt("s1", List.of("A"), nullGroup, null)));
        assertInvalid("selection", () -> alternatives.alternatives(alt("s1", List.of("A"), nullCourse, null)));
        assertInvalid("enrolled[0]", () -> alternatives.alternatives(alt("s1", List.of("A"), null, nullEnrolled)));
    }

    // ---- Límites ----

    @Test
    void tooManyCoursesIsInvalid() {
        assertInvalid("máximo es 3", () -> combinations.combinations(combo("s1", courses(4), null)));
        assertInvalid("máximo es 3", () -> alternatives.alternatives(alt("s1", courses(4), null, null)));
    }

    @Test
    void tooManyAvoidWindowsIsInvalid() {
        SlotDto window = new SlotDto("monday", 360, 420);
        assertInvalid("avoid", () -> combinations.combinations(combo("s1", List.of("A"), List.of(window, window, window))));
        assertInvalid("avoid", () -> alternatives.alternatives(new AlternativesController.AlternativesRequestDto(
                "s1", List.of("A"), List.of(window, window, window), null, null)));
    }

    @Test
    void tooManySelectionOrEnrolledEntriesIsInvalid() {
        Map<String, String> selection = new HashMap<>();
        for (String c : courses(4)) {
            selection.put(c, c + "-1");
        }
        List<String> enrolled = courses(4);
        assertInvalid("selection", () -> alternatives.alternatives(alt("s1", courses(3), selection, null)));
        assertInvalid("enrolled", () -> alternatives.alternatives(alt("s1", courses(3), null, enrolled)));
    }

    @Test
    void limitsAreInclusive() throws Exception {
        // Exactamente en el límite se acepta (aquí el catálogo está vacío: solo se verifica que no sea un 400).
        SlotDto window = new SlotDto("monday", 360, 420);
        try {
            combinations.combinations(combo("12345678", courses(3), List.of(window, window)));
        } catch (InvalidRequestException e) {
            throw new AssertionError("no debía ser inválida: " + e.getMessage(), e);
        } catch (Exception e) {
            // El catálogo no está cargado (503): la validación pasó.
        }
    }

    @Test
    void idsAreLimitedInLengthAndCharacters() {
        assertInvalid("supera el máximo de 8", () -> combinations.combinations(combo("123456789", List.of("A"), null)));
        assertInvalid("supera el máximo de 8", () -> combinations.combinations(combo("s1", List.of("ABCDEFGHI"), null)));
        for (String bad : List.of("a b", "a/b", "a;b", "ñ", "a\n", "<x>")) {
            assertInvalid("solo se permiten", () -> combinations.combinations(combo(bad, List.of("A"), null)));
            assertInvalid("solo se permiten", () -> combinations.combinations(combo("s1", List.of(bad), null)));
            assertInvalid("solo se permiten",
                    () -> alternatives.alternatives(alt("s1", List.of("A"), Map.of("A", bad), null)));
            assertInvalid("solo se permiten",
                    () -> alternatives.alternatives(alt("s1", List.of("A"), null, List.of(bad))));
        }
    }

    @Test
    void idsMayUseLettersDigitsDashUnderscoreAndDot() {
        assertEquals("Ab-1_2.3", RequestValidation.id("x", "Ab-1_2.3", RequestLimits.DEFAULTS));
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RequestLimits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RequestLimits(1, 1, 1, 0));
    }

    @Test
    void defaultsAreThoseOfUndergraduate() {
        assertEquals(new RequestLimits(12, 42, 64, 65536), RequestLimits.DEFAULTS);
    }
}
