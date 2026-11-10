'use strict';
/*
 * MOTOR DE FACTURACIÓN · réplica en JavaScript del dominio Java
 * (backend/src/main/java/com/borgwarner/tbp/dominio/facturacion).
 *
 * Es la especificación ejecutable del cálculo: el laboratorio lo usa para
 * generar las referencias (casos-facturacion.json) que la prueba Java
 * ParidadFacturacionTest debe reproducir, y el backend simulado lo usa para
 * probar la interfaz React sin Java. Cualquier cambio de reglas se hace en
 * los dos lados y se comprueba con las referencias.
 *
 * Fechas y horas: textos «aaaa-mm-dd» y «aaaa-mm-ddThh:mm» de reloj de pared
 * (Europe/Madrid). Se convierten a minutos con aritmética UTC para que un
 * cambio de hora no altere las horas (igual que (Fin−Inicio)×24 en el Excel).
 */

const DIA = 1440;

// ---- reglas de cálculo implementadas (no se editan como texto libre) -------
const REGLAS = [
  { codigo: 'LINEAR', nombre: 'Lineal', descripcion: 'Coste = horas totales × tarifa', divisor: 0, unidad: 'H' },
  { codigo: 'ASINH_100', nombre: 'Reductora /100', descripcion: 'Coste = tarifa × ASINH(horas totales / 100) × 100', divisor: 100, unidad: 'H' },
  { codigo: 'ASINH_200', nombre: 'Reductora /200', descripcion: 'Coste = tarifa × ASINH(horas totales / 200) × 200', divisor: 200, unidad: 'H' },
  { codigo: 'PER_PIECE', nombre: 'Por pieza', descripcion: 'Coste = piezas × tarifa (RACS)', divisor: 0, unidad: 'PIEZAS' },
  { codigo: 'NOT_BILLABLE', nombre: 'No facturable', descripcion: 'La línea se excluye: no suma horas ni importe', divisor: 0, unidad: 'H' },
];
const CODIGOS_REGLA = REGLAS.map((r) => r.codigo);

const ESTADOS_PERIODO = ['BORRADOR', 'EN_REVISION', 'APROBADO', 'EXPORTADO'];
const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

/** Motivos de parada: los motivos de retraso de la aplicación más «Festivo / no laborable». */
const MOTIVOS_PARADA = ['Averia', 'Falta de recursos', 'Piezas con retraso', 'Caida de ensayo', 'Cambio de prioridad', 'Festivo', 'Otro'];

// ---- fechas -------------------------------------------------------------------

const pad = (n) => String(n).padStart(2, '0');

/** «aaaa-mm-dd» o «aaaa-mm-ddThh:mm» → minutos (null si no es una fecha válida). */
function aMin(texto) {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?$/.exec(String(texto ?? '').trim());
  if (!m) return null;
  const [a, me, d, h, mi] = [+m[1], +m[2], +m[3], +(m[4] ?? 0), +(m[5] ?? 0)];
  if (me < 1 || me > 12 || d < 1 || h > 23 || mi > 59) return null;
  const ms = Date.UTC(a, me - 1, d, h, mi);
  const f = new Date(ms);
  if (f.getUTCDate() !== d || f.getUTCMonth() !== me - 1) return null;
  return ms / 60000;
}
const deMin = (min) => new Date(min * 60000).toISOString().slice(0, 16);
const diaDeMin = (min) => new Date(min * 60000).toISOString().slice(0, 10);
const diaSemana = (min) => new Date(min * 60000).getUTCDay(); // 0 domingo … 6 sábado
const sumarDias = (dia, n) => diaDeMin(aMin(dia) + n * DIA);
const esHora = (t) => /^([01]\d|2[0-3]):[0-5]\d$/.test(String(t ?? ''));

/** Minutos de [a,b) que caen en sábado o domingo. */
function minutosFinDeSemana(a, b) {
  if (b <= a) return 0;
  let total = 0;
  let dia = Math.floor(a / DIA) * DIA;
  while (dia < b) {
    const ds = diaSemana(dia);
    if (ds === 0 || ds === 6) total += Math.max(0, Math.min(b, dia + DIA) - Math.max(a, dia));
    dia += DIA;
  }
  return total;
}
const solape = (a1, b1, a2, b2) => Math.max(0, Math.min(b1, b2) - Math.max(a1, a2));

/** Redondeo a 15 minutos (al cuarto de hora más próximo; la mitad, hacia arriba). */
function cuartosDeMinutos(min) {
  if (min >= 0) return Math.floor((2 * min + 15) / 30);
  return -Math.floor((2 * -min + 15) / 30);
}
const horasDeMinutos = (min) => cuartosDeMinutos(min) / 4;
const redondear2 = (x) => Math.round((x + Number.EPSILON * Math.sign(x)) * 100) / 100;
/** ROUND(x;2) de Excel: mitad alejándose de cero, sobre la representación decimal. */
function redondeoExcel2(x) {
  if (x == null || !Number.isFinite(x)) return x;
  const s = Math.sign(x) || 1;
  const v = Math.abs(x);
  return s * Math.round(Number((v * 100).toPrecision(15))) / 100;
}

// ---- periodos ----------------------------------------------------------------

/** Periodo nominal «aaaa-mm»: del 21 del mes anterior al 20 del mes, ambos incluidos. */
function periodo(anio, mes) {
  const ant = mes === 1 ? [anio - 1, 12] : [anio, mes - 1];
  return {
    id: `${anio}-${pad(mes)}`, anio, mes, nombre: `${MESES[mes - 1]} ${anio}`,
    inicio: `${ant[0]}-${pad(ant[1])}-21`, fin: `${anio}-${pad(mes)}-20`,
  };
}
function periodoDeId(id) {
  const m = /^(\d{4})-(\d{2})$/.exec(String(id || ''));
  if (!m || +m[2] < 1 || +m[2] > 12) throw new ErrorRegla(`Periodo no válido: ${id}`);
  return periodo(+m[1], +m[2]);
}
const siguienteId = (id) => { const p = periodoDeId(id); return p.mes === 12 ? `${p.anio + 1}-01` : `${p.anio}-${pad(p.mes + 1)}`; };
const anteriorId = (id) => { const p = periodoDeId(id); return p.mes === 1 ? `${p.anio - 1}-12` : `${p.anio}-${pad(p.mes - 1)}`; };
/** Periodo al que pertenece un día: hasta el 20, el del mes; desde el 21, el del mes siguiente. */
function idPeriodoDeDia(dia) {
  const [a, m, d] = dia.split('-').map(Number);
  if (d <= 20) return `${a}-${pad(m)}`;
  return m === 12 ? `${a + 1}-01` : `${a}-${pad(m + 1)}`;
}

class ErrorRegla extends Error {}

// ---- proyectos -----------------------------------------------------------------

/**
 * Número de proyecto canónico: sin ceros a la izquierda y con la forma corta
 * de los proyectos 8000xxxx (80000885 → 80885), como en las órdenes 463B80885….
 */
function numeroCanonico(n) {
  let s = String(n ?? '').replace(/\D/g, '');
  if (!s) return '';
  s = s.replace(/^0+/, '') || '0';
  if (s.length === 8 && s.startsWith('8000')) s = '8' + s.slice(4);
  return s;
}
/** «11364 - BMW - …» → {numero: '11364', nombre: 'BMW - …'} */
function proyectoDeTexto(texto) {
  const t = String(texto ?? '').trim();
  const m = /^(\d+)\s*(?:-\s*(.*))?$/.exec(t);
  if (!m) return { numero: '', nombre: t };
  return { numero: numeroCanonico(m[1]), nombre: (m[2] ?? '').trim() };
}

// ---- datos de facturación del ensayo (DataJson.billing) ------------------------

function datosFacturacion(datos) {
  const b = datos && typeof datos.billing === 'object' && datos.billing ? datos.billing : {};
  return {
    startTime: b.startTime ?? '', endTime: b.endTime ?? '',
    stops: Array.isArray(b.stops) ? b.stops : [],
    setupHours: b.setupHours ?? '', setupConfirmed: b.setupConfirmed === true,
    hv: b.hv === 'yes' || b.hv === 'no' ? b.hv : '',
    billable: b.billable !== false, billableBy: b.billableBy ?? '', billableAt: b.billableAt ?? '',
    order: b.order ?? '', orderException: b.orderException && typeof b.orderException === 'object' ? b.orderException : null,
    pieces: b.pieces ?? '', note: b.note ?? '',
  };
}

/**
 * Datos de un ensayo que necesita la facturación, a partir de la fila de la vista
 * (datos = DataJson, calculo = lo calculado por el backend).
 */
function ensayoFacturable(banco, organizacion, fila, requesterPorAprobacion) {
  const d = fila.datos || {};
  const c = fila.calculo || {};
  const cerrado = !!fila.cerrado;
  const t = (k) => (d[k] === undefined || d[k] === null ? '' : String(d[k]));
  const fechaInicio = cerrado ? (t('finalTrueStart') || t('finalActualStart') || t('histStart') || t('actualStart')) : t('actualStart');
  const fechaFin = cerrado ? (t('finalTrueEnd') || t('histEnd') || t('trueEnd')) : t('trueEnd');
  const aprob = (t('approvalId').match(/\d+/) || [''])[0];
  return {
    id: String(fila.id), banco, organizacion: organizacion || '', cerrado,
    tipoEnsayo: t('type'), responsable: t('resp'), proyecto: t('project'), approvalId: t('approvalId'),
    requester: (aprob && requesterPorAprobacion && requesterPorAprobacion[aprob]) || '',
    fechaInicio, fechaFin, finDeSemana: d.weekend === true || d.weekend === 'yes',
    inicioAsumido: !cerrado && !t('actualStart') && !!c.arranqueAsumido,
    inicioPlan: cerrado ? '' : (c.inicio || ''), finPlan: cerrado ? '' : (c.fin || ''),
    tipoEspecial: !!c.tipoEspecial, billing: datosFacturacion(d),
  };
}

