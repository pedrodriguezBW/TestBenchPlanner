'use strict';
/*
 * Ejecuta los PASOS NEUTROS de un caso sobre la interfaz React, como lo haría
 * una persona: escribir en celdas, elegir en el calendario, pulsar botones,
 * arrastrar filas, rellenar la ventana de cierre…
 *
 * Es el equivalente de controlador-4.9.4.cjs para la interfaz nueva. La foto
 * resultante se saca de la API (/api/v1/planificacion y /api/load), de modo que
 * sirve igual contra el backend Java que contra el backend simulado.
 */

const ESPERA_MAXIMA_MS = 15000;

/** Espera a que no quede nada pendiente de guardar («● Synced»). */
async function esperarGuardado(pagina, minimoMs = 0) {
  if (minimoMs) await pagina.waitForTimeout(minimoMs);
  await pagina.waitForFunction(() => {
    const s = document.getElementById('sp-status');
    return s && /Synced|Solo lectura/.test(s.textContent);
  }, null, { timeout: ESPERA_MAXIMA_MS });
  await pagina.waitForTimeout(150);
}

async function abrirBanco(pagina, banco) {
  const destino = '#/' + encodeURIComponent(banco);
  if (!pagina.url().endsWith(destino)) {
    await pagina.evaluate((d) => { window.location.hash = d; }, destino);
  }
  await pagina.waitForFunction((b) => document.querySelector('.view-head h2')?.textContent === b, banco);
}

const fila = (pagina, id) => pagina.locator(`#tb tr[data-id="${id}"]`);

/** Elige una fecha en el calendario propio (navegando por meses) o la borra. */
async function elegirFecha(pagina, caja, valor) {
  await caja.click();
  const panel = pagina.locator('.cal-pop');
  await panel.waitFor();
  if (!valor) {
    await panel.getByRole('button', { name: 'Clear' }).click();
    return;
  }
  const [a, m, d] = valor.split('-').map(Number);
  const objetivo = new Date(a, m - 1, 1).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
  for (let i = 0; i < 240; i++) {
    const titulo = await panel.locator('.mtitle').textContent();
    if (titulo === objetivo) break;
    const [nombreMes, anio] = titulo.split(' ');
    const actual = new Date(`${nombreMes} 1, ${anio}`);
    await panel.locator('.cal-head button').nth(actual < new Date(a, m - 1, 1) ? 1 : 0).click();
  }
  await panel.locator('td.day:not(.oth)', { hasText: new RegExp(`^${d}$`) }).first().click();
}

