// Lógica pura del cliente de demostración: sin DOM ni red propia, para probarla con `node --test`.
// Todo lo que toca el navegador (DOM, sessionStorage, fetch real) vive en main.js.

export const DAYS = [
  { key: 'monday', label: 'Lunes', short: 'Lun' },
  { key: 'tuesday', label: 'Martes', short: 'Mar' },
  { key: 'wednesday', label: 'Miércoles', short: 'Mié' },
  { key: 'thursday', label: 'Jueves', short: 'Jue' },
  { key: 'friday', label: 'Viernes', short: 'Vie' },
  { key: 'saturday', label: 'Sábado', short: 'Sáb' },
];

export const START_HOUR = 6;
export const END_HOUR = 22;
export const DEFAULT_TIMEOUT_MS = 800;
/** Aviso E5: el motor es una ayuda no crítica; el estudiante sigue en la plataforma institucional. */
export const TIMEOUT_MESSAGE =
  'El asistente no respondió a tiempo; continúa tu matrícula en la plataforma institucional.';
const MAX_AVOID = 42;
const ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

const dayByKey = new Map(DAYS.map((d) => [d.key, d]));

// Tiempo

/** 450 -> "07:30". */
export function minutesToHHMM(minutes) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** "07:30" -> 450; null si no es una hora válida (00:00 a 24:00). */
export function hhmmToMinutes(text) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(text).trim());
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (m > 59 || h > 24 || (h === 24 && m !== 0)) return null;
  return h * 60 + m;
}

/** "Lun 06:00–08:00, Mié 06:00–08:00". */
export function slotsText(slots) {
  return slots
    .map((s) => `${dayByKey.get(s.day)?.short ?? s.day} ${minutesToHHMM(s.start_minute)}–${minutesToHHMM(s.end_minute)}`)
    .join(', ');
}

// Franjas a evitar

export const cellKey = (day, hour) => `${day}:${hour}`;

/**
 * Celdas marcadas (conjunto de "día:hora", una celda = 1 h) a ventanas `avoid`: las horas
 * consecutivas de un mismo día se unen en una sola ventana.
 */
export function cellsToAvoid(cells) {
  const windows = [];
  for (const day of DAYS) {
    let start = null;
    for (let hour = START_HOUR; hour <= END_HOUR; hour++) {
      const marked = hour < END_HOUR && cells.has(cellKey(day.key, hour));
      if (marked && start === null) start = hour;
      if (!marked && start !== null) {
        windows.push({ day: day.key, start_minute: start * 60, end_minute: hour * 60 });
        start = null;
      }
    }
  }
  return windows;
}

/** Cantidad máxima de ventanas que acepta el motor por defecto (SCHED_MAX_AVOID). */
export const tooManyWindows = (windows) => windows.length > MAX_AVOID;

// Asignaturas

/** "MAT101, FIS101 mat101" -> { courses: ['MAT101','FIS101'], invalid: [] } (sin repetidos). */
export function parseCourses(text) {
  const courses = [];
  const invalid = [];
  for (const raw of String(text).split(/[\s,;]+/)) {
    if (!raw) continue;
    if (!ID_PATTERN.test(raw)) invalid.push(raw);
    else if (!courses.includes(raw)) courses.push(raw);
  }
  return { courses, invalid };
}

// Solicitudes

/**
 * Cuerpo de /combinations. Con token, el estudiante y los cursos salen del token firmado: no se
 * envían. Sin token (SCHED_AUTH=none) van en el cuerpo.
 */
export function buildCombinationsRequest({ studentId, courses, avoid, withToken }) {
  const body = {};
  if (!withToken) {
    body.student_id = studentId;
    body.eligible_courses = courses;
  }
  if (avoid && avoid.length > 0) body.avoid = avoid;
  return body;
}

/** `selection` es { curso: grupo }; `enrolled`, lista de grupos que deben estar en `selection`. */
export function buildAlternativesRequest({ studentId, courses, avoid, withToken, selection, enrolled }) {
  const body = buildCombinationsRequest({ studentId, courses, avoid, withToken });
  body.selection = { ...selection };
  const chosen = new Set(Object.values(selection));
  const kept = (enrolled ?? []).filter((section) => chosen.has(section));
  if (kept.length > 0) body.enrolled = kept;
  return body;
}

/** Selección { curso: grupo } a partir de las secciones de una combinación o alternativa. */
export function selectionFrom(sections) {
  return Object.fromEntries(sections.map((s) => [s.course_id, s.section_id]));
}

// Token