/** Validación de las paradas de un ensayo: textos de error (vacío = correctas). */
function validarParadas(paradas, inicioMin, finMin) {
  const errores = [];
  const validas = [];
  (paradas || []).forEach((p, i) => {
    const n = i + 1;
    const a = aMin(p.from);
    const b = aMin(p.to);
    if (a === null || b === null) { errores.push(`Parada ${n}: indica inicio y fin con fecha y hora.`); return; }
    if (b < a) { errores.push(`Parada ${n}: el final no puede ser anterior al inicio.`); return; }
    if (b === a) { errores.push(`Parada ${n}: la duración es cero.`); return; }
    if (!p.reason) errores.push(`Parada ${n}: falta el motivo.`);
    if (inicioMin !== null && a < inicioMin) errores.push(`Parada ${n}: empieza antes del inicio real del ensayo.`);
    if (finMin !== null && b > finMin) errores.push(`Parada ${n}: termina después del fin real del ensayo.`);
    validas.push({ a, b, n });
  });
  validas.sort((x, y) => x.a - y.a || x.b - y.b);
  for (let i = 1; i < validas.length; i++) {
    if (validas[i].a < validas[i - 1].b) errores.push(`Las paradas ${validas[i - 1].n} y ${validas[i].n} se solapan.`);
  }
  return errores;
}

/** Inicio y fin real con hora (minutos) de un ensayo; la hora que falte se toma como 00:00 / 24:00. */
function inicioFinReal(e) {
  const b = e.billing;
  const inicio = e.fechaInicio ? aMin(`${e.fechaInicio}T${esHora(b.startTime) ? b.startTime : '00:00'}`) : null;
  let fin = null;
  if (e.fechaFin) {
    fin = esHora(b.endTime) ? aMin(`${e.fechaFin}T${b.endTime}`) : (aMin(e.fechaFin) === null ? null : aMin(e.fechaFin) + DIA);
  }
  return { inicio, fin };
}

/** Validación completa de los datos de facturación de un ensayo (ficha y ventana de cierre). */
function validarDatosEnsayo(e) {
  const b = e.billing;
  const errores = [];
  if (b.startTime && !esHora(b.startTime)) errores.push('La hora real de inicio no es válida (hh:mm).');
  if (b.endTime && !esHora(b.endTime)) errores.push('La hora real de fin no es válida (hh:mm).');
  const { inicio, fin } = inicioFinReal(e);
  if (inicio !== null && fin !== null && fin <= inicio) errores.push('El fin real con hora debe ser posterior al inicio real.');
  if (b.setupHours !== '' && b.setupHours !== null) {
    const s = Number(b.setupHours);
    if (!Number.isFinite(s) || s < 0) errores.push('El setup debe ser un número de horas mayor o igual que 0.');
    else if (Math.abs(s * 4 - Math.round(s * 4)) > 1e-9) errores.push('El setup va en incrementos de 0,25 h.');
  }
  if (b.pieces !== '' && b.pieces !== null && !(Number(b.pieces) >= 0)) errores.push('Las piezas deben ser un número mayor o igual que 0.');
  errores.push(...validarParadas(b.stops, inicio, fin));
  return errores;
}

// ---- maestros ---------------------------------------------------------------------

function buscarProyecto(maestros, numero) {
  return (maestros.proyectos || []).find((p) => numeroCanonico(p.numero) === numero && p.activo !== false) || null;
}
function buscarCategoria(maestros, codigo) {
  return (maestros.categorias || []).find((c) => c.codigo === codigo && c.activa !== false) || null;
}
/** Mapeo banco + tipo de ensayo → categoría (el tipo concreto gana al genérico). */
function buscarMapeo(maestros, banco, tipoEnsayo) {
  const activos = (maestros.mapeos || []).filter((m) => m.activo !== false && m.banco === banco);
  const tipo = String(tipoEnsayo || '').trim().toLowerCase();
  return activos.find((m) => m.tipoEnsayo && m.tipoEnsayo.trim().toLowerCase() === tipo)
    || activos.find((m) => !m.tipoEnsayo) || null;
}
const vigente = (t, dia) => (!t.desde || t.desde <= dia) && (!t.hasta || t.hasta >= dia);

/**
 * Tarifa vigente el último día del periodo de facturación. Jerarquía:
 * 1) proyecto o cliente, 2) planta (BW/CTAG) o línea de producto, 3) general de
 * la categoría y la condición HV. Dentro de cada nivel gana la categoría exacta
 * sobre «*», el HV exacto sobre «*» y la vigencia más reciente.
 */
function resolverTarifa(maestros, { categoria, hv, proyecto, planta, lineaProducto, dia }) {
  const candidatas = (maestros.tarifas || []).filter((t) => (t.categoria === categoria || t.categoria === '*')
    && (t.hv === hv || t.hv === '*' || !t.hv) && vigente(t, dia) && Number.isFinite(Number(t.importe)) && t.importe !== '' && t.importe !== null);
  const nivel = (t) => {
    if (t.proyecto) return numeroCanonico(t.proyecto) === proyecto ? 1 : 0;
    if (t.planta || t.lineaProducto) {
      const okPlanta = !t.planta || t.planta === planta;
      const okLinea = !t.lineaProducto || String(t.lineaProducto).toLowerCase() === String(lineaProducto || '').toLowerCase();
      return okPlanta && okLinea ? 2 : 0;
    }
    return 3;
  };
  let mejor = null;
  let claveMejor = null;
  candidatas.forEach((t, i) => {
    const n = nivel(t);
    if (!n) return;
    const clave = [n, t.categoria === '*' ? 1 : 0, (t.hv === '*' || !t.hv) ? 1 : 0, t.desde ? -aMin(t.desde) : 0, i];
    if (!claveMejor || comparar(clave, claveMejor) < 0) { mejor = { ...t, nivel: n }; claveMejor = clave; }
  });
  return mejor;
}
function comparar(a, b) {
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
  return 0;
}

/** Coste de una línea según su regla (lógica del Excel «Coste total»). */
function costePorRegla(regla, cantidad, tarifa) {
  if (tarifa === null || tarifa === undefined || !Number.isFinite(cantidad)) return null;
  switch (regla) {
    case 'LINEAR': case 'PER_PIECE': return cantidad * tarifa;
    case 'ASINH_100': return cantidad === 0 ? 0 : tarifa * Math.asinh(cantidad / 100) * 100;
    case 'ASINH_200': return cantidad === 0 ? 0 : tarifa * Math.asinh(cantidad / 200) * 200;
    case 'NOT_BILLABLE': return 0;
    default: return null;
  }
}

/** Setup recomendado por categoría, banco y tipo de ensayo (el más concreto). */
function setupRecomendado(maestros, { categoria, banco, tipoEnsayo }) {
  const tipo = String(tipoEnsayo || '').trim().toLowerCase();
  let mejor = null;
  let puntos = -1;
  (maestros.setupRecomendado || []).forEach((s) => {
    if (s.banco && s.banco !== banco) return;
    if (s.categoria && s.categoria !== categoria) return;
    if (s.tipoEnsayo && s.tipoEnsayo.trim().toLowerCase() !== tipo) return;
    const p = (s.banco ? 4 : 0) + (s.tipoEnsayo ? 2 : 0) + (s.categoria ? 1 : 0);
    if (p > puntos) { mejor = s; puntos = p; }
  });
  return mejor ? Number(mejor.horas) : null;
}

// ---- cálculo de un tramo -------------------------------------------------------------

const BLOQ = 'BLOQUEANTE';
const ADV = 'ADVERTENCIA';
const TEXTOS = {
  FALTA_FECHA_REAL: 'Falta la fecha real de arranque (el ensayo se da por arrancado según la planificación).',
  FALTA_HORA_INICIO: 'Falta la hora real de inicio.',
  FALTA_HORA_FIN: 'Falta la hora real de fin.',
  FECHAS_INCOHERENTES: 'Fechas incoherentes: el fin real es anterior al inicio real.',
  HORAS_NEGATIVAS: 'Las horas netas del tramo son negativas.',
  PARADAS_INVALIDAS: 'Paradas no válidas',
  PROYECTO_SIN_ALTA: 'El proyecto no está dado de alta en los maestros de facturación.',
  SIN_ORDEN: 'El proyecto no tiene ninguna orden contable activa.',
  ORDEN_NO_SELECCIONADA: 'El proyecto tiene varias órdenes: selecciona una.',
  ORDEN_NO_VALIDA: 'La orden no está asociada al proyecto (solo un Admin puede usar otra, con justificación).',
  SIN_CATEGORIA: 'El banco no tiene categoría de facturación para este tipo de ensayo.',
  REGLA_NO_CONFIGURADA: 'La categoría no tiene una regla de cálculo válida.',
  HV_SIN_SELECCIONAR: 'Falta indicar Alta tensión (HV) Sí/No.',
  SETUP_NO_CONFIRMADO: 'El setup no está confirmado.',
  PIEZAS_SIN_INDICAR: 'Faltan las piezas (categoría facturada por piezas).',
  TARIFA_NO_ENCONTRADA: 'No hay tarifa vigente para la categoría, la condición HV y el periodo.',
  TARIFA_PENDIENTE: 'Tarifa excepcional pendiente de aprobación (PENDING_RATE_APPROVAL).',
  AJUSTE_SIN_VALIDAR: 'Ajuste económico pendiente de validar por un Admin.',
  YA_FACTURADO: 'El tramo ya está incluido en un periodo aprobado.',
  LINEAS_SOLAPADAS: 'Se solapa con otra línea del mismo ensayo.',
  ENSAYO_NO_ENCONTRADO: 'El ensayo ya no existe o ya no corresponde a este periodo.',
  ENSAYO_ABIERTO: 'Ensayo todavía abierto: se factura hasta la fecha de corte.',
  PERIODO_EN_CURSO: 'El periodo aún no ha terminado: se cuenta hasta ahora.',
  HORAS_ALTAS: 'Horas excepcionalmente altas.',
  AJUSTE_MANUAL: 'Tiene ajustes manuales.',
  ORDEN_EXCEPCIONAL: 'Orden excepcional autorizada por un Admin.',
  COSTE_CERO: 'Coste cero.',
  CAMBIO_POSTERIOR: 'El ensayo ha cambiado desde el último cálculo y hay un ajuste manual.',
  DE_PERIODO_ANTERIOR: 'Pendiente del periodo anterior (aplazada).',
  PROYECTO_CERO: 'Proyecto 0: coste 0, como en el Excel.',
};
const incidencia = (codigo, nivel, detalle, soloAprobacion = false) => {
  const i = { codigo, nivel, mensaje: TEXTOS[codigo] + (detalle ? ` ${detalle}` : '') };
  if (soloAprobacion) i.soloAprobacion = true;
  return i;
};

