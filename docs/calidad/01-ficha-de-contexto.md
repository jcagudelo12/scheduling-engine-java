# 1. Ficha de contexto

Caso: **proyecto propio**. El cliente es la oficina de Admisiones y Registro del ITM, primer
usuario del motor, con la visión de que después lo usen otras universidades.

## Lo que dice el cliente

> "Cada semestre la matrícula es igual: abrimos el turno y en diez minutos los grupos buenos ya
> no tienen cupo, y la mitad de los estudiantes arma un horario que choca o que ya no existe
> cuando le da *matricular*. Después llegan las quejas y las solicitudes de ajuste, que nos
> toman semanas. Queremos que el estudiante vea de una vez las combinaciones que sí le sirven,
> que sean las mejores para él y que estén actualizadas, porque nada genera más reclamos que
> mostrarle un cupo que ya no hay. Eso sí: tiene que ser rápido, aunque entren todos al tiempo,
> y no nos puede tumbar el sistema académico, que es el mismo que está matriculando. TI ya nos
> advirtió que no va a dejar que nadie le meta carga a esa base en plena matrícula. Y que sea
> justo: no queremos que unos pocos se queden con todo."

## Anclas firmes

1. **Demanda.** 26.000 estudiantes de pregrado matriculan por turnos, según su promedio, durante
   5 días. Al abrir cada turno entran hasta **4.000 estudiantes en los primeros 15 minutos**, y
   cada uno consulta sus combinaciones unas 10 veces.
2. **Ritmo del catálogo.** Hay 5.000 grupos de 1.000 cursos, con cupos de 25 a 40. En la apertura
   de un turno se registran hasta **30 matrículas por segundo**, y los grupos más pedidos se llenan
   en **menos de 10 minutos**.
3. **Tamaño de la búsqueda.** Un estudiante tiene hasta **7 materias** habilitadas, con hasta
   **8 grupos** cada una: hasta 2,1 millones de combinaciones posibles antes de podar. El portal
   debe mostrar sus combinaciones en **menos de 1 s (p95)**.
4. **Sistema académico.** Sigue siendo el único que confirma matrículas, y el motor no escribe en
   él ([ADR 0002](../adr/0002-roles-de-la-institucion.md)). Su base de datos es la misma que
   atiende la matrícula, y TI no permite que procesos externos usen **más del 5 % de su
   capacidad** durante los turnos, ni que se instale nada en ella.
5. **Recursos.** Un equipo de **5 personas** y **1.500 USD al mes** de infraestructura. El motor
   debe poder instalarse después en otras universidades **sin cambiar su código**, solo
   configurándolo.

## Tensión dominante

En este motor, **recomendar al instante las mejores combinaciones choca con que esas
recomendaciones sigan siendo ciertas cuando el estudiante va a matricular**. Los cupos cambian
más rápido de lo que la institución puede informarlos sin sobrecargar su propia base, y la
recomendación misma concentra la demanda en los mismos grupos.

## Lo que el cliente no dijo

Cada pregunta se responde como supuesto en [02-supuestos.md](02-supuestos.md).

- ¿Qué tan "actualizado" debe estar un cupo: segundos o un minuto?
- ¿Qué es "lo mejor" para un estudiante, y quién lo define?
- ¿Se puede apartar un cupo mientras el estudiante decide?
- ¿Qué pasa si el motor recomienda lo mismo a todos y esos grupos se llenan?
- ¿Cuánto cuesta en la base académica leer el catálogo, y qué permisos dará TI?
- Si una búsqueda tarda demasiado, ¿se espera o se muestra algo aproximado?
- Si el motor falla en plena matrícula, ¿el estudiante puede seguir matriculando?

## Decisión de producto escondida

"Justo" no es un requisito técnico. Si el motor ordena por lo que cada estudiante prefiere, todos
los de un mismo semestre reciben los mismos grupos como "los mejores", y esos grupos se agotan
para los que consultan un minuto después. Si en cambio reparte la demanda, a algunos les
recomienda algo que no es lo mejor para ellos. Cualquier mecanismo que se elija favorece a alguien
(a quien consulta primero, a quien tiene preferencias comunes o a quien tiene preferencias raras),
y esa decisión es de la institución, no del algoritmo. Los ADRs la declaran donde aparece.

## Lo que ya está decidido

Estas decisiones son restricciones del contexto. No se reabren en esta entrega:

| ADR | Restricción |
|---|---|
| [0002](../adr/0002-roles-de-la-institucion.md) | La institución empuja los datos; el motor nunca la consulta ni escribe en ella |
| [0003](../adr/0003-replica-del-catalogo.md) | El motor responde desde una réplica en memoria y marca `stale` cuando puede estar desactualizada |
| [0005](../adr/0005-varias-instancias-con-historial-compartido.md) | Varias instancias de consulta alimentadas por un historial compartido, con una sola ingesta |
| [0006](../adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md) | El motor devuelve las 5 mejores combinaciones exactas según los criterios que ordena el estudiante |

El rendimiento del motor ya está medido ([bench/README.md](../../bench/README.md)): p99 de 2,6 ms
a 4.000 solicitudes/s. El pico del ancla 1 es de unas 45 solicitudes/s, así que **el volumen no es
una tensión**. Las tensiones están en la frescura de los datos, en el peor caso de una búsqueda y
en la carga sobre la institución.
