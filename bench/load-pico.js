// Prueba del pico de matrícula con k6: la apertura de una ventana (rampa, meseta y descenso de la
// tasa de llegada) con la mezcla de tráfico del portal y, aparte, la ráfaga de cupos de `seat-storm`.
//
// Mezcla: ~80 % POST /combinations (con y sin avoid, y algunos perfiles peor caso 7x8) y ~20 %
// POST /alternatives (selección con un grupo lleno y `enrolled`). Los cuerpos salen de los archivos
// que genera `generate_scenario.py --profile itm`, recorridos en orden (reproducible).
//
// Medidas (con la etiqueta phase = ramp | hold | down; las que importan son las de `hold`; la sonda de E1 usa phase = probe):
//   - http_req_duration, http_req_failed (422 es respuesta válida de negocio, no un error).
//   - incomplete: respuestas con "complete": false (ADR 0009).
//   - schedule_full: cada combinación devuelta que contiene un grupo ya lleno según la ráfaga de
//     cupos (E1: < 2 %), y full_group_age_ms: cuánto hacía que se había vaciado ese grupo (E1: ≤ 5 s).
//
// E1 sin hablar con la ráfaga: `seat-storm` vacía el grupo i de DRAIN_FILE en STORM_AT_MS + i /
// STORM_RATE (segundos), así que k6 sabe qué grupos están llenos en cada instante.
//
// Variables: TARGET, START_MS (hora absoluta de inicio de la rampa), RATE (pico, solicitudes/s), RAMP, HOLD, DOWN (segundos), ALT_SHARE (0,2),
// COMBOS_FILE, ALTS_FILE, TOKENS_COMBOS_FILE y TOKENS_ALTS_FILE (opcionales: con SCHED_AUTH=jwt, tokens Bearer en el
// mismo orden que los cuerpos; los genera profile-tokens.py), DRAIN_FILE, STORM_AT_MS, STORM_RATE (0 = sin ráfaga), E1_SAMPLE (1 de cada n
// respuestas de /combinations se examina en detalle), OUT (resumen JSON).
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const TARGET = __ENV.TARGET || 'http://localhost:18080';
const RATE = parseInt(__ENV.RATE || '1000');
const RAMP = parseInt(__ENV.RAMP || '40');
const HOLD = parseInt(__ENV.HOLD || '90');
// Segundos iniciales de la meseta que se etiquetan phase = warm (calentamiento de la JVM) y quedan fuera de las
// métricas de `hold`. 0 (por defecto) = toda la meseta es `hold`, como antes.
const WARM = parseInt(__ENV.WARM || '0');
const DOWN = parseInt(__ENV.DOWN || '20');
const ALT_SHARE = parseFloat(__ENV.ALT_SHARE || '0.2');
// Hora absoluta (ms) en que empieza la rampa; las fases y la ráfaga de cupos se miden contra ella.
const START_MS = parseInt(__ENV.START_MS || '0');
const STORM_AT_MS = parseInt(__ENV.STORM_AT_MS || '0');
const STORM_RATE = parseFloat(__ENV.STORM_RATE || '0');
const PROBE_RATE = parseFloat(__ENV.PROBE_RATE || '100');
const E1_SAMPLE = parseInt(__ENV.E1_SAMPLE || '3');

// SLICE_S > 0: además etiqueta cada solicitud con slice = n.º de tramo de SLICE_S segundos desde START_MS, para ver la latencia en el tiempo
// (http_req_duration{slice:N} en el resumen). 0 = sin etiqueta.
const SLICE_S = parseInt(__ENV.SLICE_S || '0');

const combos = new SharedArray('combos', () =>
  JSON.parse(open('./' + (__ENV.COMBOS_FILE || 'students.json'))).map((s) => JSON.stringify(s)),
);
const alts = new SharedArray('alts', () =>
  ALT_SHARE > 0 ? JSON.parse(open('./' + (__ENV.ALTS_FILE || 'alternatives.json'))).map((s) => JSON.stringify(s)) : [],
);
// Tokens Bearer (opcional), en paralelo a los cuerpos: con ellos el motor verifica la firma EdDSA en cada solicitud.
const tokensCombos = new SharedArray('tokens-combos', () =>
  __ENV.TOKENS_COMBOS_FILE ? JSON.parse(open(__ENV.TOKENS_COMBOS_FILE)) : [],
);
const tokensAlts = new SharedArray('tokens-alts', () =>
  __ENV.TOKENS_ALTS_FILE ? JSON.parse(open(__ENV.TOKENS_ALTS_FILE)) : [],
);
const drain = new SharedArray('drain', () =>
  STORM_RATE > 0
    ? open('./' + (__ENV.DRAIN_FILE || 'drain.txt')).split('\n').map((l) => l.trim()).filter((l) => l)
    : [],
);
// Grupo → hora (ms) en que la ráfaga lo vacía.
const drainedAt = new Map();
drain.forEach((id, i) => drainedAt.set(id, STORM_AT_MS + Math.round((i * 1000) / STORM_RATE)));