/**
 * Horas de un ensayo dentro del intervalo de un periodo de ejecución.
 * Devuelve null si el ensayo no ocupa el periodo.
 */
function tramo(e, per, ahoraMin) {
  const iniPer = aMin(per.inicio);
  const finPer = aMin(per.fin) + DIA;
  const { inicio, fin } = inicioFinReal(e);
  if (inicio === null) return null;
  if (inicio >= finPer) return null;
  const incidencias = [];
  let corte = finPer;
  let abierto = false;
  let finEfectivo = fin;
  if (fin === null) {
    abierto = true;
    if (ahoraMin !== null && ahoraMin < finPer) { corte = Math.max(iniPer, ahoraMin); incidencias.push(incidencia('PERIODO_EN_CURSO', ADV)); }
    finEfectivo = corte;
    incidencias.push(incidencia('ENSAYO_ABIERTO', ADV));
  }
  if (fin !== null && fin <= iniPer) return null;
  const b = e.billing;
  const a = Math.max(inicio, iniPer);
  const z = Math.min(finEfectivo, finPer);
  const contieneInicio = inicio >= iniPer;
  if (contieneInicio && !esHora(b.startTime)) incidencias.push(incidencia('FALTA_HORA_INICIO', BLOQ));
  if (!abierto && fin <= finPer && !esHora(b.endTime)) incidencias.push(incidencia('FALTA_HORA_FIN', BLOQ));
  if (fin !== null && fin <= inicio) {
    incidencias.push(incidencia('FECHAS_INCOHERENTES', BLOQ));
  }
  const errParadas = validarParadas(b.stops, inicio, fin);
  if (errParadas.length) incidencias.push(incidencia('PARADAS_INVALIDAS', BLOQ, errParadas.join(' ')));
  const brutos = Math.max(0, z - a);
  const finde = e.finDeSemana ? 0 : minutosFinDeSemana(a, z);
  let paradas = 0;
  (b.stops || []).forEach((p) => {
    if (p.billable === true) return;
    const pa = aMin(p.from);
    const pb = aMin(p.to);
    if (pa === null || pb === null || pb <= pa) return;
    const sa = Math.max(pa, a);
    const sb = Math.min(pb, z);
    if (sb <= sa) return;
    paradas += (sb - sa) - (e.finDeSemana ? 0 : minutosFinDeSemana(sa, sb));
  });
  const netos = brutos - finde - paradas;
  if (netos < 0) incidencias.push(incidencia('HORAS_NEGATIVAS', BLOQ));
  return {
    inicioReal: deMin(inicio), finReal: fin === null ? '' : deMin(fin), tramoInicio: deMin(a), tramoFin: deMin(z),
    abierto, contieneInicio, minutosBrutos: brutos, minutosFinDeSemana: finde, minutosParadas: paradas, minutosNetos: netos,
    horasNetas: horasDeMinutos(Math.max(0, netos)), incidencias,
  };
}

/**
 * Identidad económica de una línea: proyecto, orden, categoría, HV, tarifa…
 * `entrada` lleva los valores (ya con los ajustes que correspondan).
 */
function resolverEconomia(e, entrada, maestros, perFact) {
  const inc = [];
  const r = { proyecto: entrada.proyecto, proyectoNombre: '', orden: entrada.orden || '', categoria: entrada.categoria || '', grupo: '',
    unidad: 'H', regla: '', hv: entrada.hv || '', tarifa: null, tarifaId: '', nivelTarifa: 0, lineaProducto: '', requester: e.requester || '',
    ordenExcepcional: false };
  const proy = r.proyecto ? buscarProyecto(maestros, r.proyecto) : null;
  if (!proy) {
    inc.push(incidencia('PROYECTO_SIN_ALTA', BLOQ, r.proyecto ? `(${r.proyecto})` : '(el ensayo no tiene proyecto)'));
  } else {
    r.proyectoNombre = proy.nombre || '';
    r.lineaProducto = proy.lineaProducto || '';
    if (!r.requester) r.requester = proy.requester || '';
    const ordenes = (proy.ordenes || []).filter((o) => o.activa !== false).map((o) => String(o.codigo).trim());
    if (!r.orden) {
      if (ordenes.length === 1) r.orden = ordenes[0];
      else if (!ordenes.length) inc.push(incidencia('SIN_ORDEN', BLOQ));
      else inc.push(incidencia('ORDEN_NO_SELECCIONADA', BLOQ, `(${ordenes.join(', ')})`));
    } else if (!ordenes.includes(String(r.orden).trim())) {
      if (entrada.ordenExcepcional) { r.ordenExcepcional = true; inc.push(incidencia('ORDEN_EXCEPCIONAL', ADV)); }
      else inc.push(incidencia('ORDEN_NO_VALIDA', BLOQ, `(${r.orden})`));
    }
  }
  if (!r.categoria) {
    const m = buscarMapeo(maestros, e.banco, e.tipoEnsayo);
    if (m) { r.categoria = m.categoria; r.regla = m.regla || ''; }
  }
  const cat = r.categoria ? buscarCategoria(maestros, r.categoria) : null;
  if (!cat) {
    inc.push(incidencia('SIN_CATEGORIA', BLOQ, r.categoria ? `(${r.categoria})` : `(${e.banco}${e.tipoEnsayo ? ' · ' + e.tipoEnsayo : ''})`));
  } else {
    r.grupo = cat.grupo || '';
    r.unidad = cat.unidad === 'PIEZAS' ? 'PIEZAS' : 'H';
    if (!r.regla) r.regla = cat.regla || '';
    if (!CODIGOS_REGLA.includes(r.regla)) inc.push(incidencia('REGLA_NO_CONFIGURADA', BLOQ));
  }
  if (r.hv !== 'yes' && r.hv !== 'no') inc.push(incidencia('HV_SIN_SELECCIONAR', BLOQ));
  if (cat && r.regla !== 'NOT_BILLABLE' && (r.hv === 'yes' || r.hv === 'no')) {
    const t = resolverTarifa(maestros, { categoria: cat.codigo, hv: r.hv, proyecto: r.proyecto, planta: e.organizacion,
      lineaProducto: r.lineaProducto, dia: perFact.fin });
    if (!t) inc.push(incidencia('TARIFA_NO_ENCONTRADA', BLOQ, `(${cat.codigo}, HV ${r.hv === 'yes' ? 'Sí' : 'No'}, ${perFact.nombre})`));
    else { r.tarifa = Number(t.importe); r.tarifaId = t.id || ''; r.nivelTarifa = t.nivel; }
  }
  return { r, inc };
}

const CAMPOS_AJUSTABLES = ['horasNetas', 'horasSetup', 'piezas', 'proyecto', 'orden', 'hv', 'categoria', 'coste', 'textoControlling'];
const CAMPOS_ECONOMICOS = ['horasNetas', 'horasSetup', 'piezas', 'proyecto', 'hv', 'categoria', 'coste'];

/**
 * Calcula (o recalcula) una línea: valor de origen, valores aplicados con los
 * ajustes, incidencias y estado. `previa` conserva exclusión, aplazamiento,
 * ajustes, propuesta de tarifa e historial.
 */
