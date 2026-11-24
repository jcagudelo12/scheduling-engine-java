# Análisis estadístico del experimento

- Archivo de resultados: `simulation/experiments/resultados/results.csv`
- Perfiles emparejados: 500
- Nivel de significación alfa = 0.05; mu0 = 0.2; semilla = 42; remuestreos bootstrap = 10000

## 1. Estadísticos descriptivos

| Condición | Variable | n | Media | Mediana | Desv. est. | RIC | Mín | Máx |
|---|---|---|---|---|---|---|---|---|
| A | Ttotal (s) | 500 | 2304.4263 | 1922.8085 | 1482.3379 | 1359.4135 | 120.5330 | 10928.3980 |
| A | Rtraslape | 500 | 1.9080 | 1.0000 | 2.5886 | 3.0000 | 0.0000 | 20.0000 |
| A | Rcupo | 500 | 0.2360 | 0.0000 | 0.5107 | 0.0000 | 0.0000 | 3.0000 |
| A | Rtotal | 500 | 2.1440 | 1.0000 | 2.6910 | 3.0000 | 0.0000 | 20.0000 |
| A | Asignaturas matriculadas | 500 | 4.1640 | 4.0000 | 1.3404 | 1.0000 | 0.0000 | 8.0000 |
| B | Ttotal (s) | 500 | 1748.3685 | 1643.7590 | 752.0002 | 928.0660 | 241.1590 | 5562.6150 |
| B | Rtraslape | 500 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| B | Rcupo | 500 | 0.6040 | 0.0000 | 0.8418 | 1.0000 | 0.0000 | 4.0000 |
| B | Rtotal | 500 | 0.6040 | 0.0000 | 0.8418 | 1.0000 | 0.0000 | 4.0000 |
| B | Asignaturas matriculadas | 500 | 4.3740 | 4.0000 | 1.2479 | 1.0000 | 1.0000 | 8.0000 |

A: reactivo; B: asistido. RIC: rango intercuartílico (cuantiles tipo 7).

## 2. Hipótesis principal: reducción del tiempo total

r = (Ttotal_A - Ttotal_B) / Ttotal_A por perfil. H0: la reducción es <= 20.0 %; H1: la reducción es > 20.0 % (unilateral, alfa = 0.05).

| n | Media de r | Mediana de r | Desv. est. | RIC | Mín | Máx |
|---|---|---|---|---|---|---|
| 500 | 0.1081 | 0.1516 | 0.3532 | 0.3977 | -1.3186 | 0.8507 |

Normalidad (Shapiro-Wilk) de reducción relativa r: W = 0.9722, p < 0.0001.

**Prueba elegida: Wilcoxon de rangos con signo.** Shapiro-Wilk rechaza la normalidad (W = 0.9722, p < 0.0001 <= 0.05); se usa Wilcoxon.

| Prueba | Estadístico | gl | p (unilateral) | Decisión | r (Z/√n) |
|---|---|---|---|---|---|
| Wilcoxon de rangos con signo | 46804.0000 | n/d | 1.0000 | no se rechaza H0 | -0.2189 |

Método del p: aproximación normal con corrección de continuidad. El estadístico es W+; el tamaño del efecto es r = Z/raíz(n) con el Z de la aproximación normal. La prueba compara la ubicación de r - mu0 con 0 (no es exactamente una prueba sobre la media).

| Intervalo | Inferior | Superior |
|---|---|---|
| IC 95 % de la reducción media (t) | 0.0771 | 0.1391 |
| IC 95 % bootstrap percentil de la media (10000 remuestreos, semilla 42) | 0.0770 | 0.1385 |

**Conclusión.** No se rechaza H0: no hay evidencia suficiente de que la reducción media del tiempo total sea mayor que el 20.0 % (media = 10.8 %, mediana = 15.2 %, p = 1.0000, r (Z/√n) = -0.2189). Advertencia: el p-valor depende del número de perfiles simulados (con n grande cualquier diferencia pequeña resulta significativa), por eso se acompaña del tamaño del efecto y de los intervalos de confianza.

## 3. Rtotal: comparación pareada A frente a B

Diferencia d = Rtotal_B - Rtotal_A por perfil. Bilateral: H0: media de d = 0. Unilateral: H1: Rtotal_B < Rtotal_A.

| n | Media de d | Mediana | Desv. est. | RIC | Mín | Máx |
|---|---|---|---|---|---|---|
| 500 | -1.5400 | -1.0000 | 2.7216 | 2.0000 | -20.0000 | 3.0000 |

Normalidad (Shapiro-Wilk) de diferencia Rtotal (B - A): W = 0.7723, p < 0.0001.

**Prueba elegida: Wilcoxon de rangos con signo.** Shapiro-Wilk rechaza la normalidad (W = 0.7723, p < 0.0001 <= 0.05); se usa Wilcoxon.

| Contraste | Estadístico | p | Decisión | r (Z/√n) |
|---|---|---|---|---|
| Bilateral | 15706.0000 | < 0.0001 | se rechaza H0 | -0.5803 |
| Unilateral (B < A) | 15706.0000 | < 0.0001 | se rechaza H0 | -0.5803 |

Método del p: aproximación normal con corrección de continuidad, corrección por empates, tratamiento de ceros.

IC 95 % (t) de la diferencia media: [-1.7791, -1.3009]. Las pruebas usan alfa = 0.05.

## 4. Rtraslape en la condición B (condición de diseño)

CUMPLE: Rtraslape = 0 en los 500 perfiles de la condición B.

## 5. Amenaza a la validez: asignaturas matriculadas

| Media A | Media B | Diferencia B - A | IC 95 % (t) |
|---|---|---|---|
| 4.1640 | 4.3740 | 0.2100 | [0.1285, 0.2915] |

**AVISO**: |diferencia| = 0.2100 supera 0.1: una condición matricula en promedio más asignaturas que la otra, lo que amenaza la comparación de Ttotal (más materias cuestan más tiempo).

Perfiles con Ttotal_A = 0 excluidos de r: 0

## 6. Reducción relativa por subgrupos (descriptivo, sin pruebas)

| Variable | Valor | n | Reducción media | Reducción mediana |
|---|---|---|---|---|
| n_courses | 1 | 9 | -0.2946 | -0.2110 |
| n_courses | 2 | 19 | -0.1428 | -0.0424 |
| n_courses | 3 | 9 | 0.0678 | 0.0139 |
| n_courses | 4 | 192 | 0.0668 | 0.1194 |
| n_courses | 5 | 171 | 0.1374 | 0.1771 |
| n_courses | 6 | 59 | 0.2299 | 0.2180 |
| n_courses | 7 | 35 | 0.2281 | 0.2913 |
| n_courses | 8 | 6 | 0.1547 | 0.2538 |
| window | 1 | 162 | 0.1216 | 0.1593 |
| window | 2 | 156 | 0.0166 | 0.0363 |
| window | 3 | 182 | 0.1745 | 0.2546 |
| program | MECATRONICA | 282 | 0.1029 | 0.1366 |
| program | OTROS | 23 | 0.1257 | 0.1483 |
| program | SISTEMAS | 195 | 0.1135 | 0.1646 |

## 7. Potencia y tamaño de muestra de la prueba principal

Con la desviación estándar observada de r (0.3532) y un efecto mínimo de interés de 5.0000 puntos sobre mu0 (aproximación normal unilateral, alfa = 0.05, corrección de Guenther): se necesitan **310 perfiles** para una potencia de 0,80 y **429** para 0,90. Con los **500 perfiles usados** la potencia para ese efecto es 0.9358.
