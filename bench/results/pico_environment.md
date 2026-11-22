## Entorno

| | |
|---|---|
| Fecha | 2026-11-22 02:09 |
| CPU | AMD Ryzen 7 2700 Eight-Core Processor (16 hilos) |
| Memoria | 30 GB |
| Kernel | 7.0.0-44-generic |
| Modo de frecuencia | schedutil |
| Docker | 29.1.3 |
| Imagen | f77df74dbb75 |
| Java | openjdk version "25.0.5.1" 2026-11-17 LTS |
| k6 | k6 v2.4.0 (commit/dd9395906d, go1.27.3, linux/amd64) |
| Traefik | 3.7.19 |
| Núcleos | motor 1 `4,6`, motor 2 `8,10`, motor 3 `12,14`, Traefik `0,1,2,3`, NATS e ingesta `5,7`, k6 `8-15` (1 réplica) o `5,7,9,11,13,15` (3), ráfaga `7` |
| Motor | 2 CPUs y 1 GB por réplica; SCHED_SEARCH_TIMEOUT_MS=150, SCHED_MAX_COMBINATIONS=5 |
| Camino de k6 | Traefik, con 2 núcleos (4 hilos) |
| Forma de la carga | rampa 25 s, meseta 45 s, descenso 5 s; ráfaga de cupos: 10 grupos/s en la meseta |