function calcularLinea(e, perEjec, perFact, maestros, previa, ctx) {
  const t = tramo(e, perEjec, ctx.ahoraMin);
  const linea = previa ? clonar(previa) : {
    id: `${e.id}@${perEjec.id}`, ensayoId: e.id, periodoEjecucion: perEjec.id, periodoFacturacion: perFact.id,
    origen: perEjec.id === perFact.id ? 'PERIODO' : 'APLAZADA', ajustes: {}, propuestaTarifa: null, exclusion: null,
    aplazamiento: null, historial: [],
  };
  linea.periodoFacturacion = perFact.id;
  Object.assign(linea, { banco: e.banco, organizacion: e.organizacion, tipoEnsayo: e.tipoEnsayo, responsable: e.responsable,
    proyectoTexto: e.proyecto, approvalId: e.approvalId, cerrado: e.cerrado });
  if (linea.exclusion && linea.exclusion.origen === 'ENSAYO') linea.exclusion = null;
  if (!e.billing.billable) linea.exclusion = { por: e.billing.billableBy || '', en: e.billing.billableAt || '', origen: 'ENSAYO' };
  const incidencias = [];
  // Sin arranque real: pendiente (planificado) o incidencia (arranque asumido).
  if (!t && e.fechaInicio) return null; // arrancado, pero sin horas dentro de este periodo
  if (!t) {
    linea.pendiente = true;
    Object.assign(linea, { inicioReal: '', finReal: '', tramoInicio: '', tramoFin: '', abierto: !e.cerrado });
    if (e.inicioAsumido) incidencias.push(incidencia('FALTA_FECHA_REAL', BLOQ));
    linea.calculado = null;
    linea.aplicado = null;
    linea.incidencias = incidencias;
    linea.estado = estadoDeLinea(linea);
    linea.huella = '';
    return linea;
  }
  linea.pendiente = false;
  Object.assign(linea, { inicioReal: t.inicioReal, finReal: t.finReal, tramoInicio: t.tramoInicio, tramoFin: t.tramoFin, abierto: t.abierto });
  incidencias.push(...t.incidencias);
  const b = e.billing;
  const pr = proyectoDeTexto(e.proyecto);
  const setup = t.contieneInicio ? (b.setupHours === '' || b.setupHours === null ? 0 : Number(b.setupHours)) : 0;
  const piezas = t.contieneInicio ? (b.pieces === '' || b.pieces === null ? null : Number(b.pieces)) : 0;
  const ordenEx = !!(b.orderException && b.orderException.justification);
  // Valor de origen (sin ajustes).
  const origen = resolverEconomia(e, { proyecto: pr.numero, orden: b.order, categoria: '', hv: b.hv, ordenExcepcional: ordenEx }, maestros, perFact);
  const calc = Object.assign({ minutosBrutos: t.minutosBrutos, minutosFinDeSemana: t.minutosFinDeSemana, minutosParadas: t.minutosParadas,
    minutosNetos: t.minutosNetos, horasNetas: t.horasNetas, horasSetup: setup, piezas }, origen.r);
  calc.horasTotales = calc.horasNetas + calc.horasSetup;
  calc.cantidad = calc.unidad === 'PIEZAS' ? calc.piezas : calc.horasTotales;
  calc.coste = calc.regla && calc.cantidad !== null ? costePorRegla(calc.regla, calc.cantidad, calc.tarifa) : null;
  if (calc.coste !== null && pr.numero === '0') calc.coste = 0;
  calc.textoControlling = calc.categoria;
  // Conflictos de ajustes con el nuevo valor de origen (se avisa, no se pisa).
  Object.entries(linea.ajustes || {}).forEach(([campo, aj]) => {
    const nuevo = calc[campo] ?? null;
    if (!igual(aj.original, nuevo)) aj.conflicto = { anterior: aj.original, nuevo };
    else delete aj.conflicto;
  });
  // Valores aplicados.
  const aj = (campo, defecto) => (linea.ajustes && linea.ajustes[campo] ? linea.ajustes[campo].valor : defecto);
  const ajustado = resolverEconomia(e, {
    proyecto: aj('proyecto', pr.numero), orden: aj('orden', b.order), categoria: aj('categoria', ''), hv: aj('hv', b.hv),
    ordenExcepcional: ordenEx || !!(linea.ajustes && linea.ajustes.orden && linea.ajustes.orden.excepcional),
  }, maestros, perFact);
  const ap = Object.assign({ horasNetas: Number(aj('horasNetas', calc.horasNetas)), horasSetup: Number(aj('horasSetup', calc.horasSetup)),
    piezas: aj('piezas', calc.piezas) === null ? null : Number(aj('piezas', calc.piezas)) }, ajustado.r);
  const prop = linea.propuestaTarifa;
  if (prop && prop.estado === 'APROBADA' && prop.alcance === 'LINEA') {
    ap.tarifa = Number(prop.tarifaFinal);
    ap.tarifaId = 'EXCEPCIONAL';
  }
  ap.horasTotales = ap.horasNetas + ap.horasSetup;
  ap.cantidad = ap.unidad === 'PIEZAS' ? ap.piezas : ap.horasTotales;
  ap.costeCalculado = ap.regla && ap.cantidad !== null ? costePorRegla(ap.regla, ap.cantidad, ap.tarifa) : null;
  if (ap.costeCalculado !== null && String(ap.proyecto) === '0') ap.costeCalculado = 0;
  ap.coste = linea.ajustes && linea.ajustes.coste ? Number(linea.ajustes.coste.valor) : ap.costeCalculado;
  ap.textoControlling = aj('textoControlling', ap.categoria);
  incidencias.push(...ajustado.inc);
  if (String(ap.proyecto) === '0') incidencias.push(incidencia('PROYECTO_CERO', ADV));
  if (ap.regla === 'NOT_BILLABLE' && !linea.exclusion) linea.exclusion = { por: '', en: '', origen: 'REGLA' };
  if (linea.exclusion && linea.exclusion.origen === 'REGLA' && ap.regla !== 'NOT_BILLABLE') linea.exclusion = null;
  if (t.contieneInicio && !b.setupConfirmed && !(linea.ajustes && linea.ajustes.horasSetup)) incidencias.push(incidencia('SETUP_NO_CONFIRMADO', BLOQ));
  if (ap.unidad === 'PIEZAS' && (ap.piezas === null || !Number.isFinite(ap.piezas))) incidencias.push(incidencia('PIEZAS_SIN_INDICAR', BLOQ));
  if (prop && prop.estado === 'PENDIENTE') incidencias.push(incidencia('TARIFA_PENDIENTE', BLOQ, '', true));
  const sinValidar = Object.values(linea.ajustes || {}).some((x) => x.economico && !x.validado);
  if (sinValidar) incidencias.push(incidencia('AJUSTE_SIN_VALIDAR', BLOQ, '', true));
  if (ap.horasNetas > Number((maestros.parametros || {}).umbralHorasAltas || 720)) incidencias.push(incidencia('HORAS_ALTAS', ADV, `(${ap.horasNetas} h)`));
  if (Object.keys(linea.ajustes || {}).length) incidencias.push(incidencia('AJUSTE_MANUAL', ADV));
  if (Object.values(linea.ajustes || {}).some((x) => x.conflicto)) incidencias.push(incidencia('CAMBIO_POSTERIOR', ADV));
  if (ap.coste === 0 && ap.regla !== 'NOT_BILLABLE') incidencias.push(incidencia('COSTE_CERO', ADV));
  if (linea.origen === 'APLAZADA') incidencias.push(incidencia('DE_PERIODO_ANTERIOR', ADV, `(ejecución: ${periodoDeId(linea.periodoEjecucion).nombre})`));
  linea.calculado = calc;
  linea.aplicado = ap;
  linea.incidencias = incidencias;
  linea.huella = huellaDe(calc);
  linea.estado = estadoDeLinea(linea);
  return linea;
}

/** Huella del valor de origen: detecta cambios del ensayo después de aprobar. */
function huellaDe(c) {
  return [c.horasNetas, c.horasSetup, c.piezas, c.proyecto, c.orden, c.categoria, c.hv, c.tarifa, c.coste === null ? '' : redondear2(c.coste)]
    .map((x) => (x === null || x === undefined ? '' : String(x))).join('|');
}

function estadoDeLinea(l) {
  if (l.exclusion) return 'EXCLUDED';
  if (l.aplazamiento) return 'DEFERRED';
  if (l.incidencias.some((i) => i.nivel === BLOQ)) return 'BLOCKED';
  if (l.pendiente) return 'PENDING';
  const adv = l.incidencias.filter((i) => i.nivel === ADV);
  if (Object.keys(l.ajustes || {}).length && adv.every((i) => ['AJUSTE_MANUAL', 'ORDEN_EXCEPCIONAL'].includes(i.codigo))) return 'ADJUSTED';
  if (adv.length) return 'WARNING';
  return 'READY';
}
/** Línea que suma horas e importe. */
const cuenta = (l) => !l.exclusion && !l.aplazamiento && !l.pendiente && !!l.aplicado;

function igual(a, b) {
  if (a === b) return true;
  if (a === null || b === null || a === undefined || b === undefined) return false;
  if (typeof a === 'number' || typeof b === 'number') return Math.abs(Number(a) - Number(b)) < 1e-9;
  return String(a) === String(b);
}
const clonar = (x) => JSON.parse(JSON.stringify(x));

// ---- periodo completo ------------------------------------------------------------------

/**
 * Selección de ensayos y cálculo de las líneas del periodo.
 * @param doc        documento del periodo (lineas previas conservan su estado manual)
 * @param ensayos    ensayos facturables (ensayoFacturable)
 * @param otros      documentos de los demás periodos {id: doc}
 * @param ctx        {ahora: 'aaaa-mm-ddThh:mm'}
 */
