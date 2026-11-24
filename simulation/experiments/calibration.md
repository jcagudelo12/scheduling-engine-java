# Calibración del flujo A con la consulta a estudiantes

- Configuración de partida: `simulation/experiments/base.json`
- Semilla de calibración: 42; perfiles por evaluación: 500; presupuesto: 20000 evaluaciones (16589 usadas; 10000 de hipercubo latino y 12 refinamientos Nelder-Mead); duración: 103 s
- Salida: `calibrated.json` (configuración completa, lista para `experiment`).

## 1. Método

Se buscan los parámetros del flujo A y los compartidos con B (tiempos de las acciones comunes, mezcla de conciencia de cruces, heterogeneidad de velocidad, escasez de cupos y tasa de llegadas) que minimizan la distancia entre las distribuciones simuladas por el flujo A y las de la consulta en A9 (veces que rehizo la selección por cruces), A11 (tiempo en la plataforma) y A13 (perdió o casi perdió cupo). La distancia por tabla es la de **Hellinger** (acotada en [0, 1], simétrica, definida con categorías vacías y sensible a las categorías pequeñas); la total es la media ponderada con pesos iguales. La búsqueda es un hipercubo latino (exploración global) seguido de Nelder-Mead desde los mejores puntos (refinamiento local, en el cubo unitario normalizado y con las cotas como restricción), con los mismos números aleatorios (la misma semilla) en todas las evaluaciones, de modo que las diferencias entre dos puntos se deben a los parámetros y no al azar. Las evaluaciones corren en paralelo. **Los tiempos propios de B no se calibran**: no hay datos de B; se varían en el análisis de sensibilidad (`sensitivity`).

## 2. Distancia

| Tabla | Piso de ruido | Inicial | Calibrada (semilla 42) | Fuera de muestra (semilla 7961) | Media de 5 semillas nuevas | Veredicto |
|---|---|---|---|---|---|---|
| A9 | 0.0685 | 0.3198 | 0.2366 | 0.2348 | 0.2443 | parcialmente reproducida (3.6 x piso) |
| A11 | 0.0588 | 0.2996 | 0.1228 | 0.1287 | 0.1245 | parcialmente reproducida (2.1 x piso) |
| A13 | 0.0391 | 0.3449 | 0.2086 | 0.2130 | 0.2357 | **NO reproducida** (6.0 x piso) |
| **Total** | | 0.3214 | 0.1893 | 0.1922 | 0.2015 | |

Distancia de Hellinger (0 = idénticas, 1 = sin categorías en común). El **piso de ruido** es la distancia esperada entre la propia distribución de la consulta y una muestra de 135 estudiantes sacada de ella (2.000 remuestreos): con n = 135 la consulta misma no se puede reproducir con distancia menor sin sobreajustar. Veredicto por tabla, según la media fuera de muestra en múltiplos de su piso: reproducida hasta 2 x, parcialmente reproducida hasta 4 x, no reproducida por encima.

## 3. Consulta frente a simulado

Porcentajes. *Calibrada*: la semilla de calibración; *fuera de muestra*: la semilla 7961.

**A9: veces que rehizo la selección por cruces**

| Categoría | Consulta | Simulado (calibrada) | Simulado (fuera de muestra) | Inicial |
|---|---|---|---|---|
| 0 | 11.9 | 31.1 | 30.2 | 41.7 |
| 1 | 15.6 | 21.0 | 18.8 | 24.9 |
| 2 | 30.3 | 16.1 | 15.4 | 14.2 |
| 3 | 23.0 | 11.0 | 10.9 | 8.9 |
| 4 | 1.5 | 5.9 | 6.4 | 4.4 |
| 5 o más | 17.8 | 15.1 | 18.4 | 6.1 |

**A11: tiempo en la plataforma hasta confirmar**

