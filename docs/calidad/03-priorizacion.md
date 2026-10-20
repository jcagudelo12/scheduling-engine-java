# 3. Priorización con presupuesto de puntos

18 puntos repartidos entre las 8 características de ISO/IEC 25010, de 1 a 5 cada una. Con el
mínimo de 1 por atributo, quedan 10 puntos libres.

| Atributo | Puntos | Subcaracterística que pesa | Por qué |
|---|:---:|---|---|
| **Adecuación funcional** | **5** | Corrección funcional | Es la razón de ser del motor: validar *antes*. Una combinación que choca o que ofrece un grupo lleno es un resultado incorrecto, y produce justo los reclamos que el cliente quiere eliminar |
| **Eficiencia de desempeño** | **3** | Comportamiento temporal | El portal tiene 1 s (ancla 3) y una búsqueda puede explotar a 2,1 millones de combinaciones. No es un problema de volumen (el pico es de 45 solicitudes/s y ya está medido), sino del peor caso de una sola búsqueda |
| **Compatibilidad** | **3** | Coexistencia e interoperabilidad | El motor depende de leer una base que está atendiendo la matrícula en ese mismo momento, con un tope del 5 % (ancla 4). Si la afecta, perjudica el proceso que viene a mejorar |
| **Usabilidad** | **3** | Operabilidad y protección contra errores | El front con el que el estudiante elige materias y filtros y ve sus 5 mejores combinaciones lo construimos nosotros. Si lo recomendado no se puede matricular, o si el orden no se entiende, el estudiante vuelve a armar el horario a mano |
| Portabilidad | 1 | Instalabilidad y adaptabilidad | El motor debe instalarse en otras universidades solo con configuración (ancla 5). Importa para el producto, pero el primer cliente es uno solo |
| Fiabilidad | 1 | Disponibilidad | El motor funciona en paralelo con el sistema de matrícula (ancla 4): si cae, el estudiante matricula como hoy. Es una ayuda, no un paso obligatorio. La réplica en memoria y el historial compartido ya cubren lo básico (ADR 0003 y 0005) |
| Seguridad | 1 | Integridad | El motor no guarda datos sensibles ni confirma matrículas. La manipulación del contexto del estudiante ya está resuelta con el token firmado (ADR 0004) |
| Mantenibilidad | 1 | Modularidad | La arquitectura hexagonal ya aísla las capas (ADR 0001) y no hay una tensión abierta aquí |
| **Total** | **18** | | |

## Qué bajamos para subir otros

- **Fiabilidad a 1 para dar 5 a adecuación funcional.** Es posible porque el motor funciona en
  paralelo con el sistema de matrícula (ancla 4): su caída no impide matricular.
- **Desempeño de 4 a 3 para subir la usabilidad.** El volumen ya está medido y no es una tensión;
  lo que queda es el peor caso de una búsqueda (E2), que sigue entre los atributos más altos.
- **Portabilidad de 2 a 1 para subir la usabilidad.** Hoy pesa más que el primer estudiante
  pueda usar bien el front que facilitar la instalación en la próxima universidad. Esto puede
  invertirse cuando haya una segunda.

## Cómo se conecta con los escenarios

| Escenario | Atributo dominante | Atributo que paga |
|---|---|---|
| E1: grupo lleno durante el pico | Adecuación funcional (5) | Desempeño (3) y compatibilidad (3) |
| E2: búsqueda que explota | Desempeño (3) | Adecuación funcional (5) |
| E3: detección de cambios en el SDK | Compatibilidad (3) | Adecuación funcional (5) y portabilidad (1) |
| E4: efecto manada | Adecuación funcional (5) | Usabilidad (3) |

Los cuatro atributos más altos (adecuación funcional, desempeño, compatibilidad y usabilidad) se
enfrentan entre sí en los cuatro escenarios. Por eso los cuatro son críticos (ver
[04-escenarios.md](04-escenarios.md)).
