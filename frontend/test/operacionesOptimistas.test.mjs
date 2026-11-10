// Regresión: añadir y duplicar se ven al momento, sin esperar a SharePoint.
// Ejecutar con «npm test» (compila el módulo a target-test y usa node --test).
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  aplicarOperacionesOptimistas, crearCola, ejecutarConActualizacionOptimista, FORMATO_ID, nuevoIdEnsayo,
  prepararOperacionOptimista,
} from '../target-test/estado/operacionesOptimistas.js';

function fila(id, proyecto, cerrado = false) {
  return {
    id, version: `v-${id}`, cerrado, ...(cerrado ? {} : { prioridad: 1 }),
    datos: { id, project: proyecto, done: cerrado, resp: 'Otro' },
    calculo: { estado: 'queued', estadoDeclarado: 'ok', diasNominales: 1, diasPlanificados: 1, fraccionAvance: 0, tipoEspecial: false },
  };
}

function vista(...filas) {
  return {
    hoy: '2026-11-10', semanaHoy: 46, calendario: { anio: 2026, saltarFestivos: true, festivos: [] },
    grupos: [{ clave: 'g', nombre: 'Grupo', color: '#000', bancos: [{ nombre: 'Banco 1', organizacion: 'BW' }] }],
    bancos: { 'Banco 1': filas, 'Banco 2': [fila('rotro001', 'X')] },
  };
}

const editor = { email: 'ana@ejemplo.local', nombre: 'Ana', responsable: 'Ana Responsable', rol: 'editor', esAdmin: false, soloLectura: false };
const admin = { ...editor, rol: 'admin', esAdmin: true };

function generador(...ids) {
  let i = 0;
  return () => ids[i++];
}

function diferida() {
  let resolver;
  let rechazar;
  const promesa = new Promise((res, rej) => { resolver = res; rechazar = rej; });
  return { promesa, resolver, rechazar };
}

const ids = (v, banco = 'Banco 1') => v.bancos[banco].map((f) => f.id);

