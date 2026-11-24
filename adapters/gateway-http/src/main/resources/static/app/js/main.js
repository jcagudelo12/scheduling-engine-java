// Cliente de demostración del motor de horarios. Solo DOM y red del navegador; la lógica pura
// (solicitudes, textos, token, tiempo máximo) está en logic.js y se prueba con `node --test`.
import {
  DAYS, START_HOUR, END_HOUR, TIMEOUT_MESSAGE,
  buildAlternativesRequest, buildCombinationsRequest, callEngine, cellKey, cellsToAvoid,
  conflictsText, courseColors, creditsText, decodeTokenPayload, errorMessage, minutesToHHMM,
  newRequestId, parseCourses, reasonText, selectionFrom, slotsText, timeoutFromQuery, tooManyWindows,
} from './logic.js';

const TOKEN_KEY = 'sched.token'; // solo sessionStorage: nunca localStorage
const timeoutMs = timeoutFromQuery(location.search);
const $ = (id) => document.getElementById(id);

const state = {
  token: '',
  tokenInfo: null,
  cells: new Set(),
  selection: {}, // { curso: grupo }
  sectionsById: new Map(), // grupo -> sección (con franjas), de la última combinación o alternativa elegida
  enrolled: new Set(),
  alerted: new Set(),
  catalogSeq: 0,
  colors: new Map(),
  generation: { combinations: 0, alternatives: 0 }, // descarta respuestas de llamadas anteriores
};

function el(tag, { text, cls, attrs } = {}, ...children) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  for (const [k, v] of Object.entries(attrs ?? {})) node.setAttribute(k, v);
  for (const child of children) node.append(child);
  return node;
}

// Aviso general (E5 y errores de red): no bloquea la página.
function showNotice(message, kind = 'warn') {
  const box = $('aviso');
  box.className = `notice notice-${kind}`;
  box.textContent = message;
  box.hidden = false;
}
function clearNotice() {
  $('aviso').hidden = true;
  $('aviso').textContent = '';
}

// Token

function loadToken() {
  state.token = sessionStorage.getItem(TOKEN_KEY) ?? '';
  applyToken();
}

function applyToken() {
  const withToken = state.token !== '';
  state.tokenInfo = withToken ? decodeTokenPayload(state.token) : null;
  $('token').value = state.token;
  $('modo-con-token').hidden = !withToken;
  $('modo-sin-token').hidden = withToken;
  const status = $('estado-token');
  if (!withToken) {
    status.textContent = 'Sin token: el estudiante y las asignaturas se envían en la solicitud (solo desarrollo).';
    return;
  }
  const info = state.tokenInfo;
  if (!info.ok) {
    status.textContent = 'No pude leer el contenido del token; igual se enviará y el motor decidirá si es válido.';
    $('token-estudiante').textContent = '(desconocido)';
    $('token-asignaturas').replaceChildren();
    return;
  }
  status.textContent = info.expired
    ? 'El token ya venció: el motor lo rechazará. Pega uno nuevo.'
    : 'Token cargado (guardado solo en esta pestaña).';
  $('token-estudiante').textContent = info.studentId || '(sin identificador)';
  $('token-asignaturas').replaceChildren(...info.courses.map((c) => el('li', { text: c })));
}

$('usar-token').addEventListener('click', () => {
  const value = $('token').value.trim();
  if (value) sessionStorage.setItem(TOKEN_KEY, value);
  else sessionStorage.removeItem(TOKEN_KEY);
  state.token = value;
  applyToken();
});
$('olvidar-token').addEventListener('click', () => {
  sessionStorage.removeItem(TOKEN_KEY);
  state.token = '';
  applyToken();
});

/** Estudiante y asignaturas según el modo; null y un aviso si falta algo. */
function identity() {
  if (state.token) {
    return { studentId: state.tokenInfo?.studentId ?? '', courses: state.tokenInfo?.courses ?? [], withToken: true };
  }
  const studentId = $('estudiante').value.trim();
  const { courses, invalid } = parseCourses($('asignaturas').value);
  if (!studentId) return fail('Indica el identificador del estudiante.');
  if (invalid.length > 0) return fail(`Códigos no válidos: ${invalid.join(', ')}. Usa letras, dígitos, punto, guion o guion bajo.`);
  if (courses.length === 0) return fail('Indica al menos una asignatura.');
  return { studentId, courses, withToken: false };
}

