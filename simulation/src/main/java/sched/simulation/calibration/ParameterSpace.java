package sched.simulation.calibration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sched.simulation.experiment.ExperimentConfig;

/**
 * Espacio de búsqueda de la calibración del flujo A y de lo que comparte con B. Las cotas son
 * explícitas y se justifican en cada parámetro; que el óptimo quede pegado a una cota es un
 * hallazgo (el modelo necesita algo fuera de lo plausible) y el reporte lo marca.
 *
 * <p>Los tiempos propios de B (revisar combinaciones y alternativas) NO se calibran: no hay datos
 * de B; se varían en el análisis de sensibilidad.
 */
public final class ParameterSpace {

    private static final List<String> SHARED_CV = List.of(
            "times.browseCourse.cv", "times.chooseSection.cv", "times.submitSection.cv",
            "times.readRejection.cv", "times.withdraw.cv", "times.confirm.cv");

    private final List<ParamSpec> specs;

    public ParameterSpace(List<ParamSpec> specs) {
        this.specs = List.copyOf(specs);
    }

    public static ParameterSpace defaults() {
        return new ParameterSpace(List.of(
                new ParamSpec("tiempo de navegar una asignatura (s)", List.of("times.browseCourse.meanSeconds"), 30, 360,
                        "de medio minuto a 6 min por página de oferta; la consulta reporta 15-60 min para 4-5 asignaturas"),
                new ParamSpec("tiempo de elegir un grupo, solo A (s)", List.of("times.chooseSection.meanSeconds"), 15, 240,
                        "decidir entre 2-6 grupos mirando horarios: de 15 s a 4 min"),
                new ParamSpec("tiempo de enviar un grupo (s)", List.of("times.submitSection.meanSeconds"), 5, 90,
                        "un clic y la espera de la plataforma: de 5 s a 1,5 min"),
                new ParamSpec("tiempo de leer un rechazo (s)", List.of("times.readRejection.meanSeconds"), 10, 240,
                        "leer el mensaje y entender el cruce: de 10 s a 4 min"),
                new ParamSpec("tiempo de retirar un grupo (s)", List.of("times.withdraw.meanSeconds"), 10, 180,
                        "dos o tres pantallas: de 10 s a 3 min"),
                new ParamSpec("tiempo de confirmar (s)", List.of("times.confirm.meanSeconds"), 15, 300,
                        "revisar el horario final y confirmar: de 15 s a 5 min"),
                new ParamSpec("cv de los tiempos compartidos", SHARED_CV, 0.2, 1.2,
                        "dispersión lognormal de una acción humana: de moderada a muy asimétrica"),
                new ParamSpec("cv de la velocidad por estudiante", List.of("behavior.speedCv"), 0, 1.2,
                        "heterogeneidad entre estudiantes (cola pesada de A11); 0 = todos igual de rápidos"),
                new ParamSpec("fracción que planeó antes (planificadores)", List.of("behavior.plannerShare"), 0.05, 0.20,
                        "A10: 11,1 % armó el horario antes en papel; margen de ±9 puntos por el error de la muestra (n = 135)"),
                new ParamSpec("conciencia de cruces del ensayo y error (media)", List.of("behavior.clashAwareness"), 0, 0.35,
                        "A10/A12: 71,9 % hizo ensayo y error y 34,1 % no preparó nada: conciencia baja, no mayor a 0,35"),
                new ParamSpec("concentración de la conciencia (beta)", List.of("behavior.awarenessConcentration"), 1, 30,
                        "1 = muy heterogénea (U/J), 30 = casi igual para todos"),
                new ParamSpec("peso de la jornada preferida", List.of("behavior.preferenceStrength"), 0, 6,
                        "0 = elige al azar; 6 = casi siempre el grupo de su jornada"),
                new ParamSpec("prob. de aplazar al agotar grupos", List.of("behavior.deferOnExhaustProbability"), 0.15, 0.45,
                        "A14: 31,3 % (n = 80); intervalo aproximado de la muestra"),
                new ParamSpec("holgura de cupos (cupo = demanda x factor)", List.of("offer.slackFactor"), 0.9, 1.5,
                        "una oferta con menos cupos que demanda total o hasta 50 % de holgura"),
                new ParamSpec("cupo relativo de los grupos populares", List.of("offer.popularSeatShare"), 0.4, 1.0,
                        "1 = sin escasez de la jornada más pedida; 0,4 = muy escasos"),
                new ParamSpec("desigualdad del reparto de cupos", List.of("offer.seatUnevenness"), 0, 1,
                        "0 = grupos del mismo tamaño; 1 = muy desiguales"),
                new ParamSpec("concentración de los bloques en la jornada del grupo", List.of("offer.shiftAlignment"), 0.6, 1.0,
                        "a más concentración, más cruces entre grupos de la misma jornada"),
                new ParamSpec("probabilidad de no recargar tras un rechazo", List.of("behavior.staleViewProbability"), 0, 1,
                        "el estudiante reintenta sobre la página abierta sin recargarla (cupos viejos): de nunca a siempre"),
                new ParamSpec("probabilidad de bloque semanal adicional", List.of("offer.extraBlockProbability"), 0, 0.8,
                        "prácticas o laboratorios además de las horas por créditos: de ninguna a 8 de cada 10 asignaturas"),
                new ParamSpec("dispersión de la popularidad de los grupos", List.of("offer.popularitySkew"), 0, 2,
                        "0 = todos los grupos igual de deseados; 2 = unos pocos concentran la demanda (profesor, horario)"),
                new ParamSpec("tamaño objetivo de grupo", List.of("offer.targetGroupSize"), 12, 60,
                        "grupos de 12 a 60 estudiantes; define cuántos grupos tiene una asignatura"),
                new ParamSpec("llegadas por minuto (por ventana)", List.of("arrivals.arrivalsPerMinute"), 1, 40,
                        "de 1 a 40 estudiantes por minuto entrando a la plataforma en la ventana de matrícula"),
                new ParamSpec("fracción que entra en la avalancha de apertura", List.of("arrivals.rushShare"), 0, 0.9,
                        "a la hora exacta de apertura de la ventana entra una parte de los estudiantes; de ninguno a 9 de cada 10"),
                new ParamSpec("media de la avalancha de apertura (s)", List.of("arrivals.rushMeanSeconds"), 30, 600,
                        "la avalancha se disuelve en entre medio minuto y 10 min"),
                new ParamSpec("umbral de casi perder el cupo (cupos)", List.of("behavior.nearLossSeats"), 1, 5,
                        "definición de 'casi': de 1 a 5 cupos restantes al inscribir", true),
                new ParamSpec("mínimo de grupos por asignatura", List.of("offer.minSections"), 1, 3,
                        "hay asignaturas con un solo grupo (el cruce obliga a mover otra materia) y otras con 2 o 3", true)));
    }

