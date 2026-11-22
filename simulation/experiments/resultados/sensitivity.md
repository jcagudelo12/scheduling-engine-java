# Análisis de sensibilidad de la conclusión principal

- Configuración base: `simulation/experiments/calibrated.json`
- Semilla: 42; perfiles por corrida: 500; H0: r <= 20 % (unilateral, alfa = 0,05); IC bootstrap percentil 95 % con 2000 remuestreos; duración: 3 s
- r = (Ttotal_A - Ttotal_B) / Ttotal_A por perfil. Cada fila es un experimento pareado completo (oferta, perfiles, A y B) con la variación indicada y el análisis de `analyze` (Shapiro-Wilk, y t o Wilcoxon según el caso).

## 1. Variaciones

B no tiene datos empíricos: sus tiempos propios (revisar cada combinación y cada alternativa) son supuestos. Las acciones que A y B comparten usan los mismos parámetros; B paga además navegar y enviar cada grupo.

| Variación | Qué cambia | Media de r | IC 95 % bootstrap | p | Decisión | Ttotal A (s) | Ttotal B (s) | Dif. matriculadas B - A |
|---|---|---|---|---|---|---|---|---|
| base | configuración calibrada sin cambios | 10.8 % | [7.6 %, 13.8 %] | 1.0000 | **no se rechaza H0** | 2304 | 1748 | 0.21 |
| shared-x0.75 | tiempos de navegar, elegir, enviar, leer un rechazo, retirar y confirmar x0.75 | 8.1 % | [4.9 %, 11.1 %] | 1.0000 | **no se rechaza H0** | 1719 | 1343 | 0.19 |
| shared-x1.25 | tiempos de navegar, elegir, enviar, leer un rechazo, retirar y confirmar x1.25 | 11.5 % | [8.4 %, 14.5 %] | 1.0000 | **no se rechaza H0** | 2835 | 2135 | 0.20 |
| b-own-x2 | tiempos de revisar cada combinación y cada alternativa x2 | 2.9 % | [-0.5 %, 6.1 %] | 1.0000 | **no se rechaza H0** | 2304 | 1893 | 0.21 |
| b-own-x3 | tiempos de revisar cada combinación y cada alternativa x3 | -5.0 % | [-8.7 %, -1.5 %] | 1.0000 | **no se rechaza H0** | 2304 | 2044 | 0.21 |
| b-own-x5 | tiempos de revisar cada combinación y cada alternativa x5 | -21.8 % | [-26.3 %, -17.4 %] | 1.0000 | **no se rechaza H0** | 2304 | 2346 | 0.21 |
| b-review-double | B revisa el doble de combinaciones y de alternativas antes de elegir | 2.7 % | [-0.9 %, 6.1 %] | 1.0000 | **no se rechaza H0** | 2304 | 1887 | 0.22 |
| b-browse-x0.5 | B paga la mitad del tiempo de navegar la asignatura por grupo que inscribe (el motor lleva al grupo) | 39.2 % | [37.0 %, 41.3 %] | < 0.0001 | se rechaza H0 | 2304 | 1183 | 0.20 |
| b-browse-x0 | B paga nada del tiempo de navegar la asignatura por grupo que inscribe (el motor lleva al grupo) | 67.2 % | [65.7 %, 68.6 %] | < 0.0001 | se rechaza H0 | 2304 | 626 | 0.19 |
| slack-less | menos holgura: factor de holgura - 0,10 | 10.4 % | [7.3 %, 13.5 %] | 1.0000 | **no se rechaza H0** | 2262 | 1762 | 0.14 |
| slack-more | más holgura: factor de holgura + 0,20 | 7.9 % | [5.0 %, 10.7 %] | 1.0000 | **no se rechaza H0** | 2129 | 1750 | 0.10 |
| latency-0 | latencia entre el cambio de cupo y la réplica del motor: 0 s | 10.8 % | [7.7 %, 13.8 %] | 1.0000 | **no se rechaza H0** | 2304 | 1748 | 0.21 |
| latency-2 | latencia entre el cambio de cupo y la réplica del motor: 2 s | 10.8 % | [7.6 %, 13.8 %] | 1.0000 | **no se rechaza H0** | 2304 | 1748 | 0.21 |
| latency-10 | latencia entre el cambio de cupo y la réplica del motor: 10 s | 10.8 % | [7.7 %, 13.8 %] | 1.0000 | **no se rechaza H0** | 2304 | 1750 | 0.21 |
| latency-30 | latencia entre el cambio de cupo y la réplica del motor: 30 s | 10.6 % | [7.4 %, 13.6 %] | 1.0000 | **no se rechaza H0** | 2304 | 1753 | 0.21 |
| arrivals-x0.5 | tasa de llegadas por minuto x0.5 | 10.4 % | [7.2 %, 13.3 %] | 1.0000 | **no se rechaza H0** | 2289 | 1754 | 0.19 |
| arrivals-x2 | tasa de llegadas por minuto x2 | 10.1 % | [6.9 %, 13.0 %] | 1.0000 | **no se rechaza H0** | 2276 | 1748 | 0.18 |
| b-no-preferences | B sin preferencias de jornada (sin ventanas a evitar) | 4.7 % | [0.9 %, 8.3 %] | 1.0000 | **no se rechaza H0** | 2304 | 1843 | 0.23 |
| pessimistic | tiempos compartidos x0,75, tiempos propios de B x3, latencia 30 s, B sin preferencias | -21.5 % | [-26.1 %, -17.0 %] | 1.0000 | **no se rechaza H0** | 1719 | 1759 | 0.20 |