describe('altas y copias al momento', () => {
  it('los ids nuevos tienen el formato del backend (r + 7 en base 36)', () => {
    for (let i = 0; i < 200; i++) assert.match(nuevoIdEnsayo(), FORMATO_ID);
  });

  it('el alta aparece antes de los cerrados, con el responsable del usuario y prioridad siguiente', () => {
    const base = vista(fila('ra000001', 'P1'), fila('ra000002', 'P2'), fila('rc000001', 'C', true));
    const op = prepararOperacionOptimista(base, 'Banco 1', 'anadir', {}, editor, generador('rnuevo01'));
    const mostrada = aplicarOperacionesOptimistas(base, [op]);
    assert.deepEqual(ids(mostrada), ['ra000001', 'ra000002', 'rnuevo01', 'rc000001']);
    const nueva = mostrada.bancos['Banco 1'][2];
    assert.equal(nueva.optimista, true);
    assert.equal(nueva.datos.id, 'rnuevo01');
    assert.equal(nueva.datos.resp, 'Ana Responsable');
    assert.equal(nueva.datos.cf, 1.2);
    assert.equal(nueva.datos.inputs, false);
    assert.equal(nueva.prioridad, 3);
    assert.deepEqual(op.ids, ['rnuevo01']);
    assert.equal(base.bancos['Banco 1'].length, 3, 'la vista del backend no se toca');
  });

  it('un Admin da de alta sin responsable (como OperacionesBanco.anadir)', () => {
    const op = prepararOperacionOptimista(vista(), 'Banco 1', 'anadir', {}, admin, generador('rnuevo02'));
    assert.equal(op.inserciones[0].fila.datos.resp, '');
  });

  it('cada copia va justo debajo de su original y la prioridad se renumera', () => {
    const base = vista(fila('ra000001', 'P1'), fila('ra000002', 'P2'), fila('ra000003', 'P3'));
    const op = prepararOperacionOptimista(base, 'Banco 1', 'duplicar', { ids: ['ra000001', 'ra000003'] }, editor,
      generador('rcopia01', 'rcopia03'));
    const mostrada = aplicarOperacionesOptimistas(base, [op]);
    assert.deepEqual(ids(mostrada), ['ra000001', 'rcopia01', 'ra000002', 'ra000003', 'rcopia03']);
    assert.deepEqual(mostrada.bancos['Banco 1'].map((f) => f.prioridad), [1, 2, 3, 4, 5]);
    assert.equal(mostrada.bancos['Banco 1'][1].datos.project, 'P1');
    assert.equal(mostrada.bancos['Banco 1'][1].datos.id, 'rcopia01');
    assert.equal(mostrada.bancos['Banco 1'][1].version, null);
    assert.deepEqual(op.ids, ['rcopia01', 'rcopia03']);
  });

  it('nunca propone un id que ya existe en otro banco ni lo repite', () => {
    const base = vista(fila('ra000001', 'P1'));
    const op = prepararOperacionOptimista(base, 'Banco 1', 'duplicar', { ids: ['ra000001'] }, editor,
      generador('rotro001', 'ra000001', 'rlibre01'));
    assert.deepEqual(op.ids, ['rlibre01']);
  });

  it('sin selección activa no hay fila provisional (el backend da el aviso de siempre)', () => {
    const base = vista(fila('ra000001', 'P1'), fila('rc000001', 'C', true));
    assert.equal(prepararOperacionOptimista(base, 'Banco 1', 'duplicar', { ids: ['rc000001'] }, editor), null);
    assert.equal(prepararOperacionOptimista(base, 'Banco 1', 'mover', { ids: ['ra000001'] }, editor), null);
  });

  it('al llegar la vista confirmada la fila no se duplica', () => {
    const base = vista(fila('ra000001', 'P1'));
    const op = prepararOperacionOptimista(base, 'Banco 1', 'anadir', {}, editor, generador('rnuevo03'));
    const confirmada = vista(fila('ra000001', 'P1'), fila('rnuevo03', ''));
    assert.deepEqual(ids(aplicarOperacionesOptimistas(confirmada, [op])), ['ra000001', 'rnuevo03']);
  });

  it('se ve antes de que termine la petición y se revierte si el backend falla', async () => {
    const base = vista(fila('ra000001', 'P1'));
    const op = prepararOperacionOptimista(base, 'Banco 1', 'anadir', {}, editor, generador('rnuevo04'));
    const espera = diferida();
    let activas = [];
    let revertido = false;
    const resultado = ejecutarConActualizacionOptimista(
      () => { activas = [op]; }, () => espera.promesa, () => { activas = []; }, () => { activas = []; revertido = true; });
    assert.deepEqual(ids(aplicarOperacionesOptimistas(base, activas)), ['ra000001', 'rnuevo04']);
    espera.rechazar(new Error('SharePoint no responde'));
    await assert.rejects(resultado, /SharePoint no responde/);
    assert.equal(revertido, true);
    assert.deepEqual(ids(aplicarOperacionesOptimistas(base, activas)), ['ra000001']);
  });
});

describe('cola de operaciones', () => {
  it('las ejecuta de una en una y en orden, también si una falla', async () => {
    const encolar = crearCola();
    const orden = [];
    const primera = diferida();
    const a = encolar(async () => { orden.push('a-empieza'); await primera.promesa; orden.push('a-termina'); return 'a'; });
    const b = encolar(async () => { orden.push('b'); throw new Error('falla b'); });
    const c = encolar(async () => { orden.push('c'); return 'c'; });
    await new Promise((r) => setTimeout(r, 10));
    assert.deepEqual(orden, ['a-empieza'], 'b y c esperan a que termine a');
    primera.resolver();
    assert.equal(await a, 'a');
    await assert.rejects(b, /falla b/);
    assert.equal(await c, 'c');
    assert.deepEqual(orden, ['a-empieza', 'a-termina', 'b', 'c']);
  });
});
