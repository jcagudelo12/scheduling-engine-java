// Pruebas de la lógica pura del cliente web: `node --test adapters/gateway-http/src/test/js/` (Node >= 22.7).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  DEFAULT_TIMEOUT_MS, TIMEOUT_MESSAGE,
  buildAlternativesRequest, buildCombinationsRequest, callEngine, cellKey, cellsToAvoid, conflictsText,
  courseColors, creditsText, decodeTokenPayload, errorMessage, hhmmToMinutes, minutesToHHMM, parseCourses,
  reasonText, selectionFrom, slotsText, timeoutFromQuery,
} from '../../main/resources/static/app/js/logic.js';

const b64url = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
const token = (payload) => `${b64url({ alg: 'EdDSA' })}.${b64url(payload)}.firma`;

test('minutos y HH:MM son inversos', () => {
  assert.equal(minutesToHHMM(360), '06:00');
  assert.equal(minutesToHHMM(1335), '22:15');
  assert.equal(hhmmToMinutes('06:00'), 360);
  assert.equal(hhmmToMinutes('7:30'), 450);
  assert.equal(hhmmToMinutes('24:00'), 1440);
  for (const bad of ['', 'abc', '25:00', '10:60', '24:01', '1030']) assert.equal(hhmmToMinutes(bad), null, bad);
  for (const m of [0, 59, 360, 1439]) assert.equal(hhmmToMinutes(minutesToHHMM(m)), m);
});

test('las celdas marcadas se unen en ventanas por día', () => {
  const cells = new Set([
    cellKey('monday', 6), cellKey('monday', 7), cellKey('monday', 9),
    cellKey('saturday', 21),
  ]);
  assert.deepEqual(cellsToAvoid(cells), [
    { day: 'monday', start_minute: 360, end_minute: 480 },
    { day: 'monday', start_minute: 540, end_minute: 600 },
    { day: 'saturday', start_minute: 1260, end_minute: 1320 },
  ]);
  assert.deepEqual(cellsToAvoid(new Set()), []);
});

test('las asignaturas se limpian, sin repetidos y señalando las inválidas', () => {
  assert.deepEqual(parseCourses(' MAT101, FIS101  MAT101;QUI-1 '), { courses: ['MAT101', 'FIS101', 'QUI-1'], invalid: [] });
  assert.deepEqual(parseCourses('MAT 101, a/b').invalid, ['a/b']);
  assert.deepEqual(parseCourses('').courses, []);
});

test('con token el cuerpo no lleva estudiante ni cursos; sin token sí', () => {
  const avoid = [{ day: 'monday', start_minute: 360, end_minute: 480 }];
  assert.deepEqual(buildCombinationsRequest({ studentId: 's', courses: ['A'], avoid, withToken: true }), { avoid });
  assert.deepEqual(buildCombinationsRequest({ studentId: 's', courses: ['A'], avoid: [], withToken: false }),
    { student_id: 's', eligible_courses: ['A'] });
});

test('alternativas: selection y solo los inscritos que están en la selección', () => {
  const body = buildAlternativesRequest({
    studentId: 's', courses: ['A', 'B'], avoid: [], withToken: false,
    selection: { A: 'A-01', B: 'B-02' }, enrolled: ['A-01', 'Z-09'],
  });
  assert.deepEqual(body, {
    student_id: 's', eligible_courses: ['A', 'B'], selection: { A: 'A-01', B: 'B-02' }, enrolled: ['A-01'],
  });
  const none = buildAlternativesRequest({ withToken: true, selection: { A: 'A-01' }, enrolled: [] });
  assert.equal('enrolled' in none, false);
  assert.deepEqual(selectionFrom([{ course_id: 'A', section_id: 'A-01' }]), { A: 'A-01' });
});

test('el payload del token se decodifica solo para mostrarlo', () => {
  const info = decodeTokenPayload(token({ sub: 'est-001', courses: ['MAT101', 'FIS101'], exp: 2000 }), 1000);
  assert.deepEqual(info, { ok: true, studentId: 'est-001', courses: ['MAT101', 'FIS101'], exp: 2000, expired: false });
  assert.equal(decodeTokenPayload(token({ sub: 'x', exp: 10 }), 1000).expired, true);
  // con tildes (UTF-8) y sin relleno base64
  assert.equal(decodeTokenPayload(token({ sub: 'estudiante-ñ', courses: [] })).studentId, 'estudiante-ñ');
  for (const bad of ['', 'a.b', 'a.!!.c', `x.${b64url('texto')}.y`, `x.${b64url(null)}.y`]) {
    assert.deepEqual(decodeTokenPayload(bad), { ok: false }, bad);
  }
});

test('cada razón tiene su explicación', () => {
  assert.match(reasonText({ course: 'MAT', from: 'MAT-01', to: 'MAT-02', reason: 'no_seats' }), /MAT-01 se quedó sin cupo.*MAT-02/);
  assert.match(reasonText({ course: 'MAT', from: 'MAT-01', to: 'MAT-02', reason: 'clash' }), /se cruza/);
  assert.match(reasonText({ course: 'MAT', from: 'MAT-01', to: 'MAT-02', reason: 'rearranged' }), /acomodar/);
  assert.match(reasonText({ course: 'MAT', from: null, to: 'MAT-02', reason: 'not_selected' }), /aún no tenías grupo/);
  assert.match(reasonText({ course: 'MAT', from: null, to: 'MAT-02', reason: 'otra' }), /sin grupo/);
});