| Categoría | Consulta | Simulado (calibrada) | Simulado (fuera de muestra) | Inicial |
|---|---|---|---|---|
| menos de 15 min | 3.0 | 6.1 | 5.6 | 28.8 |
| 15-30 min | 47.4 | 36.1 | 35.0 | 40.1 |
| 31-60 min | 35.6 | 41.9 | 41.2 | 23.8 |
| 1-2 h | 9.6 | 14.5 | 16.8 | 7.2 |
| más de 2 h | 4.4 | 1.4 | 1.6 | 0.3 |

**A13: perdió o casi perdió cupo al resolver un cruce**

| Categoría | Consulta | Simulado (calibrada) | Simulado (fuera de muestra) | Inicial |
|---|---|---|---|---|
| sí | 39.3 | 14.9 | 14.6 | 3.8 |
| casi | 17.8 | 33.6 | 34.4 | 19.2 |
| no | 43.0 | 51.5 | 51.0 | 77.1 |

Otros indicadores del flujo A calibrado: media de rechazos por cruce = 2.24, por cupo = 0.19; asignaturas matriculadas / pedidas = 0.879 (inicial: 0.902). Un valor muy bajo de este último sería una señal de que la escasez calibrada es poco realista.

## 4. Parámetros calibrados y sus cotas

| Parámetro | Ruta(s) | Cotas | Inicial | Calibrado | En la cota | Sensibilidad local (ΔD a ±10 % del rango) | Identificable |
|---|---|---|---|---|---|---|---|
| tiempo de navegar una asignatura (s) | `times.browseCourse.meanSeconds` | [30.0, 360] | 150 | 219 | no | 0.0138 | sí |
| tiempo de elegir un grupo, solo A (s) | `times.chooseSection.meanSeconds` | [15.0, 240] | 70.0 | 35.1 | no | 0.0131 | sí |
| tiempo de enviar un grupo (s) | `times.submitSection.meanSeconds` | [5.00, 90.0] | 20.0 | 61.2 | no | 0.0057 | sí |
| tiempo de leer un rechazo (s) | `times.readRejection.meanSeconds` | [10.0, 240] | 60.0 | 60.7 | no | 0.0094 | sí |
| tiempo de retirar un grupo (s) | `times.withdraw.meanSeconds` | [10.0, 180] | 45.0 | 133 | no | 0.0014 | **no** |
| tiempo de confirmar (s) | `times.confirm.meanSeconds` | [15.0, 300] | 90.0 | 114 | no | 0.0006 | **no** |
| cv de los tiempos compartidos | `times.browseCourse.cv (y otros 5)` | [0.200, 1.20] | 0.600 | 0.663 | no | 0.0070 | sí |
| cv de la velocidad por estudiante | `behavior.speedCv` | [0.000, 1.20] | 0.500 | 0.178 | no | 0.0173 | sí |
| fracción que planeó antes (planificadores) | `behavior.plannerShare` | [0.050, 0.200] | 0.111 | 0.179 | no | 0.0039 | **no** |
| conciencia de cruces del ensayo y error (media) | `behavior.clashAwareness` | [0.000, 0.350] | 0.150 | 0.156 | no | 0.0099 | sí |
| concentración de la conciencia (beta) | `behavior.awarenessConcentration` | [1.00, 30.0] | 4.00 | 13.9 | no | 0.0110 | sí |
| peso de la jornada preferida | `behavior.preferenceStrength` | [0.000, 6.00] | 2.00 | 3.20 | no | 0.0143 | sí |
| prob. de aplazar al agotar grupos | `behavior.deferOnExhaustProbability` | [0.150, 0.450] | 0.310 | 0.217 | no | 0.0022 | **no** |
| holgura de cupos (cupo = demanda x factor) | `offer.slackFactor` | [0.900, 1.50] | 1.10 | 0.983 | no | 0.0107 | sí |
| cupo relativo de los grupos populares | `offer.popularSeatShare` | [0.400, 1.00] | 0.700 | 0.906 | no | 0.0060 | sí |
| desigualdad del reparto de cupos | `offer.seatUnevenness` | [0.000, 1.00] | 0.700 | 0.180 | no | 0.0122 | sí |
| concentración de los bloques en la jornada del grupo | `offer.shiftAlignment` | [0.600, 1.00] | 0.850 | 0.835 | no | 0.0087 | sí |
| probabilidad de no recargar tras un rechazo | `behavior.staleViewProbability` | [0.000, 1.00] | 0.300 | 0.091 | no | 0.0042 | **no** |
| probabilidad de bloque semanal adicional | `offer.extraBlockProbability` | [0.000, 0.800] | 0.200 | 0.737 | no | 0.0114 | sí |
| dispersión de la popularidad de los grupos | `offer.popularitySkew` | [0.000, 2.00] | 0.500 | 0.522 | no | 0.0107 | sí |
| tamaño objetivo de grupo | `offer.targetGroupSize` | [12.0, 60.0] | 25.0 | 14.2 | no | 0.0107 | sí |
| llegadas por minuto (por ventana) | `arrivals.arrivalsPerMinute` | [1.00, 40.0] | 6.00 | 21.5 | no | 0.0035 | **no** |
| fracción que entra en la avalancha de apertura | `arrivals.rushShare` | [0.000, 0.900] | 0.300 | 0.769 | no | 0.0042 | **no** |
| media de la avalancha de apertura (s) | `arrivals.rushMeanSeconds` | [30.0, 600] | 120 | 37.1 | **sí** | 0.0037 | **no** |
| umbral de casi perder el cupo (cupos) | `behavior.nearLossSeats` | [1.00, 5.00] | 2.00 | 2.00 | no | 0.0112 | sí |
| mínimo de grupos por asignatura | `offer.minSections` | [1.00, 3.00] | 2.00 | 3.00 | no | 0.0176 | sí |