function fail(message) {
  showNotice(message, 'error');
  return null;
}

// Grilla de franjas a evitar

function buildAvoidGrid() {
  const grid = $('grilla-evitar');
  const hours = [];
  for (let h = START_HOUR; h < END_HOUR; h++) hours.push(h);
  grid.append(el('div', { attrs: { role: 'row' }, cls: 'row' }, el('div', { attrs: { role: 'columnheader' }, text: '' }),
    ...DAYS.map((d) => el('div', { attrs: { role: 'columnheader', 'aria-label': d.label }, text: d.short }))));
  for (const hour of hours) {
    const row = el('div', { attrs: { role: 'row' }, cls: 'row' },
      el('div', { attrs: { role: 'rowheader' }, text: `${String(hour).padStart(2, '0')}:00` }));
    for (const day of DAYS) {
      row.append(el('div', {
        attrs: {
          role: 'gridcell',
          tabindex: '-1',
          'aria-selected': 'false',
          'aria-label': `${day.label} ${minutesToHHMM(hour * 60)} a ${minutesToHHMM((hour + 1) * 60)}`,
          'data-day': day.key,
          'data-hour': String(hour),
        },
      }));
    }
    grid.append(row);
  }
  grid.querySelector('[role="gridcell"]').tabIndex = 0;
}

function cellOf(node) {
  return node?.closest?.('[role="gridcell"]') ?? null;
}

function setCell(cell, marked) {
  const key = cellKey(cell.dataset.day, Number(cell.dataset.hour));
  if (marked) state.cells.add(key);
  else state.cells.delete(key);
  cell.setAttribute('aria-selected', String(marked));
  updateAvoidSummary();
}

function updateAvoidSummary() {
  const windows = cellsToAvoid(state.cells);
  const hours = state.cells.size;
  $('resumen-franjas').textContent = hours === 0
    ? 'Sin franjas a evitar.'
    : `${hours} hora${hours === 1 ? '' : 's'} a evitar en ${windows.length} ventana${windows.length === 1 ? '' : 's'}.`;
}

function focusCell(cell) {
  const grid = $('grilla-evitar');
  grid.querySelectorAll('[tabindex="0"]').forEach((c) => { c.tabIndex = -1; });
  cell.tabIndex = 0;
  cell.focus();
}

function neighbour(cell, dDay, dHour) {
  const dayIndex = DAYS.findIndex((d) => d.key === cell.dataset.day) + dDay;
  const hour = Number(cell.dataset.hour) + dHour;
  if (dayIndex < 0 || dayIndex >= DAYS.length || hour < START_HOUR || hour >= END_HOUR) return null;
  return $('grilla-evitar').querySelector(`[data-day="${DAYS[dayIndex].key}"][data-hour="${hour}"]`);
}

function wireAvoidGrid() {
  const grid = $('grilla-evitar');
  let dragging = null; // estado que se pinta mientras se arrastra con el mouse

  grid.addEventListener('pointerdown', (event) => {
    const cell = cellOf(event.target);
    if (!cell || event.button !== 0) return;
    const marked = cell.getAttribute('aria-selected') !== 'true';
    setCell(cell, marked);
    focusCell(cell);
    // En pantallas táctiles solo se marca con un toque: así no se pierde el desplazamiento.
    if (event.pointerType !== 'touch') dragging = marked;
    event.preventDefault();
  });
  grid.addEventListener('pointerover', (event) => {
    const cell = cellOf(event.target);
    if (dragging !== null && cell && event.buttons === 1) setCell(cell, dragging);
  });
  document.addEventListener('pointerup', () => { dragging = null; });
  document.addEventListener('pointercancel', () => { dragging = null; });

  grid.addEventListener('keydown', (event) => {
    const cell = cellOf(event.target);
    if (!cell) return;
    const moves = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    if (event.key in moves) {
      const next = neighbour(cell, ...moves[event.key]);
      if (next) {
        // Con Mayús se "pinta" al moverse, como el arrastre.
        if (event.shiftKey) setCell(next, cell.getAttribute('aria-selected') === 'true');
        focusCell(next);
      }
      event.preventDefault();
    } else if (event.key === ' ' || event.key === 'Enter') {
      setCell(cell, cell.getAttribute('aria-selected') !== 'true');
      event.preventDefault();
    } else if (event.key === 'Home' || event.key === 'End') {
      const target = grid.querySelector(`[data-day="${cell.dataset.day}"][data-hour="${event.key === 'Home' ? START_HOUR : END_HOUR - 1}"]`);
      focusCell(target);
      event.preventDefault();
    }
  });

  $('limpiar-franjas').addEventListener('click', () => {
    state.cells.clear();
    grid.querySelectorAll('[aria-selected="true"]').forEach((c) => c.setAttribute('aria-selected', 'false'));
    updateAvoidSummary();
  });
}