    public List<ParamSpec> specs() {
        return specs;
    }

    public int size() {
        return specs.size();
    }

    /** Valores reales a partir de coordenadas normalizadas en [0, 1]. */
    public double[] values(double[] unit) {
        double[] v = new double[specs.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = specs.get(i).denormalize(unit[i]);
            if (specs.get(i).integer()) {
                v[i] = specs.get(i).clamp(Math.rint(v[i]));
            }
        }
        return v;
    }

    /** Coordenadas normalizadas del valor actual de {@code config} (recortadas a las cotas). */
    public double[] unitOf(ExperimentConfig config) {
        double[] u = new double[specs.size()];
        for (int i = 0; i < u.length; i++) {
            u[i] = specs.get(i).normalize(config.valueAt(specs.get(i).paths().getFirst()));
        }
        return u;
    }

    /** Copia de {@code base} con los valores reales dados. */
    public ExperimentConfig apply(ExperimentConfig base, double[] values) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            for (String path : specs.get(i).paths()) {
                map.put(path, values[i]);
            }
        }
        return base.withValues(map);
    }

    /** Copia de {@code base} con las coordenadas normalizadas dadas. */
    public ExperimentConfig applyUnit(ExperimentConfig base, double[] unit) {
        return apply(base, values(unit));
    }
}