function base64UrlToText(segment) {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Decodifica el payload SOLO para mostrarlo. No verifica la firma ni se debe confiar en él: el
 * servidor es quien valida el token.
 */
export function decodeTokenPayload(token, nowSeconds = Date.now() / 1000) {
  try {
    const parts = String(token).trim().split('.');
    if (parts.length !== 3) return { ok: false };
    const payload = JSON.parse(base64UrlToText(parts[1]));
    if (payload === null || typeof payload !== 'object') return { ok: false };
    const courses = Array.isArray(payload.courses) ? payload.courses.filter((c) => typeof c === 'string') : [];
    const exp = typeof payload.exp === 'number' ? payload.exp : null;
    return {
      ok: true,
      studentId: typeof payload.sub === 'string' ? payload.sub : '',
      courses,
      exp,
      expired: exp !== null && exp < nowSeconds,
    };
  } catch {
    return { ok: false };
  }
}

// Texto para el estudiante

const courseOf = (change) => change.course;

/** Explicación en lenguaje natural de un cambio de /alternatives según su `reason`. */
export function reasonText(change) {
  const course = courseOf(change);
  switch (change.reason) {
    case 'no_seats':
      return `${course}: el grupo ${change.from} se quedó sin cupo; te proponemos el ${change.to}.`;
    case 'clash':
      return `${course}: el grupo ${change.from} se cruza con otro de tu selección; te proponemos el ${change.to}.`;
    case 'rearranged':
      return `${course}: cambia del ${change.from} al ${change.to} para acomodar los demás cambios (el ${change.from} seguía siendo válido).`;
    case 'not_selected':
      return `${course}: aún no tenías grupo; te proponemos el ${change.to}.`;
    default:
      return `${course}: cambia ${change.from ?? 'sin grupo'} por ${change.to}.`;
  }
}

export function creditsText(totalCredits, maxCredits) {
  return maxCredits > 0
    ? `Créditos: ${totalCredits} de ${maxCredits}`
    : `Créditos: ${totalCredits} (el periodo no tiene tope)`;
}

/** Conflictos de la selección en frases. */
export function conflictsText(conflicts) {
  const lines = [];
  for (const item of conflicts?.without_seats ?? []) {
    lines.push(`Sin cupo: el grupo ${item.section} de ${item.course}.`);
  }
  for (const pair of conflicts?.clashes ?? []) {
    lines.push(`Cruce de horario entre ${pair.first} y ${pair.second}.`);
  }
  return lines;
}

/**
 * Mensaje claro para una respuesta que no fue 200. `body` es el texto crudo (el 503 puede no ser
 * JSON). Devuelve { title, detail }.
 */
export function errorMessage({ status, body }) {
  let json = null;
  try {
    json = JSON.parse(body);
  } catch {
    json = null;
  }
  switch (status) {
    case 401:
      return { title: 'No autorizado', detail: 'El token falta, es inválido o venció. Pega un token nuevo e inténtalo otra vez.' };
    case 400:
      return { title: 'Solicitud no válida', detail: json?.message ?? 'Revisa las asignaturas y las franjas elegidas.' };
    case 413:
      return { title: 'Solicitud demasiado grande', detail: 'Reduce las asignaturas o las franjas a evitar.' };
    case 422:
      return {
        title: 'Demasiados créditos',
        detail:
          json?.error === 'credit_limit_exceeded'
            ? `Las asignaturas suman ${json.total_credits} créditos y el tope del periodo es ${json.max_credits}. Quita alguna asignatura.`
            : 'Las asignaturas superan el tope de créditos del periodo.',
      };
    case 503:
      return { title: 'Asistente no disponible', detail: 'El catálogo aún no está cargado o un servicio del motor no responde. Inténtalo en un momento.' };
    default:
      return { title: 'Error inesperado', detail: `El asistente respondió con el código ${status}.` };
  }
}

// Red

/** Id para `X-Request-Id`: letras, dígitos y guiones (UUID). */
export function newRequestId(cryptoImpl = globalThis.crypto) {
  return cryptoImpl.randomUUID();
}

/** `?timeout=1200` -> 1200 ms; si falta o no es un entero entre 1 y 60000, el valor por defecto. */
export function timeoutFromQuery(search, fallback = DEFAULT_TIMEOUT_MS) {
  const raw = new URLSearchParams(search).get('timeout');
  if (raw === null || !/^\d+$/.test(raw)) return fallback;
  const value = Number(raw);
  return value >= 1 && value <= 60000 ? value : fallback;
}

/**
 * Una llamada JSON con tiempo máximo (E5). Nunca lanza: devuelve
 * { kind: 'ok', data, requestId } | { kind: 'http', status, body, requestId } | { kind: 'timeout' | 'network', requestId }.
 * El plazo cubre también la lectura del cuerpo.
 */
export async function callEngine({ fetchImpl, url, token, body, timeoutMs, requestId, timers = globalThis }) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = timers.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const headers = { 'Content-Type': 'application/json', 'X-Request-Id': requestId };
  if (token) headers.Authorization = `Bearer ${token}`;
  try {
    const response = await fetchImpl(url, { method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal });
    const text = await response.text();
    const id = response.headers?.get?.('X-Request-Id') || requestId;
    if (response.ok) return { kind: 'ok', data: JSON.parse(text), requestId: id };
    return { kind: 'http', status: response.status, body: text, requestId: id };
  } catch {
    return { kind: timedOut ? 'timeout' : 'network', requestId };
  } finally {
    timers.clearTimeout(timer);
  }
}

// Presentación

/** Índice de color estable por asignatura (orden alfabético dentro de la lista dada). */
export function courseColors(courseIds, paletteSize = 8) {
  const sorted = [...new Set(courseIds)].sort();
  return new Map(sorted.map((id, i) => [id, i % paletteSize]));
}