function calcularPeriodo(doc, ensayos, maestros, otros, ctx) {
  const per = periodoDeId(doc.id);
  const c = { ahoraMin: ctx.ahora ? aMin(ctx.ahora) : null };
  const previas = new Map((doc.lineas || []).map((l) => [l.id, l]));
  const porId = new Map(ensayos.map((e) => [e.id, e]));
  const lineas = [];
  const vistos = new Set();
  const iniPer = per.inicio;
  const finPer = per.fin;
  for (const e of ensayos) {
    if (e.tipoEspecial) continue;
    const id = `${e.id}@${per.id}`;
    let ocupa = false;
    if (e.fechaInicio) {
      ocupa = e.fechaInicio <= finPer && (!e.fechaFin || e.fechaFin >= iniPer);
    } else if (!e.cerrado && e.inicioPlan && e.finPlan) {
      ocupa = e.inicioPlan <= finPer && e.finPlan >= iniPer;
    }
    if (!ocupa) continue;
    const l = calcularLinea(e, per, per, maestros, previas.get(id) || null, c);
    if (!l) continue;
    lineas.push(l);
    vistos.add(id);
  }
  // Líneas aplazadas desde el periodo anterior.
  const ant = otros[anteriorId(per.id)];
  if (ant) {
    const origenAnt = fotoVigente(ant) || ant.lineas || [];
    origenAnt.filter((l) => l.aplazamiento && l.aplazamiento.a === per.id).forEach((la) => {
      const e = porId.get(la.ensayoId);
      const id = la.id;
      const previa = previas.get(id) || Object.assign(clonar(la), { aplazamiento: null, origen: 'APLAZADA', historial: [...(la.historial || []),
        { en: ctx.ahora || '', por: '', accion: 'Recibida aplazada', campo: '', de: per.id === la.periodoFacturacion ? '' : la.periodoFacturacion, a: per.id, justificacion: '' }] });
      if (!e) {
        lineas.push(huerfana(previa));
      } else {
        const l = calcularLinea(e, periodoDeId(la.periodoEjecucion), per, maestros, previa, c) || huerfana(previa);
        l.origen = 'APLAZADA';
        if (!l.incidencias.some((i) => i.codigo === 'DE_PERIODO_ANTERIOR')) l.incidencias.push(incidencia('DE_PERIODO_ANTERIOR', ADV));
        l.estado = estadoDeLinea(l);
        lineas.push(l);
      }
      vistos.add(id);
    });
  }
  // Líneas previas con trabajo manual que ya no aparecen: se conservan bloqueadas.
  for (const [id, l] of previas) {
    if (vistos.has(id)) continue;
    const manual = Object.keys(l.ajustes || {}).length || l.propuestaTarifa || (l.exclusion && l.exclusion.origen === 'LINEA') || l.aplazamiento;
    if (manual) lineas.push(huerfana(l));
  }
  // Duplicados: tramo ya incluido en un periodo aprobado / líneas solapadas del mismo ensayo.
  const aprobadas = new Set();
  Object.values(otros).forEach((o) => {
    if (o.id === per.id) return;
    (fotoVigente(o) || []).forEach((l) => { if (cuenta(l)) aprobadas.add(l.id); });
  });
  const porEnsayo = new Map();
  lineas.forEach((l) => {
    if (aprobadas.has(l.id) && !l.exclusion) l.incidencias.push(incidencia('YA_FACTURADO', BLOQ));
    if (!porEnsayo.has(l.ensayoId)) porEnsayo.set(l.ensayoId, []);
    porEnsayo.get(l.ensayoId).push(l);
  });
  porEnsayo.forEach((ls) => {
    const con = ls.filter((l) => l.tramoInicio && !l.exclusion);
    for (let i = 0; i < con.length; i++) {
      for (let j = i + 1; j < con.length; j++) {
        if (solape(aMin(con[i].tramoInicio), aMin(con[i].tramoFin), aMin(con[j].tramoInicio), aMin(con[j].tramoFin)) > 0) {
          con[i].incidencias.push(incidencia('LINEAS_SOLAPADAS', BLOQ));
          con[j].incidencias.push(incidencia('LINEAS_SOLAPADAS', BLOQ));
        }
      }
    }
  });
  lineas.forEach((l) => { l.estado = estadoDeLinea(l); });
  lineas.sort(ordenLineas);
  doc.lineas = lineas;
  doc.resumenRapido = resumenRapido(doc);
  return doc;
}
function huerfana(l) {
  const h = clonar(l);
  h.incidencias = [incidencia('ENSAYO_NO_ENCONTRADO', BLOQ)];
  h.estado = estadoDeLinea(h);
  return h;
}
function ordenLineas(a, b) {
  return comparar([a.banco || '', a.tramoInicio || a.periodoEjecucion, a.ensayoId, a.periodoEjecucion],
    [b.banco || '', b.tramoInicio || b.periodoEjecucion, b.ensayoId, b.periodoEjecucion]);
}
/** Líneas de la última foto aprobada (null si el periodo no está aprobado). */
function fotoVigente(doc) {
  if (!doc || (doc.estado !== 'APROBADO' && doc.estado !== 'EXPORTADO')) return null;
  const f = (doc.fotos || []).find((x) => x.version === doc.versionAprobada);
  return f ? f.lineas : null;
}

// ---- resúmenes (overview, Excel y HTML) -------------------------------------------------

function resumenRapido(doc) {
  const ls = doc.lineas || [];
  const contadas = ls.filter(cuenta);
  return {
    ensayos: new Set(ls.map((l) => l.ensayoId)).size, lineas: ls.length,
    incidencias: ls.reduce((n, l) => n + l.incidencias.filter((i) => i.nivel === BLOQ || i.nivel === ADV).length, 0),
    bloqueadas: ls.filter((l) => l.estado === 'BLOCKED').length,
    importe: redondear2(contadas.reduce((s, l) => s + (l.aplicado.coste || 0), 0)),
  };
}

function agrupar(lineas, clave, extra) {
  const grupos = new Map();
  lineas.forEach((l) => {
    const k = clave(l);
    if (!grupos.has(k.id)) grupos.set(k.id, Object.assign({ clave: k.id, etiqueta: k.etiqueta, ensayos: new Set(), lineas: [], horas: 0, setup: 0, horasTotales: 0, piezas: 0, coste: 0 }, k.datos || {}));
    const g = grupos.get(k.id);
    g.ensayos.add(l.ensayoId);
    g.lineas.push(l.id);
    g.horas += l.aplicado.horasNetas;
    g.setup += l.aplicado.horasSetup;
    g.horasTotales += l.aplicado.horasTotales;
    g.piezas += l.aplicado.unidad === 'PIEZAS' ? (l.aplicado.piezas || 0) : 0;
    g.coste += l.aplicado.coste || 0;
    if (extra) extra(g, l);
  });
  return [...grupos.values()].map((g) => Object.assign(g, { ensayos: g.ensayos.size }));
}

const DIMENSIONES = {
  proyecto: (l) => ({ id: l.aplicado.proyecto || '', etiqueta: `${l.aplicado.proyecto || '—'}${l.aplicado.proyectoNombre ? ' · ' + l.aplicado.proyectoNombre : ''}` }),
  orden: (l) => ({ id: l.aplicado.orden || '', etiqueta: l.aplicado.orden || '—' }),
  banco: (l) => ({ id: l.banco, etiqueta: l.banco }),
  categoria: (l) => ({ id: l.aplicado.categoria || '', etiqueta: l.aplicado.categoria || '—' }),
  tipoEnsayo: (l) => ({ id: l.tipoEnsayo || '', etiqueta: l.tipoEnsayo || '—' }),
  planta: (l) => ({ id: l.organizacion || '', etiqueta: l.organizacion || '—' }),
  lineaProducto: (l) => ({ id: l.aplicado.lineaProducto || '', etiqueta: l.aplicado.lineaProducto || '—' }),
  requester: (l) => ({ id: l.aplicado.requester || '', etiqueta: l.aplicado.requester || '—' }),
  hv: (l) => ({ id: l.aplicado.hv || '', etiqueta: l.aplicado.hv === 'yes' ? 'Alta tensión: Sí' : l.aplicado.hv === 'no' ? 'Alta tensión: No' : '—' }),
};

/** Todo lo que muestra el overview y lo que exportan el Excel y el HTML. */
function resumen(doc, maestros) {
  const ls = doc.lineas || [];
  const contadas = ls.filter(cuenta);
  const ordenCat = new Map((maestros.categorias || []).map((c) => [c.codigo, Number(c.ordenControlling) || 999]));
  const suma = (f) => contadas.reduce((s, l) => s + (f(l) || 0), 0);
  const indicadores = {
    coste: suma((l) => l.aplicado.coste), horasNetas: suma((l) => l.aplicado.horasNetas), horasSetup: suma((l) => l.aplicado.horasSetup),
    horasTotales: suma((l) => l.aplicado.horasTotales),
    ensayos: new Set(ls.map((l) => l.ensayoId)).size, lineas: ls.length, lineasContadas: contadas.length,
    preparadas: ls.filter((l) => l.estado === 'READY' || l.estado === 'ADJUSTED').length,
    bloqueadas: ls.filter((l) => l.estado === 'BLOCKED').length,
    advertencias: ls.filter((l) => l.estado === 'WARNING').length,
    excluidas: ls.filter((l) => l.estado === 'EXCLUDED').length,
    aplazadas: ls.filter((l) => l.estado === 'DEFERRED').length,
    pendientes: ls.filter((l) => l.estado === 'PENDING').length,
    ajustadas: ls.filter((l) => Object.keys(l.ajustes || {}).length).length,
    ajustesPendientes: ls.filter((l) => (l.propuestaTarifa && l.propuestaTarifa.estado === 'PENDIENTE')
      || Object.values(l.ajustes || {}).some((a) => a.economico && !a.validado)).length,
    porEstado: {},
  };
  ls.forEach((l) => { indicadores.porEstado[l.estado] = (indicadores.porEstado[l.estado] || 0) + 1; });
  const porProyectoOrden = agrupar(contadas, (l) => ({ id: `${l.aplicado.proyecto}|${l.aplicado.orden}`, etiqueta: l.aplicado.proyecto,
    datos: { proyecto: l.aplicado.proyecto, proyectoNombre: l.aplicado.proyectoNombre, orden: l.aplicado.orden } }))
    .sort((a, b) => comparar([a.proyecto, a.orden], [b.proyecto, b.orden]));
  const porEquipo = agrupar(contadas, (l) => ({ id: l.aplicado.categoria, etiqueta: l.aplicado.categoria,
    datos: { categoria: l.aplicado.categoria, grupo: l.aplicado.grupo, regla: l.aplicado.regla, unidad: l.aplicado.unidad, tarifas: new Set(), bancos: new Set() } }),
  (g, l) => { if (l.aplicado.tarifa !== null) g.tarifas.add(l.aplicado.tarifa); g.bancos.add(l.banco); })
    .map((g) => Object.assign(g, { tarifas: [...g.tarifas].sort((a, b) => a - b), bancos: [...g.bancos].sort(), noLineal: g.regla !== 'LINEAR' && g.regla !== 'PER_PIECE' }))
    .sort((a, b) => comparar([ordenCat.get(a.categoria) ?? 999, a.categoria], [ordenCat.get(b.categoria) ?? 999, b.categoria]));
  const porPlanta = agrupar(contadas, DIMENSIONES.planta).sort((a, b) => comparar([a.clave], [b.clave]));
  const desgloses = {};
  Object.entries(DIMENSIONES).forEach(([k, f]) => { desgloses[k] = agrupar(contadas, f).sort((a, b) => b.coste - a.coste || comparar([a.clave], [b.clave])); });
  // Tabla del Excel por meses: línea de producto → orden → requester × grupo (Tipo).
  const grupos = [...new Set(contadas.map((l) => l.aplicado.grupo || ''))].sort((a, b) => comparar([ordenGrupo(a), a], [ordenGrupo(b), b]));
  const filas = [];
  const nodo = (nivel, etiqueta, ls2) => {
    const valores = {};
    grupos.forEach((g) => { const s = ls2.filter((l) => (l.aplicado.grupo || '') === g).reduce((x, l) => x + (l.aplicado.coste || 0), 0); if (ls2.some((l) => (l.aplicado.grupo || '') === g)) valores[g] = s; });
    filas.push({ nivel, etiqueta, valores, total: ls2.reduce((x, l) => x + (l.aplicado.coste || 0), 0) });
  };
  const ordenar = (xs) => [...new Set(xs)].sort((a, b) => comparar([a.toLowerCase()], [b.toLowerCase()]));
  ordenar(contadas.map((l) => l.aplicado.lineaProducto || '(sin línea)')).forEach((lp) => {
    const a = contadas.filter((l) => (l.aplicado.lineaProducto || '(sin línea)') === lp);
    nodo(0, lp, a);
    ordenar(a.map((l) => l.aplicado.orden || '(sin orden)')).forEach((o) => {
      const b = a.filter((l) => (l.aplicado.orden || '(sin orden)') === o);
      nodo(1, o, b);
      ordenar(b.map((l) => l.aplicado.requester || '(sin requester)')).forEach((r) => nodo(2, r, b.filter((l) => (l.aplicado.requester || '(sin requester)') === r)));
    });
  });
  nodo(-1, 'Total general', contadas);
  const porLineaProducto = { columnas: grupos, filas };
  const incidencias = [];
  ls.forEach((l) => l.incidencias.forEach((i) => incidencias.push({ lineaId: l.id, ensayoId: l.ensayoId, banco: l.banco, estado: l.estado, ...i })));
  incidencias.sort((a, b) => comparar([a.nivel === BLOQ ? 0 : 1, a.banco, a.ensayoId, a.codigo], [b.nivel === BLOQ ? 0 : 1, b.banco, b.ensayoId, b.codigo]));
  return { indicadores, porProyectoOrden, porEquipo, porPlanta, porLineaProducto, desgloses, controlling: controlling(contadas, maestros), incidencias };
}
const ORDEN_GRUPOS = ['Shakers', 'HVCH (STC)', 'HVCH (CTAG)', 'Climático', 'RACS'];
const ordenGrupo = (g) => (ORDEN_GRUPOS.includes(g) ? ORDEN_GRUPOS.indexOf(g) : 50);

