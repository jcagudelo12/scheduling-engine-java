## Entorno

| | |
|---|---|
| Fecha | 2026-10-06 21:40 |
| CPU | AMD Ryzen 7 2700 Eight-Core Processor (16 hilos) |
| Kernel | 7.0.0-38-generic |
| Modo de frecuencia | schedutil |
| Docker | 29.1.3 |
| Imagen | 8e31bc9ca1ba (591MB) |
| Java | openjdk version "25.0.4.1" 2026-08-18 LTS |
| k6 | k6 v2.3.0 (commit/e088784614, go1.27.1, linux/amd64) |
| Núcleos | motor `4,6`, NATS `2,3`, k6 `8-15` |
| Combinaciones por respuesta | hasta 5 |
| Repeticiones | 3; 30s por tasa, 5 s de pausa entre tasas |

## Arranque y reposo (mediana)

| | Java |
|---|---|
| Arranque hasta `/ready` (ms) | 4264 |
| Memoria en reposo con catálogo (MiB) | 147 |
| CPUs detectadas por el motor | 2 |

## Primeras solicitudes en frío (200/s, 20 s)

| | RPS logrado | p50 (ms) | p95 (ms) | p99 (ms) | p99 mín–máx | máx (ms) | errores | descartadas | CPU prom. | memoria máx. (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
| java | 200 | 1.10 | 2.32 | 3.81 | 3.72–4.00 | 10 | 0.0 % | 0 | 48 % | 181 |

## 1000 solicitudes/s

| | RPS logrado | p50 (ms) | p95 (ms) | p99 (ms) | p99 mín–máx | máx (ms) | errores | descartadas | CPU prom. | memoria máx. (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
| java | 1000 | 0.48 | 0.83 | 1.96 | 1.68–3.02 | 10 | 0.0 % | 0 | 50 % | 190 |

## 2000 solicitudes/s

| | RPS logrado | p50 (ms) | p95 (ms) | p99 (ms) | p99 mín–máx | máx (ms) | errores | descartadas | CPU prom. | memoria máx. (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
| java | 2000 | 0.39 | 0.64 | 1.47 | 1.44–1.64 | 13 | 0.0 % | 0 | 50 % | 205 |

## 4000 solicitudes/s

| | RPS logrado | p50 (ms) | p95 (ms) | p99 (ms) | p99 mín–máx | máx (ms) | errores | descartadas | CPU prom. | memoria máx. (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
| java | 4000 | 0.27 | 0.70 | 2.56 | 2.42–2.64 | 22 | 0.0 % | 0 | 68 % | 234 |

## 6000 solicitudes/s

| | RPS logrado | p50 (ms) | p95 (ms) | p99 (ms) | p99 mín–máx | máx (ms) | errores | descartadas | CPU prom. | memoria máx. (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
| java | 5999 | 0.25 | 0.98 | 5.11 | 4.16–9.30 | 42 | 0.0 % | 0 | 93 % | 282 |

## 8000 solicitudes/s

| | RPS logrado | p50 (ms) | p95 (ms) | p99 (ms) | p99 mín–máx | máx (ms) | errores | descartadas | CPU prom. | memoria máx. (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
| java | 7999 | 0.24 | 2.62 | 21 | 21–26 | 159 | 0.0 % | 0 | 120 % | 340 |

Mediana de 3 repeticiones. CPU sobre 100 % por núcleo asignado.
