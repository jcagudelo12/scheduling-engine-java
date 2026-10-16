# 3. Priorización con presupuesto de puntos

18 puntos repartidos entre las 8 características de ISO/IEC 25010, de 1 a 5 cada una. Con el
mínimo de 1 por atributo, quedan 10 puntos libres.

| Atributo | Puntos | Subcaracterística que pesa | Por qué |
|---|:---:|---|---|
| **Adecuación funcional** | **5** | Corrección funcional | Es la razón de ser del motor: validar *antes*. Una combinación que choca o que ofrece un grupo lleno es un resultado incorrecto, y produce justo los reclamos que el cliente quiere eliminar |
| **Eficiencia de desempeño** | **4** | Comportamiento temporal | El portal tiene 1 s (ancla 3) y una búsqueda puede explotar a 2,1 millones de combinaciones. No es un problema de volumen (el pico es de 45 solicitudes/s y ya está medido), sino del peor caso de una sola búsqueda |
| **Compatibilidad** | **3** | Coexistencia e interoperabilidad | El motor depende de leer una base que está atendiendo la matrícula en ese mismo momento, con un tope del 5 % (ancla 4). Si la afecta, perjudica el proceso que viene a mejorar |
| **Portabilidad** | **2** | Instalabilidad y adaptabilidad | El motor debe instalarse en otras universidades solo con configuración (ancla 5). Importa para el producto, pero el primer cliente es uno solo |
| Fiabilidad | 1 | Disponibilidad | El motor funciona en paralelo con el sistema de matrícula (ancla 4): si cae, el estudiante matricula como hoy. Es una ayuda, no un paso obligatorio. La réplica en memoria y el historial compartido ya cubren lo básico (ADR 0003 y 0005) |
| Seguridad | 1 | Integridad | El motor no guarda datos sensibles ni confirma matrículas. La manipulación del contexto del estudiante ya está resuelta con el token firmado (ADR 0004) |
| Usabilidad | 1 | Operabilidad | La pantalla es del portal institucional; el motor entrega datos. Su costo aparece en algunos escenarios, pero no los gobierna |
| Mantenibilidad | 1 | Modularidad | La arquitectura hexagonal ya aísla las capas (ADR 0001) y no hay una tensión abierta aquí |
| **Total** | **18** | | |

## Qué bajamos para subir otros

- **Fiabilidad a 1 para dar 5 a adecuación funcional y 4 a desempeño.** Es posible porque el
  motor funciona en paralelo con el sistema de matrícula (ancla 4): su caída no impide matricular.
- **Usabilidad a 1 aunque el estudiante es el usuario final.** La experiencia la diseña el portal.
  El costo de esta decisión aparece en E4 (efecto manada) y en una opción del ADR 0009, que pide
  más filtros al estudiante: en ambos casos el que paga es un atributo bajo, y por eso no
  definen la arquitectura.
- **Compatibilidad sobre portabilidad.** Hoy pesa más no dañar la base del ITM que facilitar la
  instalación en la próxima universidad. Esto puede invertirse cuando haya una segunda.

## Cómo se conecta con los escenarios

| Escenario | Atributo dominante | Atributo que paga |
|---|---|---|
| E1: grupo lleno durante el pico | Adecuación funcional (5) | Desempeño (4) y compatibilidad (3) |
| E2: búsqueda que explota | Desempeño (4) | Adecuación funcional (5) |
| E3: detección de cambios en el SDK | Compatibilidad (3) | Adecuación funcional (5) y portabilidad (2) |
| E4: efecto manada | Adecuación funcional (5) | Usabilidad (1) |

Los tres atributos más altos se enfrentan entre sí en E1, E2 y E3. Por eso esos escenarios son
los críticos (ver [04-escenarios.md](04-escenarios.md)).