/**
 * Hoja «Controlling - Internal Testing»: una fila por categoría (Equipo) y orden,
 * en el orden de categorías del Excel; Amount = ROUND(suma de costes; 2).
 */
function controlling(lineas, maestros) {
  const p = Object.assign({ cctrOld: '6463-41', costElement: '420000', moneda: 'EUR', unidadHoras: 'H', unidadPiezas: 'PC' }, maestros.controlling || {});
  const ordenCat = new Map((maestros.categorias || []).map((c) => [c.codigo, Number(c.ordenControlling) || 999]));
  const filas = new Map();
  lineas.forEach((l) => {
    const a = l.aplicado;
    const texto = a.textoControlling || a.categoria;
    const k = `${a.categoria}|${String(a.orden || '').trim()}|${texto}`;
    if (!filas.has(k)) filas.set(k, { equipo: a.categoria, orden: a.orden, texto, unidad: a.unidad, coste: 0, cantidad: 0, lineas: [] });
    const f = filas.get(k);
    f.coste += a.coste || 0;
    f.cantidad += a.unidad === 'PIEZAS' ? (a.piezas || 0) : a.horasTotales;
    f.lineas.push({ lineaId: l.id, ensayoId: l.ensayoId, banco: l.banco, tipoEnsayo: l.tipoEnsayo, coste: a.coste || 0, cantidad: a.cantidad });
  });
  return [...filas.values()]
    .sort((x, y) => comparar([ordenCat.get(x.equipo) ?? 999, x.equipo, String(x.orden || ''), x.texto], [ordenCat.get(y.equipo) ?? 999, y.equipo, String(y.orden || ''), y.texto]))
    .map((f) => ({
      equipo: f.equipo, orden: f.orden, sumaCoste: f.coste, sumaHoras: f.cantidad, cctrOld: p.cctrOld, orderOld: '', operOld: '',
      costElement: p.costElement, amount: redondeoExcel2(f.coste), crcy: p.moneda, cctrNew: '', orderNew: String(f.orden || '').trim(),
      operNew: '', totalQty: f.cantidad, um: f.unidad === 'PIEZAS' ? p.unidadPiezas : p.unidadHoras, persNo: '', text: f.texto, lineas: f.lineas,
    }));
}

// ---- flujo del periodo -------------------------------------------------------------------

const ahoraTexto = (ctx) => ctx.ahora || '';
function exigir(cond, mensaje) { if (!cond) throw new ErrorRegla(mensaje); }
const editable = (doc) => doc.estado === 'BORRADOR' || doc.estado === 'EN_REVISION';

function nuevoPeriodo(anio, mes, usuario, ctx) {
  const p = periodo(anio, mes);
  return Object.assign(p, { estado: 'BORRADOR', revision: 0, creadoPor: usuario.nombre, creadoEn: ahoraTexto(ctx), ultimoCalculo: '',
    calculadoPor: '', lineas: [], versionAprobada: 0, fotos: [], reaperturas: [], exportaciones: [], envios: [], resumenRapido: null });
}

/** Comprobaciones antes de enviar a revisión (también se muestran en la pantalla). */
function comprobacionesEnvio(doc) {
  // Las líneas excluidas o aplazadas no bloquean el periodo.
  const ls = (doc.lineas || []).filter((l) => !l.exclusion && !l.aplazamiento);
  const bloqueos = ls.filter((l) => l.incidencias.some((i) => i.nivel === BLOQ && !i.soloAprobacion));
  const errores = [];
  if (bloqueos.length) errores.push(`Hay ${bloqueos.length} línea(s) con errores bloqueantes.`);
  if (!ls.some((l) => cuenta(l) && (l.aplicado.coste || 0) >= 0)) errores.push('No hay ninguna línea facturable.');
  const setup = ls.filter((l) => l.incidencias.some((i) => i.codigo === 'SETUP_NO_CONFIRMADO')).length;
  if (setup) errores.push(`Faltan ${setup} setup(s) por confirmar.`);
  const orden = ls.filter((l) => l.incidencias.some((i) => ['ORDEN_NO_SELECCIONADA', 'ORDEN_NO_VALIDA', 'SIN_ORDEN'].includes(i.codigo))).length;
  if (orden) errores.push(`Faltan ${orden} orden(es) por completar.`);
  const tarifa = ls.filter((l) => cuenta(l) && (l.aplicado.tarifa === null || !l.aplicado.regla)).length;
  if (tarifa) errores.push(`${tarifa} línea(s) sin tarifa o sin regla.`);
  return errores;
}
function comprobacionesAprobacion(doc) {
  const errores = comprobacionesEnvio(doc);
  const pend = doc.lineas.filter((l) => !l.exclusion && !l.aplazamiento && l.incidencias.some((i) => i.soloAprobacion)).length;
  if (pend) errores.push(`Quedan ${pend} propuesta(s) o ajuste(s) económicos sin resolver.`);
  return errores;
}

function registrar(linea, usuario, ctx, accion, campo, de, a, justificacion) {
  linea.historial = linea.historial || [];
  linea.historial.push({ en: ahoraTexto(ctx), por: usuario.nombre, accion, campo: campo || '', de: de === undefined ? '' : de, a: a === undefined ? '' : a, justificacion: justificacion || '' });
}

/**
 * Acción sobre una línea. Devuelve los eventos de auditoría que genera.
 * El llamador recalcula la línea después (calcularLinea con la línea modificada).
 */