Justificación de las cotas:

- tiempo de navegar una asignatura (s): de medio minuto a 6 min por página de oferta; la consulta reporta 15-60 min para 4-5 asignaturas.
- tiempo de elegir un grupo, solo A (s): decidir entre 2-6 grupos mirando horarios: de 15 s a 4 min.
- tiempo de enviar un grupo (s): un clic y la espera de la plataforma: de 5 s a 1,5 min.
- tiempo de leer un rechazo (s): leer el mensaje y entender el cruce: de 10 s a 4 min.
- tiempo de retirar un grupo (s): dos o tres pantallas: de 10 s a 3 min.
- tiempo de confirmar (s): revisar el horario final y confirmar: de 15 s a 5 min.
- cv de los tiempos compartidos: dispersión lognormal de una acción humana: de moderada a muy asimétrica.
- cv de la velocidad por estudiante: heterogeneidad entre estudiantes (cola pesada de A11); 0 = todos igual de rápidos.
- fracción que planeó antes (planificadores): A10: 11,1 % armó el horario antes en papel; margen de ±9 puntos por el error de la muestra (n = 135).
- conciencia de cruces del ensayo y error (media): A10/A12: 71,9 % hizo ensayo y error y 34,1 % no preparó nada: conciencia baja, no mayor a 0,35.
- concentración de la conciencia (beta): 1 = muy heterogénea (U/J), 30 = casi igual para todos.
- peso de la jornada preferida: 0 = elige al azar; 6 = casi siempre el grupo de su jornada.
- prob. de aplazar al agotar grupos: A14: 31,3 % (n = 80); intervalo aproximado de la muestra.
- holgura de cupos (cupo = demanda x factor): una oferta con menos cupos que demanda total o hasta 50 % de holgura.
- cupo relativo de los grupos populares: 1 = sin escasez de la jornada más pedida; 0,4 = muy escasos.
- desigualdad del reparto de cupos: 0 = grupos del mismo tamaño; 1 = muy desiguales.
- concentración de los bloques en la jornada del grupo: a más concentración, más cruces entre grupos de la misma jornada.
- probabilidad de no recargar tras un rechazo: el estudiante reintenta sobre la página abierta sin recargarla (cupos viejos): de nunca a siempre.
- probabilidad de bloque semanal adicional: prácticas o laboratorios además de las horas por créditos: de ninguna a 8 de cada 10 asignaturas.
- dispersión de la popularidad de los grupos: 0 = todos los grupos igual de deseados; 2 = unos pocos concentran la demanda (profesor, horario).
- tamaño objetivo de grupo: grupos de 12 a 60 estudiantes; define cuántos grupos tiene una asignatura.
- llegadas por minuto (por ventana): de 1 a 40 estudiantes por minuto entrando a la plataforma en la ventana de matrícula.
- fracción que entra en la avalancha de apertura: a la hora exacta de apertura de la ventana entra una parte de los estudiantes; de ninguno a 9 de cada 10.
- media de la avalancha de apertura (s): la avalancha se disuelve en entre medio minuto y 10 min.
- umbral de casi perder el cupo (cupos): definición de 'casi': de 1 a 5 cupos restantes al inscribir.
- mínimo de grupos por asignatura: hay asignaturas con un solo grupo (el cruce obliga a mover otra materia) y otras con 2 o 3.