const incomplete = new Rate('incomplete');
const stale = new Rate('stale');
const rejected422 = new Rate('rejected_422');
const scheduleFull = new Rate('schedule_full');
const scheduleFull5s = new Rate('schedule_full_over_5s');
const fullAge = new Trend('full_group_age_ms', false);
const schedulesSeen = new Counter('schedules_seen');
const schedulesFull = new Counter('schedules_full');
// Sonda de E1: ver probe().
const probeOffered = new Rate('probe_offered');
const probeAge = new Trend('probe_offered_age_ms', false);

const sliceThresholds = {};
if (SLICE_S > 0) {
  for (let n = 0; n <= Math.ceil((RAMP + HOLD + DOWN) / SLICE_S); n++) {
    sliceThresholds['http_req_duration{slice:' + n + '}'] = ['p(99)>=0'];
  }
}

http.setResponseCallback(http.expectedStatuses(200, 422));

export const options = {
  scenarios: {
    pico: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(RATE / 10)),
      timeUnit: '1s',
      stages: [
        { target: RATE, duration: RAMP + 's' },
        { target: RATE, duration: HOLD + 's' },
        { target: 0, duration: DOWN + 's' },
      ],
      preAllocatedVUs: Math.max(100, Math.round(RATE / 10)),
      // Tope de usuarios virtuales para no agotar la memoria del generador.
      maxVUs: Math.min(10000, Math.max(500, RATE * 2)),
    },
    // Sonda de E1: pregunta por un curso cuyo grupo la ráfaga acaba de vaciar y mide cuánto tarda en
    // dejar de aparecer. Solo en la meseta (la ráfaga corre entonces) y solo si hay ráfaga.
    ...(STORM_RATE > 0
      ? {
          sonda: {
            executor: 'constant-arrival-rate',
            exec: 'probe',
            rate: PROBE_RATE,
            timeUnit: '1s',
            duration: HOLD + 's',
            startTime: RAMP + 's',
            preAllocatedVUs: 20,
            maxVUs: 100,
          },
        }
      : {}),
  },
  thresholds: {
    ...sliceThresholds,
    // E1 y E2: p99 ≤ 200 ms y casi sin errores en la meseta.
    'http_req_duration{phase:hold}': ['p(99)<200'],
    'http_req_duration{phase:hold,endpoint:combinations}': ['p(99)<200'],
    'http_req_duration{phase:hold,endpoint:alternatives}': ['p(99)<200'],
    'http_req_failed{phase:hold}': ['rate<0.001'],
    // E1: menos del 2 % de las combinaciones entregadas contienen un grupo ya lleno.
    'schedule_full{phase:hold}': ['rate<0.02'],
    // Informativas (crean las series de la meseta para el resumen).
    'incomplete{phase:hold}': ['rate>=0'],
    'incomplete{phase:hold,endpoint:combinations}': ['rate>=0'],
    'incomplete{phase:hold,endpoint:alternatives}': ['rate>=0'],
    'stale{phase:hold}': ['rate>=0'],
    'rejected_422{phase:hold}': ['rate>=0'],
    'schedule_full_over_5s{phase:hold}': ['rate>=0'],
    'full_group_age_ms{phase:hold}': ['p(99)>=0'],
    'schedules_seen{phase:hold}': ['count>=0'],
    'schedules_full{phase:hold}': ['count>=0'],
    'http_reqs{phase:hold}': ['count>=0'],
    'http_reqs{phase:hold,endpoint:combinations}': ['count>=0'],
    'http_reqs{phase:hold,endpoint:alternatives}': ['count>=0'],
    'http_req_duration{phase:ramp}': ['p(99)>=0'],
    'http_req_duration{phase:warm}': ['p(99)>=0'],
    // E1: ninguna respuesta ofrece un grupo vaciado más de 5 s después.
    'probe_offered_age_ms{phase:probe}': ['max<5000'],
    'probe_offered{phase:probe}': ['rate>=0'],
    'http_reqs{phase:probe}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Examina las combinaciones de una respuesta de /combinations frente a los grupos ya vaciados.
function examine(body, now, tags) {
  const data = JSON.parse(body);
  for (const schedule of data.schedules || []) {
    let full = false;
    let over5s = false;
    for (const s of schedule.sections) {
      const at = drainedAt.get(s.section_id);
      if (at !== undefined && now >= at) {
        full = true;
        fullAge.add(now - at, tags);
        if (now - at > 5000) {
          over5s = true;
        }
      }
    }
    schedulesSeen.add(1, tags);
    scheduleFull.add(full, tags);
    scheduleFull5s.add(over5s, tags);
    if (full) {
      schedulesFull.add(1, tags);
    }
  }
}

// Espera hasta START_MS para que k6 y la ráfaga de cupos (otro proceso) arranquen alineados.
export function setup() {
  const wait = START_MS - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  } else if (START_MS > 0) {
    console.warn('k6 arrancó ' + -wait + ' ms después de START_MS: la ráfaga de cupos queda desfasada');
  }
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const t = START_MS > 0 ? (Date.now() - START_MS) / 1000 : exec.instance.currentTestRunDuration / 1000;
  const phase = t < RAMP ? 'ramp' : t < RAMP + WARM ? 'warm' : t < RAMP + HOLD ? 'hold' : 'down';
  const isAlt = ALT_SHARE > 0 && Math.floor((i + 1) * ALT_SHARE) > Math.floor(i * ALT_SHARE);
  const endpoint = isAlt ? 'alternatives' : 'combinations';
  const tags = { phase, endpoint };
  if (SLICE_S > 0) {
    tags.slice = String(Math.floor(t / SLICE_S));
  }
  const body = isAlt ? alts[i % alts.length] : combos[i % combos.length];
  const headers = { 'content-type': 'application/json' };
  const tokens = isAlt ? tokensAlts : tokensCombos;
  if (tokens.length > 0) {
    headers.authorization = 'Bearer ' + tokens[(isAlt ? i % alts.length : i % combos.length) % tokens.length];
  }

  const res = http.post(TARGET + '/' + endpoint, body, {
    headers,
    tags,
  });
  const now = Date.now();
  if (res.status === 422) {
    rejected422.add(true, tags);
    return;
  }
  rejected422.add(false, tags);
  if (res.status !== 200) {
    return;
  }
  const text = res.body;
  incomplete.add(text.includes('"complete":false'), tags);
  stale.add(text.includes('"stale":true'), tags);
  if (!isAlt && drainedAt.size > 0 && i % E1_SAMPLE === 0) {
    examine(text, now, tags);
  }
}