// Calendario semanal de una combinación

function renderCalendar(sections) {
  const calendar = el('div', { cls: 'calendar', attrs: { 'aria-hidden': 'true' } });
  calendar.append(el('div', { cls: 'head' }));
  DAYS.forEach((d) => calendar.append(el('div', { cls: 'head', text: d.short })));
  for (let h = START_HOUR; h < END_HOUR; h += 2) {
    const row = (h - START_HOUR) * 2 + 2;
    const label = el('div', { cls: 'hour', text: String(h) });
    label.style.gridRow = `${row} / span 2`;
    calendar.append(label);
    const line = el('div', { cls: 'line' });
    line.style.gridRow = `${row} / span 1`;
    calendar.append(line);
  }
  for (const section of sections) {
    const color = state.colors.get(section.course_id) ?? 0;
    for (const slot of section.slots) {
      const dayIndex = DAYS.findIndex((d) => d.key === slot.day);
      if (dayIndex < 0) continue; // el domingo no se dibuja
      const block = el('div', { cls: `block c${color}`, text: section.course_id });
      const first = Math.max(0, Math.floor((slot.start_minute - START_HOUR * 60) / 30)) + 2;
      const last = Math.min(32, Math.ceil((slot.end_minute - START_HOUR * 60) / 30)) + 2;
      block.style.gridColumn = String(dayIndex + 2);
      block.style.gridRow = `${first} / ${Math.max(last, first + 1)}`;
      calendar.append(block);
    }
  }
  return calendar;
}

function sectionList(sections) {
  const list = el('ul', { cls: 'sections' });
  for (const section of sections) {
    const swatch = el('span', { cls: `swatch c${state.colors.get(section.course_id) ?? 0}`, attrs: { 'aria-hidden': 'true' } });
    list.append(el('li', {}, swatch, el('strong', { text: `${section.course_id} · ${section.section_id}` }),
      document.createTextNode(`: ${slotsText(section.slots) || 'sin horario'}`)));
  }
  return list;
}

// Llamadas al motor

async function ask(path, body) {
  const requestId = newRequestId();
  const result = await callEngine({
    fetchImpl: (url, init) => fetch(url, init),
    url: path,
    token: state.token,
    body,
    timeoutMs,
    requestId,
  });
  return result;
}

/** Muestra el error en el área de resultados (aria-live) y, si es de tiempo/red, el aviso E5. */
function showFailure(container, result) {
  if (result.kind === 'timeout' || result.kind === 'network') {
    container.replaceChildren();
    showNotice(TIMEOUT_MESSAGE);
    return;
  }
  const { title, detail } = errorMessage(result);
  container.replaceChildren(el('div', { cls: 'notice notice-error', attrs: { role: 'alert' } },
    el('p', {}, el('strong', { text: title })),
    el('p', { text: detail }),
    el('p', { cls: 'muted', text: `Código de solicitud para soporte: ${result.requestId}` })));
}

function setBusy(button, busy, idleText) {
  button.disabled = busy;
  button.textContent = busy ? 'Consultando…' : idleText;
}