Se rechaza H0 en 2 de 19 variaciones. **No se rechaza H0 en:** base, shared-x0.75, shared-x1.25, b-own-x2, b-own-x3, b-own-x5, b-review-double, slack-less, slack-more, latency-0, latency-2, latency-10, latency-30, arrivals-x0.5, arrivals-x2, b-no-preferences, pessimistic.

La condición de diseño (Rtraslape = 0 en B) se cumple en todas las variaciones.

## 2. Punto de quiebre

Con la configuración base la reducción media ya es de 10.8 %, no mayor que el 20 %: no hay margen.

### Punto de quiebre de la navegación

Los tiempos propios de B no son lo que decide la conclusión: pesa más cuánto de la navegación de cada asignatura debe pagar B al inscribir cada grupo que le sugiere el motor (en la base, lo mismo que A: 219 s por grupo).

La reducción media sube del 10.8 % (B paga el 100 % de la navegación) al 67.2 % (B no paga navegación) y cruza el 20 % cuando B paga **83 %** de la navegación por grupo (unos 183 s frente a 219 s en A; bisección, 10 corridas): para rechazar H0 el motor tendría que ahorrar al menos el 17 % de ese tiempo, por ejemplo llevando directo al grupo a inscribir.

## 3. Réplicas (variabilidad de la simulación)

La configuración base con 10 semillas distintas (cada una genera su oferta, sus perfiles y sus llegadas): media de la reducción media = **12.6 %**, desviación estándar entre réplicas = **2.5 %**, mínimo 9.3 %, máximo 16.7 %; se rechaza H0 en 0 de 10 réplicas (p medio = 0.9538).

| Semilla | Reducción media |
|---|---|
| 42 | 10.8 % |
| 10049 | 11.0 % |
| 20056 | 16.7 % |
| 30063 | 13.0 % |
| 40070 | 9.9 % |
| 50077 | 9.3 % |
| 60084 | 13.4 % |
| 70091 | 16.0 % |
| 80098 | 12.0 % |
| 90105 | 14.2 % |

## 4. Tamaño de muestra

Con la desviación estándar observada de r en la base (0.3532) y un efecto mínimo de interés de 5 puntos sobre el 20 % (aproximación normal unilateral, alfa = 0,05, corrección de Guenther): **310 perfiles** para potencia 0,80 y **429** para 0,90. Con los 500 perfiles usados la potencia para ese efecto es 0.9358. Se recomienda usar 500 perfiles (el mayor entre 500 y el necesario para potencia 0,90, redondeado a la centena).

## 5. Limitaciones

- Las variaciones se hacen de a una (salvo el caso pesimista combinado): no exploran interacciones.
- El quiebre se calcula solo sobre los tiempos propios de B; no cubre errores de especificación del comportamiento (por ejemplo, que el estudiante de B no confíe en el motor y siga probando por su cuenta).
- El p-valor depende del número de perfiles; el tamaño del efecto y los intervalos son más informativos.
- El cómputo real del motor (`engine.timeMode = measured`) se suma al tiempo simulado; con varias corridas en paralelo puede inflarse algo por contención de CPU (milisegundos frente a minutos).
