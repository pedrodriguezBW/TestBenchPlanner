// Configuración (Test Benches, Projects): la pantalla cambia en el acto y el
// guardado va en segundo plano, en orden, juntando cambios y avisando si falla.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { crearColaConfiguracion } from '../target-test/estado/colaConfiguracion.js';

const espera = () => { let fin; const p = new Promise((r) => { fin = r; }); return { p, fin }; };
const vaciada = (cola) => new Promise((r) => { const quitar = cola.alVaciarse((v) => { quitar(); r(v); }); });

describe('cola de configuración', () => {
  it('envía en orden, junta los fusionables que esperan y recarga una sola vez', async () => {
    const cola = crearColaConfiguracion();
    const enviados = [];
    let recargas = 0;
    cola.configurar({ despues: () => { recargas++; } });
    const primero = espera();
    const fin = vaciada(cola);
    cola.encolar({ clave: 'bancos', fusionable: true, ejecutar: async () => { enviados.push('b1'); await primero.p; } });
    cola.encolar({ clave: 'bancos', fusionable: true, ejecutar: async () => { enviados.push('b2'); } });
    cola.encolar({ clave: 'bancos', fusionable: true, ejecutar: async () => { enviados.push('b3'); } });
    cola.encolar({ clave: 'proyectos', fusionable: true, ejecutar: async () => { enviados.push('p1'); } });
    assert.equal(cola.estado().pendientes, 3);
    primero.fin();
    const v = await fin;
    assert.deepEqual(enviados, ['b1', 'b3', 'p1']);
    assert.equal(v.fallo, null);
    assert.equal(recargas, 1);
    assert.equal(cola.estado().pendientes, 0);
    assert.equal(cola.pendiente(), false);
  });

  it('un renombrado nunca se junta con otro cambio', async () => {
    const cola = crearColaConfiguracion();
    const enviados = [];
    const primero = espera();
    const fin = vaciada(cola);
    cola.encolar({ clave: 'bancos', fusionable: true, ejecutar: async () => { enviados.push('mover'); await primero.p; } });
    cola.encolar({ clave: 'bancos', fusionable: false, ejecutar: async () => { enviados.push('renombrar'); } });
    cola.encolar({ clave: 'bancos', fusionable: true, ejecutar: async () => { enviados.push('org'); } });
    primero.fin();
    await fin;
    assert.deepEqual(enviados, ['mover', 'renombrar', 'org']);
  });

  it('si un guardado falla se avisa, se descartan los que esperaban y se recarga', async () => {
    const cola = crearColaConfiguracion();
    const enviados = [];
    const avisos = [];
    let recargas = 0;
    cola.configurar({ despues: () => { recargas++; }, alFallar: (e) => avisos.push(e.message) });
    const primero = espera();
    const fin = vaciada(cola);
    cola.encolar({ clave: 'bancos', fusionable: true, ejecutar: async () => { await primero.p; throw new Error('412'); } });
    cola.encolar({ clave: 'proyectos', fusionable: true, ejecutar: async () => { enviados.push('p1'); } });
    primero.fin();
    const v = await fin;
    assert.equal(v.fallo.message, '412');
    assert.deepEqual(avisos, ['412']);
    assert.deepEqual(enviados, []);
    assert.equal(recargas, 1);
  });
});
