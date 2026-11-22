# Experimento pareado: cómo reproducirlo

Todo se corre con el jar del módulo `simulation` y la semilla fija 42. Cada paso es un solo comando
desde la raíz del repositorio.

| Archivo | Qué es |
|---|---|
| `base.json` | Parámetros por defecto del simulador (supuestos, **sin calibrar**) |
| `calibrated.json` | Configuración con los parámetros del flujo A calibrados con la consulta (salida de `calibrate`) |
| `calibration.md`, `calibration.json` | Reporte de la calibración: consulta frente a simulado, distancias, parámetros y cotas |
| `resultados/analysis.md`, `analysis.json` | Análisis estadístico del experimento final (`experiment --analyze`) |
| `resultados/sensitivity.md`, `sensitivity.json` | Análisis de sensibilidad, punto de quiebre, réplicas y tamaño de muestra |
| `resultados/metadata.json` | Semilla, versión y configuración efectiva del experimento final |

`resultados/results.csv` (una fila por perfil y condición) se regenera con el paso 3 y no se versiona
(está en `.gitignore`).

## Pasos

```sh
# 0. Compilar el jar (una vez)
./mvnw -q -pl simulation -am package -DskipTests

# 1. Calibrar el flujo A con la consulta (~2 min con 16 núcleos; escribe calibrated.json y calibration.md)
java -jar simulation/target/sched-simulation.jar calibrate \
    --config simulation/experiments/base.json --out simulation/experiments --budget 20000 --seed 42

# 2. Análisis de sensibilidad sobre la configuración calibrada (segundos; escribe sensitivity.md y .json)
java -jar simulation/target/sched-simulation.jar sensitivity \
    --config simulation/experiments/calibrated.json --out simulation/experiments/resultados --replications 10

# 3. Experimento final con análisis estadístico (escribe results.csv, metadata.json, analysis.md y .json)
java -jar simulation/target/sched-simulation.jar experiment \
    --config simulation/experiments/calibrated.json --out simulation/experiments/resultados --profiles 500 --analyze
```

- `--budget` es el número aproximado de evaluaciones de la búsqueda (por defecto 8.000, ~1 min).
- El número de perfiles (500) sale del cálculo de potencia de `sensitivity.md` (sección 4: el mayor
  entre 500 y los necesarios para potencia 0,90 con un efecto de 5 puntos sobre el 20 %). Si ese
  cálculo recomienda más, usa ese valor en `--profiles` de los pasos 2 y 3.
- La calibración es determinista (misma semilla, mismo resultado). `experiment` y `sensitivity` usan
  `engine.timeMode = measured`: los conteos coinciden entre corridas y `t_total_s` varía por el reloj
  de la máquina (milisegundos del motor frente a minutos simulados). Con `timeMode: zero` en el JSON
  es exactamente reproducible.
- Qué significan las salidas y sus limitaciones: [`docs/simulador.md`](../../docs/simulador.md).
