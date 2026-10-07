# 0001. Usar arquitectura hexagonal con un módulo Maven por capa

- **Estado:** Aceptado
- **Fecha:** 2026-09-25

## Contexto

El motor debe evaluarse con datos simulados y, a la vez, integrarse con los sistemas
de la institución (gRPC y un bus de mensajes). Para que la comparación contra el
baseline sea defendible, el código evaluado en la simulación tiene que ser el mismo que
corre en producción.

## Decisión

Organizar el sistema en capas con las dependencias apuntando hacia adentro:

- `domain` (`sched-domain`): modelo puro, sin dependencias externas, sin I/O ni frameworks.
- `solver` (`sched-solver`): búsqueda de combinaciones; depende solo de `domain`.
- `application` (`sched-application`): casos de uso y puertos (interfaces).
- `adapters/*`: las únicas piezas que tocan el exterior (HTTP, gRPC, NATS, memoria).
- `server`: aplicación Spring Boot que instancia adaptadores y los inyecta.

Cada capa es un módulo Maven y solo ve las clases de los módulos que declara como
dependencia, así que las violaciones de dependencia son errores de compilación y no solo
convenciones. Spring solo aparece en `adapters/gateway-http` y en `server`.

## Consecuencias

- La simulación inyecta `adapters/in-memory` por los mismos puertos que en producción.
- Los adaptadores se pueden reemplazar sin tocar el núcleo.
- Hay más fricción de compilación y más `pom.xml`. Se acepta: esa fricción es lo que
  impide importar gRPC o Spring desde el dominio "solo por esta vez".
- Si aparece la tentación de meter I/O o anotaciones de Spring en `domain` o `solver`, el
  caso de uso pertenece a `application` o a un adaptador.

## Alternativas consideradas

- **Un solo módulo con paquetes:** más simple, pero los límites dependerían solo de la disciplina.
- **Arquitectura en capas clásica:** acopla la lógica a la infraestructura y dificulta la simulación.
