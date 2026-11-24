package sched.adapters.http;

/**
 * Límites de tamaño de las solicitudes de consulta. Acotan el trabajo que una sola solicitud
 * puede pedirle al motor: la búsqueda crece de forma exponencial con los cursos.
 *
 * <p>Los valores por defecto son los de un pregrado: 12 cursos por solicitud, 42 ventanas a
 * evitar (6 días de 7 franjas), ids de hasta 64 caracteres y cuerpos de hasta 64 KiB.
 *
 * @param maxCourses máximo de cursos en {@code eligible_courses}, de elementos en
 *     {@code selection} y de grupos en {@code enrolled}
 * @param maxAvoid máximo de ventanas en {@code avoid}
 * @param maxIdLength longitud máxima de cualquier id (estudiante, curso o grupo)
 * @param maxBodyBytes tamaño máximo del cuerpo HTTP en bytes
 */
public record RequestLimits(int maxCourses, int maxAvoid, int maxIdLength, long maxBodyBytes) {

    public static final RequestLimits DEFAULTS = new RequestLimits(12, 42, 64, 64 * 1024);

    public RequestLimits {
        if (maxCourses < 1 || maxAvoid < 0 || maxIdLength < 1 || maxBodyBytes < 1) {
            throw new IllegalArgumentException("Límites de solicitud inválidos: cursos=" + maxCourses
                    + ", avoid=" + maxAvoid + ", id=" + maxIdLength + ", cuerpo=" + maxBodyBytes);
        }
    }
}
