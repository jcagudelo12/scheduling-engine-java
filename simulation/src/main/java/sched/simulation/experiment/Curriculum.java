package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sched.domain.Course;
import sched.domain.CourseId;

/**
 * Plan de estudios: qué asignaturas pide un estudiante de cada programa y semestre, y sus créditos.
 * Las asignaturas de un mismo programa y semestre son las mismas para todos, de modo que los
 * compañeros de semestre compiten por los mismos grupos.
 */
record Curriculum(Map<CourseId, Integer> credits, Map<String, Map<Integer, List<CourseId>>> pools, int semesters) {

    static final int SEMESTERS = 10;

    List<CourseId> pool(String program, int semester) {
        return pools.getOrDefault(program, Map.of()).getOrDefault(semester, List.of());
    }

    List<Course> courses() {
        List<Course> courses = new ArrayList<>();
        credits.forEach((id, c) -> courses.add(new Course(id, c)));
        return courses;
    }

    /**
     * Plan para una oferta que viene de un archivo y no trae programa ni semestre: las
     * asignaturas, en orden de id, se reparten por igual en los 10 semestres y valen para todos
     * los programas.
     */
    static Curriculum fromCourses(List<Course> courses, List<String> programs) {
        List<Course> sorted = courses.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
        Map<CourseId, Integer> credits = new LinkedHashMap<>();
        sorted.forEach(c -> credits.put(c.id(), c.credits()));
        int perSemester = Math.max(1, (int) Math.ceil(sorted.size() / (double) SEMESTERS));
        Map<Integer, List<CourseId>> bySemester = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            int semester = Math.min(SEMESTERS, i / perSemester + 1);
            bySemester.computeIfAbsent(semester, k -> new ArrayList<>()).add(sorted.get(i).id());
        }
        Map<String, Map<Integer, List<CourseId>>> pools = new LinkedHashMap<>();
        programs.forEach(p -> pools.put(p, bySemester));
        return new Curriculum(credits, pools, SEMESTERS);
    }
}