async function abrirCerrados(pagina) {
  await pagina.getByRole('button', { name: /Completed \(/ }).click();
  await pagina.locator('#done-title').waitFor();
}

async function cerrarCerrados(pagina) {
  const boton = pagina.locator('.modal').filter({ has: pagina.locator('#done-title') }).getByRole('button', { name: 'Close', exact: true });
  if (await boton.count()) await boton.click();
}

/** Rellena la ventana de cierre y, si se pide, la confirma. */
async function rellenarCierre(pagina, paso) {
  const v = paso.valores || {};
  await pagina.locator('#cl-ok').waitFor();
  const cajas = { arranqueReal: '#cl-ts', finReal: '#cl-te', diasParada: '#cl-dd', notaParada: '#cl-dn', explicacion: '#cl-cm' };
  for (const [clave, selector] of Object.entries(cajas)) {
    if (v[clave] !== undefined) await pagina.locator(selector).fill(v[clave] === null ? '' : String(v[clave]));
  }
  if (v.estadoFinal !== undefined) await pagina.locator('#cl-st').selectOption(v.estadoFinal);
  if (v.motivo !== undefined) await pagina.locator('#cl-rs').selectOption(v.motivo);
  await pagina.waitForTimeout(600); // validación en vivo del backend
  if (paso.confirmar !== false && await pagina.locator('#cl-ok').isEnabled()) {
    await pagina.locator('#cl-ok').click();
    await pagina.locator('#cl-ok').waitFor({ state: 'detached', timeout: ESPERA_MAXIMA_MS });
  }
}

async function ejecutarPaso(pagina, paso, estado) {
  const { op } = paso;
  if (paso.banco && op !== 'mover') await abrirBanco(pagina, paso.banco);
  const visible = async (loc) => (await loc.count()) > 0 && await loc.first().isVisible() && await loc.first().isEnabled();
  switch (op) {
    case 'editar': {
      const celda = fila(pagina, paso.ensayo).locator(`[data-f="${paso.campo}"]`).first();
      if (!await visible(celda)) return; // p. ej. solo lectura: la celda está desactivada
      const tipo = await celda.evaluate((el) => `${el.tagName}:${el.type || ''}:${el.className}`);
      if (tipo.includes('datecal')) await elegirFecha(pagina, celda, paso.valor);
      else if (tipo.startsWith('SELECT')) await celda.selectOption(String(paso.valor));
      else if (tipo.includes(':checkbox:')) await celda.setChecked(!!paso.valor);
      else await celda.fill(paso.valor === null ? '' : String(paso.valor));
      await esperarGuardado(pagina, 1500);
      return;
    }
    case 'estado': {
      const s = fila(pagina, paso.ensayo).locator('[data-mstate]');
      if (await visible(s)) await s.selectOption(paso.valor);
      break;
    }
    case 'aplicarSugerencia': {
      const b = fila(pagina, paso.ensayo).locator('.sug-badge');
      if (await visible(b)) await b.click();
      break;
    }
    case 'comprometer':
    case 'deshacerCompromiso':
    case 'borrar': {
      const titulo = { comprometer: /^Confirm dates/, deshacerCompromiso: /^Undo date confirmation/, borrar: /^Delete$/ }[op];
      const b = fila(pagina, paso.ensayo).getByTitle(titulo);
      if (await visible(b)) await b.click();
      break;
    }
    case 'confirmarTodas': {
      const b = pagina.getByRole('button', { name: /Confirm all/ });
      if (await visible(b)) await b.click();
      break;
    }
    case 'anadir': {
      const b = pagina.getByRole('button', { name: /Add test/ });
      if (await visible(b)) await b.click();
      break;
    }
    case 'seleccionar': {
      const todas = pagina.locator('#selall');
      if (!await visible(todas)) return;
      await todas.setChecked(true);
      await todas.setChecked(false);
      for (const id of paso.ensayos) await fila(pagina, id).locator('.rowsel').setChecked(true);
      return;
    }
    case 'duplicar':
      await pagina.locator('#selbar').getByRole('button', { name: /Duplicate/ }).click();
      break;
    case 'borrarSeleccion':
      await pagina.locator('#selbar').getByRole('button', { name: /Delete/ }).click();
      break;
    case 'mover': {
      await abrirBanco(pagina, paso.banco);
      if (!await visible(pagina.locator('#move-target'))) return;
      await pagina.locator('#move-target').selectOption(paso.destino);
      await pagina.locator('#move-pos').fill(String(paso.posicion));
      await pagina.locator('#selbar').getByRole('button', { name: /Move/ }).click();
      // El aviso de traslado se acepta.
      await pagina.getByRole('button', { name: 'OK' }).click({ timeout: ESPERA_MAXIMA_MS });
      break;
    }
    case 'arrastrar': {
      const asa = fila(pagina, paso.origen).locator('td.prio');
      if (!await visible(asa)) return;
      // Se centra la fila destino para que no quede tapada por la cabecera fija
      // (una persona haría scroll antes de soltar).
      const destino = fila(pagina, paso.destino);
      await destino.evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await asa.dragTo(destino, { targetPosition: { x: 200, y: 10 } });
      break;
    }
    case 'cerrar': {
      const b = fila(pagina, paso.ensayo).getByTitle('Mark complete');
      if (!await visible(b)) return;
      await b.click();
      await rellenarCierre(pagina, paso);
      break;
    }
    case 'corregir':
    case 'restaurar':
    case 'reutilizar': {
      await abrirCerrados(pagina);
      const filaCerrada = pagina.locator(`table.done tr[data-id="${paso.ensayo}"]`);
      const nombre = { corregir: /Corregir/, restaurar: /Restore/, reutilizar: /Reuse/ }[op];
      const b = filaCerrada.getByRole('button', { name: nombre });
      if (!await visible(b)) { await cerrarCerrados(pagina); return; }
      await b.click();
      if (op === 'corregir') await rellenarCierre(pagina, paso);
      await esperarGuardado(pagina);
      await cerrarCerrados(pagina);
      break;
    }
    case 'fotoKpi':
    case 'esperar':
      return;
    default:
      throw new Error('Paso desconocido: ' + op);
  }
  await esperarGuardado(pagina, 300);
  estado.pasos++;
}

/** Foto en el mismo formato que las referencias (apartados que dependen de la interfaz). */
async function fotografiar(pagina, base) {
  const vista = await (await fetch(base + '/api/v1/planificacion')).json();
  const textos = Object.fromEntries((await (await fetch(base + '/api/load', { method: 'POST' })).json()).map((f) => [f.uid, f.data]));
  const planificacion = {};
  const orden = {};
  for (const g of vista.grupos) {
    for (const b of g.bancos) {
      const filas = vista.bancos[b.nombre] || [];
      orden[b.nombre] = filas.map((f) => f.id);
      planificacion[b.nombre] = filas.filter((f) => !f.cerrado).map((f) => {
        const c = f.calculo;
        return {
          id: f.id, inicio: c.inicio ?? null, fin: c.fin ?? null, criterioInicio: c.criterioInicio ?? '', estado: c.estado,
          sugerencias: (c.sugerencias || []).map((s) => s.clave), sugerenciaAbierta: !!c.sugerenciaAbierta,
          solapes: (c.solapes || []).map((s) => `${s.inicio}..${s.fin}`), diasPlanificados: c.diasPlanificados,
          finTolerado: c.finTolerado ?? null, finPlanificado: c.finPlanificado ?? null, arranqueEfectivo: c.arranqueEfectivo ?? '',
          diasSinAvance: c.diasSinAvance ?? 0,
        };
      });
    }
  }
  const abierta = await pagina.locator('#cl-ok').count() > 0;
  const cierre = abierta ? await pagina.evaluate(() => {
    const g = (k) => document.getElementById(k);
    return { abierta: true, arranqueReal: g('cl-ts').value, finReal: g('cl-te').value, estadoFinal: g('cl-st').value,
      motivo: g('cl-rs').value, diasParada: g('cl-dd').value, error: g('cl-err').textContent };
  }) : { abierta: false };
  return { planificacion, orden, textos, cierre };
}

module.exports = { ejecutarPaso, fotografiar, esperarGuardado };