test('conflictos, créditos y franjas en texto', () => {
  assert.deepEqual(conflictsText({
    without_seats: [{ course: 'MAT', section: 'MAT-01' }], clashes: [{ first: 'A-01', second: 'B-01' }],
  }), ['Sin cupo: el grupo MAT-01 de MAT.', 'Cruce de horario entre A-01 y B-01.']);
  assert.deepEqual(conflictsText(undefined), []);
  assert.equal(creditsText(7, 18), 'Créditos: 7 de 18');
  assert.match(creditsText(7, 0), /no tiene tope/);
  assert.equal(slotsText([{ day: 'monday', start_minute: 360, end_minute: 480 }, { day: 'wednesday', start_minute: 360, end_minute: 480 }]),
    'Lun 06:00–08:00, Mié 06:00–08:00');
});

test('mensajes para 400, 401, 413, 422 y 503', () => {
  assert.match(errorMessage({ status: 401, body: '{"error":"unauthorized"}' }).detail, /token/);
  assert.equal(errorMessage({ status: 400, body: '{"error":"invalid_request","message":"se requiere al menos una asignatura"}' }).detail,
    'se requiere al menos una asignatura');
  assert.match(errorMessage({ status: 422, body: '{"error":"credit_limit_exceeded","total_credits":21,"max_credits":18}' }).detail, /21 créditos.*18/);
  assert.match(errorMessage({ status: 503, body: 'el catálogo aún no se ha cargado' }).title, /no disponible/);
  assert.match(errorMessage({ status: 413, body: '' }).title, /grande/);
  assert.match(errorMessage({ status: 500, body: '<html>' }).detail, /500/);
});

test('el timeout sale de ?timeout= y cae al valor por defecto si no es válido', () => {
  assert.equal(timeoutFromQuery(''), DEFAULT_TIMEOUT_MS);
  assert.equal(timeoutFromQuery('?timeout=1500'), 1500);
  for (const bad of ['?timeout=abc', '?timeout=-5', '?timeout=0', '?timeout=999999', '?timeout=1.5']) {
    assert.equal(timeoutFromQuery(bad), DEFAULT_TIMEOUT_MS, bad);
  }
});

test('los colores son estables y reparten una paleta circular', () => {
  const colors = courseColors(['FIS', 'MAT', 'FIS', 'ALG'], 2);
  assert.deepEqual([...colors], [['ALG', 0], ['FIS', 1], ['MAT', 0]]);
});

// Red: un fetch falso que respeta la señal de aborto, como el real.
function fakeFetch(behaviour) {
  return (url, init) => new Promise((resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new DOMException('abortado', 'AbortError')));
    behaviour(resolve, reject, init);
  });
}
const response = (status, text, headers = {}) => ({
  ok: status >= 200 && status < 300, status, text: async () => text, headers: new Headers(headers),
});
const call = (fetchImpl, extra = {}) => callEngine({
  fetchImpl, url: '/combinations', token: 'T', body: { a: 1 }, timeoutMs: 40, requestId: 'rid-1', ...extra,
});

test('una respuesta a tiempo trae los datos y envía token e id de solicitud', async () => {
  let sent;
  const result = await call(fakeFetch((resolve, _r, init) => { sent = init; resolve(response(200, '{"ok":true}', { 'X-Request-Id': 'del-servidor' })); }));
  assert.deepEqual(result, { kind: 'ok', data: { ok: true }, requestId: 'del-servidor' });
  assert.equal(sent.headers.Authorization, 'Bearer T');
  assert.equal(sent.headers['X-Request-Id'], 'rid-1');
  assert.equal(sent.body, '{"a":1}');
  const anonymous = await call(fakeFetch((resolve, _r, init) => { sent = init; resolve(response(200, '{}')); }), { token: '' });
  assert.equal('Authorization' in sent.headers, false);
  assert.equal(anonymous.requestId, 'rid-1');
});

test('si vence el plazo se aborta y se informa un timeout (E5)', async () => {
  const started = Date.now();
  const result = await call(fakeFetch(() => {}));
  assert.equal(result.kind, 'timeout');
  assert.ok(Date.now() - started < 500);
  assert.match(TIMEOUT_MESSAGE, /continúa tu matrícula en la plataforma institucional/);
});

test('también vence si el cuerpo llega demasiado lento', async () => {
  const slowBody = fakeFetch((resolve, _r, init) => resolve({
    ok: true, status: 200, headers: new Headers(),
    text: () => new Promise((_ok, reject) => init.signal.addEventListener('abort', () => reject(new Error('abortado')))),
  }));
  assert.equal((await call(slowBody)).kind, 'timeout');
});

test('una falla de red no es un timeout, y un error HTTP conserva estado y cuerpo', async () => {
  assert.equal((await call(fakeFetch((_ok, reject) => reject(new TypeError('red caída'))))).kind, 'network');
  const http = await call(fakeFetch((resolve) => resolve(response(422, '{"error":"credit_limit_exceeded"}'))));
  assert.deepEqual(http, { kind: 'http', status: 422, body: '{"error":"credit_limit_exceeded"}', requestId: 'rid-1' });
});

test('se limpia el temporizador cuando responde a tiempo', async () => {
  const cleared = [];
  const timers = { setTimeout: (fn, ms) => ({ fn, ms }), clearTimeout: (t) => cleared.push(t) };
  await call(fakeFetch((resolve) => resolve(response(200, '{}'))), { timers });
  assert.equal(cleared.length, 1);
  assert.equal(cleared[0].ms, 40);
});
