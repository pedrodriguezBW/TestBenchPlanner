// Gantt general rediseñado (doc 09): carriles, ocupación y trazabilidad.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { asignarCarriles, enlacesTrazabilidad, flechasVisibles, maximoSimultaneo, ocupacionPeriodo, tramoVisible } from '../target-test/utilidades/ganttModerno.js';

describe('Gantt general: carriles', () => {
  it('apila en carriles distintos solo lo que coincide en el tiempo', () => {
    const tramos = [{ id: 'a', d0: 0, d1: 5 }, { id: 'b', d0: 3, d1: 8 }, { id: 'c', d0: 6, d1: 9 }, { id: 'd', d0: 4, d1: 4 }];
    const { carril, carriles } = asignarCarriles(tramos);
    assert.equal(carriles, 3);
    assert.equal(carril.a, 0);
    assert.equal(carril.b, 1);
    assert.equal(carril.d, 2);
    assert.equal(carril.c, 0, 'c reutiliza el carril de a, que ya terminó');
    assert.equal(maximoSimultaneo(tramos), 3);
  });
  it('un ensayo que empieza el día en que termina otro va en otro carril (comparten ese día)', () => {
    const { carriles } = asignarCarriles([{ id: 'a', d0: 0, d1: 3 }, { id: 'b', d0: 3, d1: 6 }]);
    assert.equal(carriles, 2);
    assert.equal(asignarCarriles([]).carriles, 1);
  });
});

describe('Gantt general: ocupación del periodo', () => {
  it('cuenta días con algún ensayo sin duplicar solapes y recorta a la ventana', () => {
    assert.equal(ocupacionPeriodo([{ id: 'a', d0: 0, d1: 4 }, { id: 'b', d0: 2, d1: 6 }], 10), 70);
    assert.equal(ocupacionPeriodo([{ id: 'a', d0: -5, d1: 20 }], 10), 100);
    assert.equal(ocupacionPeriodo([], 10), 0);
  });
  it('tramo visible dentro de la ventana', () => {
    const min = new Date(2026, 10, 2);
    assert.deepEqual(tramoVisible(new Date(2026, 9, 30), new Date(2026, 10, 4), min, 7), { d0: 0, d1: 2 });
    assert.equal(tramoVisible(new Date(2026, 10, 10), new Date(2026, 10, 12), min, 7), null);
  });
});

describe('Gantt general: trazabilidad del proyecto en foco', () => {
  it('cada ensayo viene del último que terminó antes en OTRO banco', () => {
    const e = [
      { id: 'wc', banco: '1500 WC', inicio: 0, fin: 10 },
      { id: 'ctag', banco: '2000 CTAG', inicio: 12, fin: 20 },
      { id: 'wc2', banco: '1500 WC', inicio: 21, fin: 25 },
      { id: 'c1', banco: '1000 CTAG 1', inicio: 22, fin: 40 },
      { id: 'paralelo', banco: '2000 CTAG', inicio: 5, fin: 30 },
    ];
    assert.deepEqual(enlacesTrazabilidad(e), [
      { de: 'wc', a: 'ctag' },
      { de: 'ctag', a: 'wc2' },
      { de: 'ctag', a: 'c1' },
    ]);
  });
  it('sin ensayos previos en otro banco no hay flecha', () => {
    assert.deepEqual(enlacesTrazabilidad([{ id: 'a', banco: 'X', inicio: 0, fin: 5 }, { id: 'b', banco: 'X', inicio: 6, fin: 9 }]), []);
  });
  it('no enlaza ensayos separados por más del hueco máximo', () => {
    const e = [{ id: 'a', banco: 'X', inicio: 0, fin: 5 }, { id: 'b', banco: 'Y', inicio: 40, fin: 45 }];
    assert.deepEqual(enlacesTrazabilidad(e, 21), []);
    assert.deepEqual(enlacesTrazabilidad(e), [{ de: 'a', a: 'b' }]);
  });
  it('con muchas flechas solo se dibujan las del ensayo bajo el ratón', () => {
    const pocas = [{ de: 'a', a: 'b' }];
    assert.deepEqual(flechasVisibles(pocas, null), pocas);
    const muchas = Array.from({ length: 20 }, (_, i) => ({ de: 'e' + i, a: 'e' + (i + 1) }));
    assert.deepEqual(flechasVisibles(muchas, null), []);
    assert.deepEqual(flechasVisibles(muchas, 'e3'), [{ de: 'e2', a: 'e3' }, { de: 'e3', a: 'e4' }]);
  });
});
