# 2. Supuestos declarados

Lo que el cliente no dijo y decidimos asumir. Cada supuesto cambia un número o un atributo, e
indica qué escenarios dependen de él y qué pasaría si el cliente respondiera lo contrario.

| # | Supuesto | Razón | Pregunta para confirmarlo | Si fuera falso | Escenarios |
|---|---|---|---|---|---|
| S1 | **"Actualizado" significa que un grupo lleno deja de ofrecerse en 5 s o menos** | Un grupo de 35 cupos que se llena en 10 minutos pierde un cupo cada 17 s. Con 5 s de retraso, cerca de 1 de cada 3 recomendaciones del último cupo ya no sería válida; con 30 s, casi todas | ¿Cuántos reclamos por "me mostró cupo y no había" está dispuesto a aceptar por turno? | Si se exige menos de 1 s, el sondeo de la base queda descartado y solo sirven mecanismos de empuje (E3). Si basta con 30 s, la tensión de E1 casi desaparece | E1, E3 |
| S2 | **El motor dispone de 200 ms (p99) del segundo que tiene el portal** | El portal, la red y el navegador consumen el resto. El motor responde hoy en 3 ms, pero la búsqueda del top 5 con filtros todavía no está medida en el peor caso | ¿Cuánto del segundo consume hoy el portal? | Con menos margen, la opción de búsqueda exacta sin límite deja de ser viable (E2) | E2 |
| S3 | **Leer el catálogo completo cuesta unos 200 ms de CPU de la base académica** | Son 5.000 grupos y unas 15.000 franjas con un par de uniones. Leerlo cada 5 s consumiría cerca del 4 % de un núcleo, justo bajo el tope del 5 % del ancla 4 | ¿Puede TI medir esa consulta en la base real durante un turno? | Si cuesta el doble, sondear cada 5 s rompe el tope de TI, y S1 y el ancla 4 quedan en conflicto directo | E3 |
| S4 | **No se puede apartar un cupo mientras el estudiante decide** | El sistema académico no tiene reservas temporales, y el motor no escribe en él (ADR 0002) | ¿Aceptaría Registro que el sistema académico aparte un cupo durante 5 minutos? | Si se acepta, aparece una opción que elimina el problema de E1 en el momento de matricular, a costa de cupos bloqueados por reservas abandonadas | E1 |
| S5 | **En cada turno, al menos el 60 % de los estudiantes comparte 4 o más materias con otros de su semestre** | El plan de estudios es fijo en los primeros semestres y los turnos se arman por promedio, que mezcla poco los programas | ¿Cuál es la distribución real de materias por turno del último semestre? | Si los perfiles son variados, el efecto manada es menor y E4 pierde peso | E4 |
| S6 | **Mostrar 5 combinaciones buenas marcadas como "no garantizadas" es aceptable si se dice** | Es preferible a no mostrar nada o a hacer esperar al estudiante | ¿Acepta Registro que el portal diga "podría haber mejores combinaciones"? | Si no se acepta, todo resultado debe ser exacto, y la latencia de E2 solo se controla limitando lo que el estudiante puede pedir | E2 |
| S7 | **El peor caso real es de 7 materias con 8 grupos cada una** | Sale del tope de créditos (unos 20 por semestre) y de la oferta de los cursos más demandados | ¿Cuántas materias y cuántos grupos por materia tuvo el estudiante más extremo del semestre pasado? | Con 9 materias el espacio se multiplica por 64: el escenario E2 y sus medidas cambian de escala | E2 |

## Supuestos que más arriesgan la entrega

- **S1 y S3 juntos.** Definen si existe una frecuencia de lectura que cumpla a la vez "actualizado"
  y "no cargar la base". Son lo primero que hay que confirmar con Registro y con TI.