function summary(data) {
  const box = el('div', {});
  box.append(el('p', { text: creditsText(data.total_credits, data.max_credits) }));
  if (data.complete === false) {
    box.append(el('p', { cls: 'notice notice-warn', text: 'La búsqueda se cortó por el tiempo máximo: estas opciones son válidas, pero podría haber mejores.' }));
  }
  if (data.stale === true) {
    box.append(el('p', { cls: 'notice notice-warn', text: 'Los cupos podrían no estar actualizados: el motor perdió sincronía con la institución.' }));
  }
  return box;
}

async function seeCombinations() {
  const who = identity();
  if (!who) return;
  const windows = cellsToAvoid(state.cells);
  if (tooManyWindows(windows)) return void showNotice('Marcaste demasiadas ventanas separadas; agrúpalas o quita algunas.', 'error');
  clearNotice();
  const generation = ++state.generation.combinations;
  const button = $('ver-combinaciones');
  const container = $('resultado-combinaciones');
  setBusy(button, true);
  const result = await ask('/combinations', buildCombinationsRequest({ ...who, avoid: windows }));
  if (generation !== state.generation.combinations) return;
  setBusy(button, false, 'Ver combinaciones');
  if (result.kind !== 'ok') return showFailure(container, result);

  const data = result.data;
  state.catalogSeq = data.catalog_seq;
  state.colors = courseColors(who.courses);
  const box = el('div', {});
  box.append(summary(data));
  if (data.schedules.length === 0) {
    box.append(el('p', { cls: 'notice notice-warn', text: 'No hay ninguna combinación completa sin cruces con cupo para esas asignaturas.' }));
  } else {
    box.append(el('p', { text: `${data.schedules.length} ${data.schedules.length === 1 ? 'combinación' : 'combinaciones'}, de menor a mayor penalización.` }));
    const cards = el('ol', { cls: 'cards' });
    data.schedules.slice(0, 12).forEach((schedule, i) => {
      const choose = el('button', { text: 'Elegir esta combinación', attrs: { type: 'button' } });
      choose.addEventListener('click', () => chooseSections(schedule.sections));
      cards.append(el('li', { cls: 'card' },
        el('h3', { text: `Combinación ${i + 1}` }),
        el('p', { cls: 'meta', text: `Penalización: ${schedule.penalty}` }),
        renderCalendar(schedule.sections), sectionList(schedule.sections), choose));
    });
    box.append(cards);
    if (data.schedules.length > 12) box.append(el('p', { cls: 'muted', text: `Se muestran las 12 primeras de ${data.schedules.length}.` }));
  }
  container.replaceChildren(box);
}

// Selección y alternativas

function chooseSections(sections) {
  state.selection = selectionFrom(sections);
  state.sectionsById = new Map(sections.map((s) => [s.section_id, s]));
  const chosen = new Set(Object.values(state.selection));
  state.enrolled = new Set([...state.enrolled].filter((id) => chosen.has(id)));
  state.alerted = new Set([...state.alerted].filter((id) => chosen.has(id)));
  renderSelection();
  $('seccion-seleccion').hidden = false;
  $('resultado-alternativas').replaceChildren();
  $('t-seleccion').scrollIntoView({ block: 'start' });
  $('t-seleccion').focus?.();
}

function renderSelection() {
  const list = $('lista-seleccion');
  list.replaceChildren();
  for (const section of [...state.sectionsById.values()].sort((a, b) => a.course_id.localeCompare(b.course_id))) {
    const id = section.section_id;
    const swatch = el('span', { cls: `swatch c${state.colors.get(section.course_id) ?? 0}`, attrs: { 'aria-hidden': 'true' } });
    const check = el('input', { attrs: { type: 'checkbox', id: `inscrito-${id}` } });
    check.checked = state.enrolled.has(id);
    check.addEventListener('change', () => (check.checked ? state.enrolled.add(id) : state.enrolled.delete(id)));
    const alert = el('button', { cls: 'danger', text: 'Este grupo se quedó sin cupo', attrs: { type: 'button' } });
    alert.addEventListener('click', () => {
      state.alerted.add(id);
      requestAlternatives();
    });
    list.append(el('li', {},
      el('div', { cls: 'info' }, swatch, el('strong', { text: `${section.course_id} · ${id}` }),
        document.createTextNode(`: ${slotsText(section.slots)}`)),
      el('label', { cls: 'check', attrs: { for: `inscrito-${id}` } }, check, document.createTextNode('Ya inscrito')),
      alert));
  }
  renderSimulationHelp();
}