function accionLinea(doc, linea, accion, datos, usuario, ctx) {
  exigir(editable(doc), 'El periodo está aprobado: no se puede modificar (un Admin puede reabrirlo).');
  const esAdmin = usuario.rol === 'admin';
  const auditoria = [];
  const costeAntes = linea.aplicado ? linea.aplicado.coste : null;
  const just = String(datos.justificacion || '').trim();
  switch (accion) {
    case 'ajustar': {
      const campos = datos.campos || {};
      Object.entries(campos).forEach(([campo, valor]) => {
        exigir(CAMPOS_AJUSTABLES.includes(campo), `Campo no ajustable: ${campo}`);
        const calc = linea.calculado ? linea.calculado[campo] ?? null : null;
        let v = valor;
        if (['horasNetas', 'horasSetup', 'piezas', 'coste'].includes(campo)) {
          v = Number(valor);
          exigir(Number.isFinite(v) && v >= 0, `Valor no válido para ${campo}.`);
          if (campo === 'horasNetas' || campo === 'horasSetup') exigir(Math.abs(v * 4 - Math.round(v * 4)) < 1e-9, 'Las horas van en incrementos de 0,25 h.');
        }
        if (campo === 'hv') exigir(v === 'yes' || v === 'no', 'HV debe ser Sí o No.');
        if (campo === 'proyecto') v = numeroCanonico(v);
        const anterior = linea.ajustes[campo] ? linea.ajustes[campo].valor : (linea.aplicado ? linea.aplicado[campo] : null);
        if (igual(v, calc)) { delete linea.ajustes[campo]; } else {
          linea.ajustes[campo] = { valor: v, original: calc, por: usuario.nombre, rol: usuario.rol, en: ahoraTexto(ctx), justificacion: just,
            economico: CAMPOS_ECONOMICOS.includes(campo), validado: esAdmin };
          if (campo === 'orden' && esAdmin && datos.excepcional) linea.ajustes.orden.excepcional = true;
        }
        registrar(linea, usuario, ctx, 'Ajuste', campo, anterior, v, just);
      });
      break;
    }
    case 'restaurar': {
      const campos = datos.campo ? [datos.campo] : Object.keys(linea.ajustes || {});
      campos.forEach((c) => { if (linea.ajustes[c]) { registrar(linea, usuario, ctx, 'Restaurar valor calculado', c, linea.ajustes[c].valor, linea.ajustes[c].original, just); delete linea.ajustes[c]; } });
      break;
    }
    case 'resolverCambio': {
      const aj = linea.ajustes[datos.campo];
      exigir(aj && aj.conflicto, 'Ese ajuste no tiene cambios pendientes.');
      if (datos.opcion === 'recalculado') {
        registrar(linea, usuario, ctx, 'Usar valor recalculado', datos.campo, aj.valor, aj.conflicto.nuevo, just);
        delete linea.ajustes[datos.campo];
      } else {
        registrar(linea, usuario, ctx, 'Conservar ajuste', datos.campo, aj.conflicto.nuevo, aj.valor, just);
        aj.original = aj.conflicto.nuevo;
        delete aj.conflicto;
      }
      break;
    }
    case 'validarAjuste': {
      exigir(esAdmin, 'Solo un Admin puede aprobar ajustes económicos.');
      Object.entries(linea.ajustes || {}).forEach(([c, aj]) => { if (aj.economico && !aj.validado) { aj.validado = true; aj.validadoPor = usuario.nombre; aj.validadoEn = ahoraTexto(ctx); registrar(linea, usuario, ctx, 'Ajuste validado', c, '', aj.valor, ''); } });
      auditoria.push({ accion: 'AJUSTE_VALIDADO', detalle: `Ajustes de ${linea.id} validados` });
      break;
    }
    case 'rechazarAjuste': {
      exigir(esAdmin, 'Solo un Admin puede rechazar ajustes económicos.');
      Object.entries(linea.ajustes || {}).forEach(([c, aj]) => { if (aj.economico && !aj.validado) { registrar(linea, usuario, ctx, 'Ajuste rechazado', c, aj.valor, aj.original, just); delete linea.ajustes[c]; } });
      auditoria.push({ accion: 'AJUSTE_RECHAZADO', detalle: `Ajustes de ${linea.id} rechazados`, justificacion: just });
      break;
    }
    case 'excluir':
      exigir(!linea.exclusion, 'La línea ya está excluida.');
      linea.exclusion = { por: usuario.nombre, en: ahoraTexto(ctx), origen: 'LINEA' };
      registrar(linea, usuario, ctx, 'Excluir', '', '', '', just);
      auditoria.push({ accion: 'LINEA_EXCLUIDA', detalle: linea.id, justificacion: just });
      break;
    case 'incluir':
      exigir(linea.exclusion && linea.exclusion.origen === 'LINEA', linea.exclusion ? 'La exclusión viene de la ficha del ensayo o de la regla de la categoría.' : 'La línea no está excluida.');
      linea.exclusion = null;
      registrar(linea, usuario, ctx, 'Volver a incluir', '', '', '', just);
      auditoria.push({ accion: 'LINEA_INCLUIDA', detalle: linea.id, justificacion: just });
      break;
    case 'aplazar':
      exigir(!linea.aplazamiento, 'La línea ya está aplazada.');
      exigir(!linea.pendiente, 'Una línea pendiente no tiene importe que aplazar.');
      linea.aplazamiento = { a: siguienteId(doc.id), por: usuario.nombre, en: ahoraTexto(ctx) };
      registrar(linea, usuario, ctx, 'Aplazar al periodo siguiente', 'periodoFacturacion', doc.id, linea.aplazamiento.a, just);
      auditoria.push({ accion: 'LINEA_APLAZADA', detalle: `${linea.id} → ${linea.aplazamiento.a}`, justificacion: just });
      break;
    case 'deshacerAplazamiento':
      exigir(linea.aplazamiento, 'La línea no está aplazada.');
      registrar(linea, usuario, ctx, 'Deshacer aplazamiento', 'periodoFacturacion', linea.aplazamiento.a, doc.id, just);
      auditoria.push({ accion: 'APLAZAMIENTO_DESHECHO', detalle: linea.id, justificacion: just });
      linea.aplazamiento = null;
      break;
    case 'proponerTarifa': {
      exigir(linea.aplicado && linea.aplicado.tarifa !== null, 'La línea no tiene tarifa calculada.');
      const t = Number(datos.tarifa);
      exigir(Number.isFinite(t) && t >= 0, 'Indica la tarifa propuesta.');
      exigir(just, 'La justificación es obligatoria.');
      const calculada = linea.calculado.tarifa;
      const conPropuesta = costePorRegla(linea.aplicado.regla, linea.aplicado.cantidad, t);
      linea.propuestaTarifa = { calculada, propuesta: t, diferencia: (conPropuesta ?? 0) - (linea.aplicado.costeCalculado ?? 0), por: usuario.nombre,
        en: ahoraTexto(ctx), justificacion: just, estado: 'PENDIENTE', resueltaPor: '', resueltaEn: '', alcance: '', tarifaFinal: null };
      registrar(linea, usuario, ctx, 'Propuesta de tarifa excepcional', 'tarifa', calculada, t, just);
      auditoria.push({ accion: 'TARIFA_PROPUESTA', detalle: `${linea.id}: ${calculada} → ${t}`, justificacion: just });
      break;
    }
    case 'resolverTarifa': {
      exigir(esAdmin, 'Solo un Admin puede resolver tarifas excepcionales.');
      const p = linea.propuestaTarifa;
      exigir(p && p.estado === 'PENDIENTE', 'No hay ninguna propuesta pendiente.');
      const decision = datos.decision;
      exigir(['aprobar', 'rechazar', 'sustituir'].includes(decision), 'Decisión no válida.');
      p.resueltaPor = usuario.nombre;
      p.resueltaEn = ahoraTexto(ctx);
      if (decision === 'rechazar') {
        p.estado = 'RECHAZADA';
      } else {
        const final = decision === 'sustituir' ? Number(datos.tarifa) : p.propuesta;
        exigir(Number.isFinite(final) && final >= 0, 'Indica la tarifa que sustituye a la propuesta.');
        p.estado = 'APROBADA';
        p.tarifaFinal = final;
        p.alcance = datos.alcance === 'MAESTRA' ? 'MAESTRA' : 'LINEA';
      }
      registrar(linea, usuario, ctx, `Tarifa excepcional ${p.estado.toLowerCase()}`, 'tarifa', p.calculada, p.tarifaFinal ?? '', just);
      auditoria.push({ accion: 'TARIFA_RESUELTA', detalle: `${linea.id}: ${p.estado}${p.alcance ? ' (' + p.alcance + ')' : ''} ${p.tarifaFinal ?? ''}`, justificacion: just });
      break;
    }
    default:
      throw new ErrorRegla(`Acción desconocida: ${accion}`);
  }
  return { auditoria, costeAntes };
}

/** Tras recalcular la línea: justificación obligatoria si el importe cambia (se comprueba antes de guardar). */
function exigirJustificacion(costeAntes, costeDespues, justificacion, accion) {
  if (['excluir', 'incluir', 'aplazar', 'deshacerAplazamiento', 'validarAjuste'].includes(accion)) return;
  const cambia = !igual(costeAntes === null ? null : redondear2(costeAntes), costeDespues === null ? null : redondear2(costeDespues));
  if (cambia && !String(justificacion || '').trim()) throw new ErrorRegla('Justificación obligatoria: el cambio modifica el importe.');
}

/** Nueva tarifa maestra a partir de una propuesta aprobada con alcance MAESTRA. */
function tarifaMaestraDePropuesta(maestros, linea, doc, usuario, ctx) {
  const a = linea.aplicado;
  const desde = doc.inicio;
  const anteriores = (maestros.tarifas || []).filter((t) => t.categoria === a.categoria && (t.hv === a.hv) && !t.proyecto && !t.planta && !t.lineaProducto && vigente(t, doc.fin));
  anteriores.forEach((t) => { t.hasta = sumarDias(desde, -1); });
  const nueva = { id: `T-${doc.id}-${linea.ensayoId}`, categoria: a.categoria, proyecto: '', planta: '', lineaProducto: '', hv: a.hv,
    importe: linea.propuestaTarifa.tarifaFinal, unidad: a.unidad, desde, hasta: '', nota: `Tarifa excepcional aprobada por ${usuario.nombre} (${linea.id})` };
  maestros.tarifas.push(nueva);
  return nueva;
}

function enviarRevision(doc, usuario, ctx) {
  exigir(doc.estado === 'BORRADOR', 'Solo se envía a revisión un periodo en borrador.');
  const errores = comprobacionesEnvio(doc);
  exigir(!errores.length, `No se puede enviar a revisión: ${errores.join(' ')}`);
  doc.estado = 'EN_REVISION';
  doc.envios.push({ por: usuario.nombre, en: ahoraTexto(ctx) });
}

function aprobar(doc, usuario, ctx, confirmacion, maestros) {
  exigir(usuario.rol === 'admin', 'Solo un Admin puede aprobar el periodo.');
  exigir(doc.estado === 'EN_REVISION', 'Solo se aprueba un periodo en revisión.');
  exigir(confirmacion === true, 'Confirma que has revisado las líneas, incidencias y ajustes.');
  const errores = comprobacionesAprobacion(doc);
  exigir(!errores.length, `No se puede aprobar: ${errores.join(' ')}`);
  const version = (doc.fotos || []).reduce((m, f) => Math.max(m, f.version), 0) + 1;
  const r = resumen(doc, maestros);
  doc.fotos.push({ version, aprobadoPor: usuario.nombre, aprobadoEn: ahoraTexto(ctx), revisionMaestros: maestros.revision || 0,
    lineas: clonar(doc.lineas), indicadores: r.indicadores, tarifas: clonar(maestros.tarifas || []), categorias: clonar(maestros.categorias || []),
    controlling: clonar(maestros.controlling || {}) });
  doc.versionAprobada = version;
  doc.estado = 'APROBADO';
  return version;
}

function reabrir(doc, usuario, ctx, justificacion) {
  exigir(usuario.rol === 'admin', 'Solo un Admin puede reabrir un periodo.');
  exigir(doc.estado === 'APROBADO' || doc.estado === 'EXPORTADO', 'Solo se reabre un periodo aprobado.');
  exigir(String(justificacion || '').trim(), 'La justificación es obligatoria para reabrir.');
  doc.reaperturas.push({ por: usuario.nombre, en: ahoraTexto(ctx), justificacion: String(justificacion).trim(), version: doc.versionAprobada });
  (doc.exportaciones || []).forEach((x) => { x.estado = 'OBSOLETA'; });
  doc.estado = 'EN_REVISION';
}