**Parámetros no identificables.** Un parámetro es no identificable si mover su valor 10 % de su rango (a cada lado, con todo lo demás en el óptimo) cambia la distancia total menos de 0.005. Para esos parámetros, el valor reportado es uno entre muchos igual de buenos: no debe leerse como una estimación. Son: tiempo de retirar un grupo (s); tiempo de confirmar (s); fracción que planeó antes (planificadores); prob. de aplazar al agotar grupos; probabilidad de no recargar tras un rechazo; llegadas por minuto (por ventana); fracción que entra en la avalancha de apertura; media de la avalancha de apertura (s).

## 5. Hallazgos de validez

- **A9 se reproduce solo parcialmente** dentro de las cotas plausibles (distancia fuera de muestra = 3.6 veces el piso de ruido). Es una limitación del modelo de comportamiento, no algo que se corrija ampliando las cotas hasta valores sin sentido; el flujo A calibrado no representa bien ese fenómeno y la conclusión sobre la reducción de Ttotal debe leerse con esa advertencia.
- **A11 se reproduce solo parcialmente** dentro de las cotas plausibles (distancia fuera de muestra = 2.1 veces el piso de ruido). Es una limitación del modelo de comportamiento, no algo que se corrija ampliando las cotas hasta valores sin sentido; el flujo A calibrado no representa bien ese fenómeno y la conclusión sobre la reducción de Ttotal debe leerse con esa advertencia.
- **A13 NO se reproduce** dentro de las cotas plausibles (distancia fuera de muestra = 6.0 veces el piso de ruido). Es una limitación del modelo de comportamiento, no algo que se corrija ampliando las cotas hasta valores sin sentido; el flujo A calibrado no representa bien ese fenómeno y la conclusión sobre la reducción de Ttotal debe leerse con esa advertencia.
- `media de la avalancha de apertura (s)` quedó en una cota (37.1): el ajuste pide un valor en el borde o fuera de lo que se consideró plausible.

## 6. Limitaciones

- Se calibra con una consulta de 135 estudiantes: las propias distribuciones objetivo tienen error de muestreo (ver piso de ruido). La distancia fuera de muestra cambia la semilla del simulador, no la consulta.
- Con 26 parámetros y tres tablas (14 categorías, 11 grados de libertad) el problema es sub-determinado: hay combinaciones distintas igual de buenas. Que A9, A11 y A13 se reproduzcan no prueba que cada parámetro sea correcto (ver los no identificables).
- La consulta no define cuándo empieza y termina "resolver un cruce" (A13): se operó como el periodo desde el primer cruce que encuentra el estudiante hasta que confirma (ver `docs/simulador.md`). Con otra definición, el ajuste de A13 cambia.
- B no tiene datos empíricos: sus tiempos propios son supuestos y se someten a sensibilidad.