// Sonda de E1: de los últimos 10 grupos vaciados toma uno (de los cinco primeros de su curso, que
// siempre salen en una respuesta de un solo curso mientras tengan cupo) y consulta ese curso solo.
// Si el grupo sigue en la respuesta, `probe_offered` cuenta y `probe_offered_age_ms` guarda
// cuánto hacía que se vació: el máximo es el tiempo hasta que el motor deja de ofrecerlo.
export function probe() {
  const last = Math.floor(((Date.now() - STORM_AT_MS) * STORM_RATE) / 1000);
  const j = last - (exec.scenario.iterationInTest % 10);
  if (j < 0 || j >= drain.length) {
    return;
  }
  const id = drain[j];
  if (parseInt(id.split('-')[1]) >= 5) {
    return;
  }
  const tags = { phase: 'probe', endpoint: 'probe' };
  const res = http.post(
    TARGET + '/combinations',
    JSON.stringify({ student_id: 'sonda', eligible_courses: [id.split('-')[0]] }),
    { headers: { 'content-type': 'application/json' }, tags },
  );
  const now = Date.now();
  const age = now - drainedAt.get(id);
  if (res.status !== 200 || age < 0) {
    return;
  }
  const offered = res.body.includes('"section_id":"' + id + '"');
  probeOffered.add(offered, tags);
  if (offered) {
    probeAge.add(age, tags);
  }
}

// Resumen en JSON para bench/report_pico.py.
export function handleSummary(data) {
  return { [__ENV.OUT || 'summary-pico.json']: JSON.stringify(data) };
}
