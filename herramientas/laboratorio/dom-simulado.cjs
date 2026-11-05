'use strict';
/*
 * DOM mínimo para ejecutar la versión 4.9.4 dentro de Node sin navegador.
 *
 * La idea es la misma que la del apartado 10.3 del manual técnico (estado
 * embebido + fetch anulado), pero sin Playwright: el script original se ejecuta
 * tal cual y todo lo que toca la interfaz cae en estos objetos de mentira.
 * Lo único que nos interesa conservar de verdad es el `value`/`checked` de los
 * campos, porque la ventana de cierre lee sus datos de ahí.
 */

function crearElemento(id) {
  const propios = {
    id: id || '',
    value: '',
    checked: false,
    textContent: '',
    innerHTML: '',
    style: {},
    dataset: {},
    children: [],
    childNodes: [],
    options: [],
    files: [],
    classList: crearClassList(),
  };
  const destino = function () {};
  return new Proxy(destino, {
    get(_t, prop) {
      if (prop in propios) return propios[prop];
      if (prop === Symbol.toPrimitive) return () => '';
      if (prop === 'then') return undefined; // que nadie lo confunda con una promesa
      if (prop === 'getBoundingClientRect') return () => ({ x: 0, y: 0, top: 0, left: 0, width: 1200, height: 600, right: 1200, bottom: 600 });
      if (prop === 'querySelectorAll' || prop === 'getElementsByTagName' || prop === 'getElementsByClassName') return () => [];
      if (prop === 'offsetWidth' || prop === 'clientWidth' || prop === 'scrollWidth') return 1200;
      if (prop === 'offsetHeight' || prop === 'clientHeight' || prop === 'scrollHeight') return 600;
      if (prop === 'scrollLeft' || prop === 'scrollTop') return 0;
      if (prop === 'parentNode' || prop === 'parentElement' || prop === 'firstChild' || prop === 'lastChild' || prop === 'nextSibling' || prop === 'previousSibling') return null;
      if (prop === 'closest' || prop === 'querySelector') return () => null;
      if (prop === 'contains') return () => false;
      if (prop === 'getAttribute') return () => null;
      // Cualquier otro método (appendChild, addEventListener, focus…) no hace nada.
      return function () { return crearElemento(); };
    },
    set(_t, prop, valor) { propios[prop] = valor; return true; },
    apply() { return crearElemento(); },
  });
}

function crearClassList() {
  const clases = new Set();
  return {
    add: (...c) => c.forEach((x) => clases.add(x)),
    remove: (...c) => c.forEach((x) => clases.delete(x)),
    toggle: (c, forzar) => { const poner = forzar === undefined ? !clases.has(c) : !!forzar; if (poner) clases.add(c); else clases.delete(c); return poner; },
    contains: (c) => clases.has(c),
  };
}

function crearDocumento() {
  const porId = new Map();
  const documento = {
    getElementById(id) {
      if (!porId.has(id)) porId.set(id, crearElemento(id));
      return porId.get(id);
    },
    createElement: () => crearElemento(),
    createElementNS: () => crearElemento(),
    createTextNode: () => crearElemento(),
    querySelector: () => null,
    querySelectorAll: () => [],
    getElementsByTagName: () => [],
    getElementsByClassName: () => [],
    addEventListener() {},
    removeEventListener() {},
    activeElement: null,
    hidden: false,
    visibilityState: 'visible',
    title: '',
  };
  documento.body = crearElemento('body');
  documento.head = crearElemento('head');
  documento.documentElement = crearElemento('html');
  return documento;
}

function crearAlmacenLocal() {
  const datos = new Map();
  return {
    getItem: (k) => (datos.has(k) ? datos.get(k) : null),
    setItem: (k, v) => datos.set(k, String(v)),
    removeItem: (k) => datos.delete(k),
    clear: () => datos.clear(),
    key: (i) => Array.from(datos.keys())[i] ?? null,
    get length() { return datos.size; },
  };
}

module.exports = { crearElemento, crearDocumento, crearAlmacenLocal };