/** Para que el motor también vea el cupo en 0, la institución simulada envía el cambio (solo demo). */
function renderSimulationHelp() {
  const box = $('ayuda-simulacion');
  if (state.alerted.size === 0) return void (box.hidden = true);
  const commands = [...state.alerted].map((id) =>
    `java -jar simulation/target/sched-simulation.jar seats ${state.catalogSeq + 1} ${id} 0 --plaintext`);
  box.replaceChildren(
    el('p', { text: 'En esta demostración el cupo real no cambia solo. Para que el motor también vea el grupo sin cupo, envía el cambio desde el simulador de la institución y vuelve a pedir alternativas:' }),
    el('pre', { cls: 'cmd', text: commands.join('\n') }));
  box.hidden = false;
}

async function requestAlternatives() {
  const who = identity();
  if (!who) return;
  clearNotice();
  const generation = ++state.generation.alternatives;
  const container = $('resultado-alternativas');
  const button = $('pedir-alternativas');
  setBusy(button, true);
  const body = buildAlternativesRequest({
    ...who,
    avoid: cellsToAvoid(state.cells),
    selection: state.selection,
    enrolled: [...state.enrolled],
  });
  const result = await ask('/alternatives', body);
  if (generation !== state.generation.alternatives) return;
  setBusy(button, false, 'Pedir alternativas');
  renderSimulationHelp();
  if (result.kind !== 'ok') return showFailure(container, result);

  const data = result.data;
  state.catalogSeq = data.catalog_seq;
  state.colors = courseColors(who.courses);
  renderSelection();
  const box = el('div', {});
  box.append(summary(data));
  const conflicts = conflictsText(data.conflicts);
  if (data.selection_valid) {
    box.append(el('p', { cls: 'notice notice-ok', text: 'Tu selección sigue siendo válida: todos los grupos tienen cupo y no se cruzan.' }));
  }
  if (conflicts.length > 0) {
    box.append(el('div', { cls: 'notice notice-error' }, el('p', {}, el('strong', { text: 'Conflictos en tu selección' })),
      ...conflicts.map((line) => el('p', { text: line }))));
  }
  const withoutSeats = new Set((data.conflicts?.without_seats ?? []).map((c) => c.section));
  const unseen = [...state.alerted].filter((id) => !withoutSeats.has(id));
  if (unseen.length > 0) {
    box.append(el('p', { cls: 'notice notice-warn', text: `El motor todavía ve cupo en ${unseen.join(', ')}: la alerta no coincide con su réplica del catálogo.` }));
  }
  if (data.alternatives.length === 0) {
    box.append(el('p', { cls: 'notice notice-warn', text: 'No hay ninguna combinación completa con cupo y sin cruces para estas asignaturas.' }));
  } else {
    box.append(el('p', { text: 'Alternativas, de la que cambia menos a la que cambia más:' }));
    const cards = el('ol', { cls: 'cards' });
    data.alternatives.forEach((alt, i) => {
      const choose = el('button', { text: 'Elegir esta alternativa', attrs: { type: 'button' } });
      choose.addEventListener('click', () => {
        state.alerted.clear();
        chooseSections(alt.sections);
      });
      const changes = alt.changes.length === 0
        ? el('p', { text: 'Sin cambios: es tu selección actual.' })
        : el('ul', { cls: 'changes' }, ...alt.changes.map((c) => el('li', { text: reasonText(c) })));
      cards.append(el('li', { cls: 'card' },
        el('h3', { text: `Alternativa ${i + 1}` }),
        el('p', { cls: 'meta', text: `Distancia: ${alt.distance} · Penalización: ${alt.penalty}` }),
        changes, renderCalendar(alt.sections), sectionList(alt.sections), choose));
    });
    box.append(cards);
  }
  container.replaceChildren(box);
}

// Arranque

buildAvoidGrid();
wireAvoidGrid();
updateAvoidSummary();
loadToken();
$('ver-combinaciones').addEventListener('click', seeCombinations);
$('pedir-alternativas').addEventListener('click', requestAlternatives);