/** Alertas de un periodo aprobado: ensayos cambiados después de aprobar (no se recalcula). */
function alertasTrasAprobar(doc, ensayos, maestros, ctx) {
  const foto = fotoVigente(doc);
  if (!foto) return [];
  const porId = new Map(ensayos.map((e) => [e.id, e]));
  const fMaestros = Object.assign({}, maestros);
  const f = (doc.fotos || []).find((x) => x.version === doc.versionAprobada);
  if (f) { fMaestros.tarifas = f.tarifas; fMaestros.categorias = f.categorias; }
  const alertas = [];
  const c = { ahoraMin: ctx.ahora ? aMin(ctx.ahora) : null };
  foto.forEach((l) => {
    if (l.pendiente) return;
    const e = porId.get(l.ensayoId);
    if (!e) { alertas.push({ lineaId: l.id, ensayoId: l.ensayoId, mensaje: 'El ensayo ya no existe.' }); return; }
    const actual = calcularLinea(e, periodoDeId(l.periodoEjecucion), periodoDeId(l.periodoFacturacion), fMaestros, clonar(l), c);
    if (!actual || actual.huella !== l.huella) {
      alertas.push({ lineaId: l.id, ensayoId: l.ensayoId, mensaje: 'El ensayo ha cambiado después de aprobar el periodo.', antes: l.huella, ahora: actual.huella,
        costeAprobado: l.aplicado ? l.aplicado.coste : null, costeActual: actual && actual.calculado ? actual.calculado.coste : null });
    }
  });
  return alertas;
}

/** Estado de los datos de facturación de un ensayo (BILLING_DATA_INCOMPLETE). */
function estadoDatosEnsayo(e, maestros) {
  if (e.tipoEspecial) return { estado: 'NO_APLICA', faltan: [] };
  if (!e.billing.billable) return { estado: 'NO_FACTURABLE', faltan: [] };
  const faltan = [];
  const b = e.billing;
  if (e.fechaInicio && !esHora(b.startTime)) faltan.push('hora de inicio');
  if (e.fechaFin && !esHora(b.endTime)) faltan.push('hora de fin');
  if (b.hv !== 'yes' && b.hv !== 'no') faltan.push('alta tensión');
  if (!b.setupConfirmed) faltan.push('setup');
  const pr = proyectoDeTexto(e.proyecto);
  const proy = pr.numero ? buscarProyecto(maestros, pr.numero) : null;
  if (!proy) faltan.push('proyecto en maestros');
  else {
    const ords = (proy.ordenes || []).filter((o) => o.activa !== false).map((o) => String(o.codigo).trim());
    if (!b.order && ords.length !== 1) faltan.push('orden');
    if (b.order && !ords.includes(b.order) && !(b.orderException && b.orderException.justification)) faltan.push('orden válida');
  }
  if (!buscarMapeo(maestros, e.banco, e.tipoEnsayo)) faltan.push('categoría del banco');
  if (validarDatosEnsayo(e).length) faltan.push('paradas o fechas válidas');
  return { estado: faltan.length ? 'BILLING_DATA_INCOMPLETE' : 'COMPLETO', faltan };
}

/** Ficha de facturación de un ensayo: datos, propuestas y vista previa de las horas por periodo. */
function fichaEnsayo(e, maestros, ctx) {
  const pr = proyectoDeTexto(e.proyecto);
  const proy = pr.numero ? buscarProyecto(maestros, pr.numero) : null;
  const mapeo = buscarMapeo(maestros, e.banco, e.tipoEnsayo);
  const categoria = mapeo ? buscarCategoria(maestros, mapeo.categoria) : null;
  const { inicio, fin } = inicioFinReal(e);
  const tramos = [];
  if (inicio !== null) {
    let id = idPeriodoDeDia(e.fechaInicio);
    const ultimo = e.fechaFin ? idPeriodoDeDia(e.fechaFin) : (ctx.ahora ? idPeriodoDeDia(ctx.ahora.slice(0, 10)) : id);
    for (let n = 0; n < 36; n++) {
      const per = periodoDeId(id);
      const t = tramo(e, per, ctx.ahora ? aMin(ctx.ahora) : null);
      if (t) tramos.push({ periodo: per.id, nombre: per.nombre, tramoInicio: t.tramoInicio, tramoFin: t.tramoFin, minutosBrutos: t.minutosBrutos,
        minutosFinDeSemana: t.minutosFinDeSemana, minutosParadas: t.minutosParadas, horasNetas: t.horasNetas, abierto: t.abierto });
      if (id === ultimo) break;
      id = siguienteId(id);
    }
  }
  return {
    ensayoId: e.id, banco: e.banco, organizacion: e.organizacion, tipoEnsayo: e.tipoEnsayo, proyectoTexto: e.proyecto, requester: e.requester || (proy ? proy.requester : ''),
    fechaInicio: e.fechaInicio, fechaFin: e.fechaFin, cerrado: e.cerrado, finDeSemana: e.finDeSemana, billing: e.billing,
    proyecto: proy ? { numero: proy.numero, nombre: proy.nombre, lineaProducto: proy.lineaProducto || '', ordenes: (proy.ordenes || []).filter((o) => o.activa !== false) } : null,
    numeroProyecto: pr.numero,
    categoria: categoria ? { codigo: categoria.codigo, grupo: categoria.grupo, unidad: categoria.unidad, regla: (mapeo && mapeo.regla) || categoria.regla } : null,
    setupRecomendado: setupRecomendado(maestros, { categoria: categoria ? categoria.codigo : '', banco: e.banco, tipoEnsayo: e.tipoEnsayo }),
    motivosParada: MOTIVOS_PARADA, errores: validarDatosEnsayo(e), estado: estadoDatosEnsayo(e, maestros), tramos,
    inicioReal: inicio === null ? '' : deMin(inicio), finReal: fin === null ? '' : deMin(fin),
  };
}

/** Recalcula el periodo (solo en BORRADOR o EN_REVISION). */
function recalcular(doc, ensayos, maestros, otros, usuario, ctx) {
  exigir(editable(doc), 'El periodo está aprobado: no se recalcula (un Admin puede reabrirlo).');
  calcularPeriodo(doc, ensayos, maestros, otros, ctx);
  doc.ultimoCalculo = ahoraTexto(ctx);
  doc.calculadoPor = usuario.nombre;
  return doc;
}

/**
 * Acción sobre una línea con todo lo que conlleva: aplicar, recalcular la línea,
 * exigir justificación si cambia el importe y, si un Admin aprueba una tarifa con
 * alcance MAESTRA, crear la tarifa y recalcular el periodo.
 * Devuelve {auditoria, maestrosCambiados, linea}.
 */
function ejecutarAccionLinea(docOriginal, lineaId, accion, datos, usuario, ensayos, maestrosOriginales, otros, ctx) {
  // Se trabaja sobre copias: si algo falla, el periodo y los maestros quedan como estaban.
  const doc = clonar(docOriginal);
  const maestros = clonar(maestrosOriginales);
  const i = (doc.lineas || []).findIndex((l) => l.id === lineaId);
  exigir(i >= 0, 'La línea ya no existe en el periodo.');
  exigir(usuario.rol === 'admin' || usuario.rol === 'editor', 'Tu usuario es de solo lectura.');
  const linea = doc.lineas[i];
  const { auditoria, costeAntes } = accionLinea(doc, linea, accion, datos || {}, usuario, ctx);
  let maestrosCambiados = false;
  if (accion === 'resolverTarifa' && linea.propuestaTarifa && linea.propuestaTarifa.estado === 'APROBADA' && linea.propuestaTarifa.alcance === 'MAESTRA') {
    // La tarifa nueva sustituye a la propuesta: la línea ya no necesita tarifa excepcional propia.
    const t = tarifaMaestraDePropuesta(maestros, linea, doc, usuario, ctx);
    auditoria.push({ accion: 'TARIFA_MAESTRA_CREADA', detalle: `${t.id}: ${t.categoria} HV ${t.hv} = ${t.importe} desde ${t.desde}` });
    maestrosCambiados = true;
  }
  // Las incidencias entre líneas (duplicados, solapes) se revisan con el periodo entero.
  calcularPeriodo(doc, ensayos, maestros, otros, ctx);
  const nueva = doc.lineas.find((l) => l.id === lineaId) || null;
  const costeDespues = nueva && nueva.aplicado ? nueva.aplicado.coste : null;
  exigirJustificacion(costeAntes, costeDespues, (datos || {}).justificacion, accion);
  if (!igual(costeAntes === null ? null : redondear2(costeAntes), costeDespues === null ? null : redondear2(costeDespues))) {
    auditoria.push({ accion: 'IMPORTE_MODIFICADO', detalle: `${lineaId} · ${accion}`, costeAntes, costeDespues, justificacion: String((datos || {}).justificacion || '') });
  }
  doc.resumenRapido = resumenRapido(doc);
  Object.keys(docOriginal).forEach((k) => { delete docOriginal[k]; });
  Object.assign(docOriginal, doc);
  if (maestrosCambiados) maestrosOriginales.tarifas = maestros.tarifas;
  return { auditoria, maestrosCambiados, linea: nueva };
}

module.exports = {
  recalcular, ejecutarAccionLinea, resumenRapido,
  REGLAS, CODIGOS_REGLA, ESTADOS_PERIODO, MOTIVOS_PARADA, CAMPOS_AJUSTABLES, TEXTOS, ErrorRegla,
  aMin, deMin, minutosFinDeSemana, cuartosDeMinutos, horasDeMinutos, redondeoExcel2, redondear2,
  periodo, periodoDeId, siguienteId, anteriorId, idPeriodoDeDia, numeroCanonico, proyectoDeTexto,
  datosFacturacion, ensayoFacturable, validarParadas, validarDatosEnsayo, inicioFinReal,
  resolverTarifa, costePorRegla, setupRecomendado, tramo, calcularLinea, calcularPeriodo, resumen, controlling, cuenta,
  nuevoPeriodo, comprobacionesEnvio, comprobacionesAprobacion, accionLinea, exigirJustificacion, tarifaMaestraDePropuesta,
  enviarRevision, aprobar, reabrir, alertasTrasAprobar, estadoDatosEnsayo, fichaEnsayo, fotoVigente, estadoDeLinea, clonar,
};
