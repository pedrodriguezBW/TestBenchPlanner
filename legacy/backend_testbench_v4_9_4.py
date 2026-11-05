#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BW Test Bench Planner - backend HIBRIDO v3.9 (sin autocierre por inactividad).

v3.9:

	- Cambios para evitar escribir datos en Share Point que estén en Local Storage. Primero se conectará a Share Point, si no es capaz mandará un aviso de que va en solo lectura.


v3.7:
 - Deteccion de usuario (SSO) y permisos por rol: lista TestBenchUsers
   (Title, Email, Role, ResponsibleName). Endpoints /api/whoami, /api/save_users.
   El backend crea las columnas que falten y siembra los usuarios por defecto
   si la lista existe pero esta vacia. Cualquiera que no este en la lista = ReadOnly.
 - Aviso de version antigua: al arrancar se publica APP_VERSION en la lista
   TestBenchAppConfig (item 'CurrentVersion'); /api/app_version la devuelve para
   que la app avise si esta corriendo una version anterior (despliegue en la nube).
 - Backend invisible: se lanza con pythonw.exe desde el .bat (sin consola).

v3.6:
 - Config compartida en SharePoint: listas TestBenchResponsibles, TestBenchProjects
   y TestBenchBenches (endpoints /api/load_config, /api/save_config, /api/diag_config).
 - Cualquier actividad de la app (load/save) mantiene vivo el backend (ya no depende
   solo del heartbeat).
 - Se limita el tamano de la cache del perfil de Edge (.edge_profile).
"""
import atexit, itertools, json, os, queue, re, socket, subprocess, sys, threading, time, webbrowser
from datetime import date
from pathlib import Path
try:
    from flask import Flask, jsonify, request, send_from_directory
    from flask_cors import CORS
    _FLASK_OK = True
except Exception:
    _FLASK_OK = False
try:
    from playwright.sync_api import sync_playwright
    _PW_OK = True
except Exception:
    _PW_OK = False

HERE = Path(__file__).parent
HTML_FILE = "BW_TestBench_Planner_31_Local.html"
if _FLASK_OK:
    app = Flask(__name__); CORS(app)

SITE_WEB = "https://borgwarner.sharepoint.com/sites/5002717"
LIST_TITLE = "TestBenchPlanner"
JSON_FIELD = "DataJson"; TITLE_FIELD = "Title"
SITE_APPROVALS = "https://borgwarner.sharepoint.com/sites/5001274/processautomation"
LIST_APPROVALS = "Services approvals database V2"
# v3.5: weekly KPI history list (create it in SITE_WEB with the columns below)
LIST_KPI = "TestBenchKPIHistory"
KPI_COLUMNS = [
    ("Year", "Number"), ("CW", "Number"), ("SnapDate", "DateTime"), ("Org", "Text"),
    ("Benches", "Number"), ("TotalBenches", "Number"),
    ("Launches", "Number"), ("Finishing", "Number"),
    ("AccruingHours", "Number"), ("NoApprovedID", "Number"), ("Behind", "Number"),
    ("NotStarted", "Number"), ("RiskBehind", "Number"), ("Unplanned", "Number"),
    ("ReasonResources", "Number"), ("ReasonParts", "Number"),
    ("ReasonBreakdown", "Number"), ("ReasonTestDrop", "Number"), ("KPIJson", "Note"),
    # v4.9.3: nuevas columnas (el backend las crea solo en la primera foto).
    ("DelayedDeclared", "Number"),   # ensayos DECLARADOS en retraso que tocan la semana
    ("OccHoursBusy", "Number"),      # horas de banco ocupadas en la semana
    ("OccHoursCap", "Number"),       # horas de banco disponibles (7 d x 24 h x bancos)
    ("OccPct", "Number"),            # % ocupacion = busy / cap
    ("ReasonPriority", "Number"),    # motivo nuevo: cambio de prioridad
    ("ReasonOther", "Number"),       # motivo nuevo: otro
]
# v4.9.3: columnas heredadas que YA NO se rellenan. Se conservan en la lista
# para no perder el historico, pero al guardar una semana se VACIAN, de modo
# que si se vuelve a guardar una semana que ya existia no arrastra valores
# antiguos (p. ej. un Behind de la 4.9.2 sumandose al retraso nuevo).
KPI_RETIRED = ("NoApprovedID", "Behind", "RiskBehind")
# v3.6: shared config lists (create the empty lists in SITE_WEB; the backend
# creates the columns below on its own). Title = the name of the item.
LIST_RESP = "TestBenchResponsibles"
RESP_COLUMNS = [("Org", "Text"), ("Seq", "Number")]
LIST_PROJ = "TestBenchProjects"
PROJ_COLUMNS = [("Seq", "Number")]
LIST_BENCH = "TestBenchBenches"
BENCH_COLUMNS = [("GroupKey", "Text"), ("GroupName", "Text"), ("Dot", "Text"),
                 ("Org", "Text"), ("Seq", "Number")]
# v3.7: users / roles list (Title = display name; identity key = Email).
APP_VERSION = "4.9.4"
# === MODO PRUEBA (v4.7 TEST) ================================================
# Con PUBLISH_VERSION = False este backend NO escribe su version en la lista
# TestBenchAppConfig / 'CurrentVersion' y ademas no se autoavisa. Asi puedes
# probar la 4.7 sin tocar la lista compartida ni provocar el banner de "version
# antigua" al resto del equipo (que sigue en 4.6.1). Pon True para el despliegue
# normal. Es el UNICO cambio respecto al backend v4.7 estandar.
# === MODO PRUEBA ===========================================================
# False -> este backend NO escribe su version en TestBenchAppConfig y responde
#          "publicada = la mia", de modo que:
#            - el equipo NO ve el banner de "version antigua",
#            - tu app de pruebas tampoco lo ve,
#            - la lista compartida queda intacta.
# Ponlo a True el dia que quieras publicar la version de verdad.
# RELEASE 4.9.3: True -> el backend publica su version en TestBenchAppConfig al
# arrancar, de modo que quien tenga abierta una version anterior recibe el aviso
# de "version antigua" y la cierra y vuelve a abrir.
# MODO PRUEBA (visualizacion): no se publica version. El equipo sigue en 4.9.3
# sin ver ningun aviso. Ponlo a True cuando se publique de verdad.
# RELEASE 4.9.4: publica la version al arrancar (aviso de version antigua al
# resto del equipo). Pon False para volver a modo prueba.
PUBLISH_VERSION = True
LIST_USERS = "TestBenchUsers"
USER_COLUMNS = [("Email", "Text"), ("Role", "Text"), ("ResponsibleName", "Text")]
# Seeded ONLY if the list exists and is empty. Anyone not listed = ReadOnly.
SEED_USERS = [
    ("Cesar Carnero",   "ccarnero@borgwarner.com",  "Admin",    "C. Carnero"),
    ("Pablo Gandara",   "pgandara@borgwarner.com",  "Admin",    "P. Gandara"),
    ("Antonio Vide",    "avide@borgwarner.com",     "Admin",    "A. Vide"),
    ("David Diaz",      "dadiaz@borgwarner.com",    "Admin",    "D. Diaz"),
    ("Victor Couto",    "vcouto@borgwarner.com",    "Admin",    "V. Couto"),
    ("Xoan Hermida",    "xhermida@borgwarner.com",  "Admin",    "X. Hermida"),
    ("Pedro Rodriguez", "pedrodriguez@borgwarner.com", "Admin", "P. Rodriguez"),
    ("Julio Lopez",     "jullopez@borgwarner.com",  "Editor",   "J. Lopez"),
    ("Federico Martin", "fedmartin@borgwarner.com", "Editor",   "F. Martin"),
    ("Carmen Morell",   "cmorell@borgwarner.com",   "Editor",   "C. Morell"),
    ("Brais Perez",     "bperez@borgwarner.com",    "Editor",   "B. Perez"),
    ("Francisco Bouzas","fbouzas@borgwarner.com",   "Editor",   "F. Bouzas"),
    ("Ana Costal",      "acostal@borgwarner.com",   "Editor",   "A. Costal"),
    ("Bernabe Lopez",   "berlopez@borgwarner.com",  "Editor",   "B. Lopez"),
    ("Ana Gomez",       "anagomez@borgwarner.com",  "Editor",   "A. Gomez"),
    ("Alberto Raez",    "araez@borgwarner.com",     "Editor",   "A. Raez"),
]
# v3.7: tiny list used to broadcast the currently-published app version.
LIST_APPCFG = "TestBenchAppConfig"
APPCFG_COLUMNS = [("Value", "Text")]

LIST_PRESENCE = "TestBenchPresence"
PRESENCE_COLUMNS = [("Email", "Text"), ("DisplayName", "Text"),
                    ("Initials", "Text"), ("Color", "Text"), ("LastSeen", "DateTime")]
PRESENCE_TTL = 75

LIST_ACTIVITY = "TestBenchActivityLog"
ACTIVITY_COLUMNS = [("Email", "Text"), ("DisplayName", "Text"),
                    ("Action", "Text"), ("Detail", "Note"), ("WhenTs", "DateTime")]

# v4.5: auxiliary resources (cylinders, pulse generators, DAQ, scopes, PSUs...).
# Catalog grouped by TYPE (GroupKey/GroupName), fully configurable. EquipmentCode
# is the unique equipment id. Org = BW/CTAG.
LIST_RESOURCES = "TestBenchResources"
RESOURCE_COLUMNS = [("GroupKey", "Text"), ("GroupName", "Text"), ("Dot", "Text"),
                    ("EquipmentCode", "Text"), ("Org", "Text"), ("Seq", "Number")]
# v4.5: manual resource blocks (calibration, maintenance, breakdown, other).
LIST_RESBLOCKS = "TestBenchResourceBlocks"
RESBLOCK_COLUMNS = [("Resource", "Text"), ("StartDate", "DateTime"), ("EndDate", "DateTime"),
                    ("Reason", "Text"), ("Note", "Note"), ("Author", "Text")]

RELEASE_SITE = SITE_WEB
RELEASE_FOLDER = ""

# v4.3: keep the SharePoint "mirror" columns + Choice fields OUT of the critical
# save path. When True, the fast save writes only Title+DataJson and responds
# immediately; the mirror/choice columns are updated in a BACKGROUND thread.
# Set to False to skip the mirror entirely (nothing else in the app needs it).
MIRROR_ENABLED = True
MIRROR_IN_BACKGROUND = True

# === v4.4: rendimiento del guardado =========================================
# (A) Conexion CDP persistente: un unico hilo trabajador es dueno de Playwright,
#     del browser y del contexto. Se conecta UNA vez, no en cada operacion.
#     Ademas la cola es FIFO con prioridad, asi que un /api/save adelanta a los
#     sondeos de fondo (presence, poll, config) en vez de quedarse esperando.
SP_PERSISTENT_CTX = True        # False -> comportamiento clasico v4.3.
SP_PRIO_SAVE = 0                # menor = mas prioritario
SP_PRIO_UI = 3
SP_PRIO_BG = 7                  # espejo y tareas decorativas
# (B) Espejo: un unico hilo permanente con cola fusionada (coalescing). Diez
#     ediciones seguidas producen UNA pasada de espejo, no diez.
MIRROR_DEBOUNCE_S = 2.0         # ventana de fusion
MIRROR_CHUNK = 5                # filas por trabajo (deja huecos para los saves)
# (D) Cache de Id de elemento: uid -> Id de SharePoint, para no repetir el
#     $filter=Title eq '...' en cada guardado ni en el espejo.
ID_CACHE_ENABLED = True
# ============================================================================
APPR_STATUS_FIELD = "Status"            # legacy fallback
# v3.0: colour is driven by "Global status"; picker also shows Requester name + Service details.
APPR_GLOBAL_TITLES = ["Global status", "GlobalStatus", "Global Status"]
APPR_REQUESTER_TITLES = ["Requester name", "Requester Name", "RequesterName", "Requester"]
APPR_SERVICE_TITLES = ["Service details", "Service Details", "ServiceDetails", "Service"]
_APPR_FIELDS = {"global": None, "requester": None, "service": None, "ready": False}
SP_JSON = "application/json;odata=nometadata"
PROFILE_DIR = HERE / ".edge_profile"; DEBUG_PORT = 9222
_browser_lock = threading.Lock(); _edge_proc = None
_state_lock = threading.Lock(); STARTED_AT = time.time(); LAST_BEAT = time.time()
GOT_BEAT = False; GRACE_S = 90; IDLE_TIMEOUT = 20; NOCONN_TIMEOUT = 1800; _opened_browser = False
AUTOCLOSE_ON_IDLE = False
_columns_ready = False; LAST_ENSURE = {"created": [], "errors": {}}

PW_MISSING = "El entorno no tiene 'playwright'. Instala: pip install flask flask-cors playwright"
EDGE_BLOCKED = "No se pudo abrir Edge con depuracion remota. Avisa a IT."

_KIND = {"Text": 2, "Note": 3, "DateTime": 4, "Boolean": 8, "Number": 9}
NEW_COLUMNS = [
    ("ResponsibleName", "Text"), ("Comments", "Note"), ("SamplesAvail", "DateTime"),
    ("LaunchConfirmed", "DateTime"), ("StartOverride", "DateTime"), ("DurManual", "Number"),
    ("ManualUnit", "Text"), ("Cycles", "Number"), ("CycleVal", "Number"), ("CycleUnit", "Text"),
    ("InputsReady", "Boolean"), ("ProgressActual", "Number"), ("LastUpdate", "DateTime"),
    ("HistStart", "DateTime"), ("HistEnd", "DateTime"), ("DoneDate", "DateTime"),
    ("ApprovalStatusText", "Text"), ("Seq", "Number"),
    ("BenchText", "Text"), ("ModeText", "Text"), ("StatusText", "Text"),
    ("DelayReason", "Text"), ("BenchOrg", "Text"), ("Contingency", "Number"),
    # --- Columnas ESPEJO nuevas (decorativas, para las vistas de SharePoint).
    # Los datos de verdad viajan dentro de DataJson, asi que aunque no existan
    # la app sigue funcionando; el backend las crea solo la primera vez.
    ("ActualStart", "DateTime"),      # fecha real de arranque (sustituye a Launched)
    ("Progress100Date", "DateTime"),  # dia en que se alcanzo el 100 %
    ("ManualState", "Text"),          # estado declarado en vivo: ok | risk | delayed
    ("StatusManual", "Text"),         # etiqueta legible del estado efectivo
    # --- Estado FINAL congelado al cerrar (alimenta las estadisticas anuales)
    ("FinalStatus", "Text"),          # ok | risk | delayed
    ("FinalReason", "Text"),          # motivo declarado al cerrar
    ("FinalBy", "Text"),              # quien lo cerro
    ("FinalActualStart", "DateTime"), # arranque real congelado
    ("FinalCommittedEnd", "DateTime"),# fin comprometido congelado
    # --- Ventana de cierre (fechas reales confirmadas + paradas)
    ("TrueEnd", "DateTime"),          # fin real (vivo)
    ("DowntimeDays", "Number"),       # dias de parada (vivo)
    ("DowntimeNote", "Note"),         # nota de parada
    ("FinalTrueStart", "DateTime"),   # arranque real CONFIRMADO al cerrar
    ("FinalTrueEnd", "DateTime"),     # fin real CONFIRMADO al cerrar
    ("FinalDelayDays", "Number"),     # fin real - fin comprometido (dias naturales)
    ("FinalReasonCode", "Text"),      # motivo (valor estable)
    ("FinalComment", "Note"),         # explicacion
    ("FinalDowntimeDays", "Number"),  # dias de parada confirmados
    ("FinalConfirmedBy", "Text"),     # quien confirmo el cierre
    ("FinalConfirmedAt", "DateTime"), # cuando
]
CHOICE_FIELDS = ["Bench", "Mode", "Status", "ApprovalStatus"]

def ensure_playwright():
    global sync_playwright, _PW_OK
    if _PW_OK: return True
    py = sys.executable
    for cmd in ([py, "-m", "pip", "install", "--disable-pip-version-check", "playwright"],):
        try: subprocess.run(cmd, check=True, capture_output=True, timeout=900)
        except Exception: continue
        try:
            from playwright.sync_api import sync_playwright as _sp
            sync_playwright = _sp; _PW_OK = True; return True
        except Exception: continue
    return False

def _clear_locks():
    for lk in ("SingletonLock", "SingletonCookie", "SingletonSocket", "lockfile"):
        try: (PROFILE_DIR / lk).unlink()
        except Exception: pass

def _edge_exe():
    for c in (os.path.expandvars(r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
              os.path.expandvars(r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
              os.path.expandvars(r"%LocalAppData%\Microsoft\Edge\Application\msedge.exe")):
        if c and os.path.exists(c): return c
    return "msedge"

def _port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1): return True
    except Exception: return False

def _kill_edge():
    global _edge_proc
    try:
        if _edge_proc and _edge_proc.poll() is None:
            _edge_proc.terminate()
            try: _edge_proc.wait(timeout=5)
            except Exception: _edge_proc.kill()
    except Exception: pass
    _edge_proc = None
atexit.register(_kill_edge)

def _spawn_edge(headless=True):
    global _edge_proc
    PROFILE_DIR.mkdir(exist_ok=True); _clear_locks()
    args = [_edge_exe(), "--remote-debugging-port=%d" % DEBUG_PORT, "--user-data-dir=%s" % PROFILE_DIR,
            "--no-first-run", "--no-default-browser-check", "--no-service-autorun",
            "--disable-background-networking",
            # v3.6: keep the .edge_profile small (cap disk + media cache to ~64 MB)
            "--disk-cache-size=67108864", "--media-cache-size=33554432",
            "--disable-features=Translate,MediaRouter,OptimizationHints",
            "about:blank"]
    if headless: args[1:1] = ["--headless=new", "--disable-gpu"]
    flags = 0
    if os.name == "nt": flags = 0x00000008 | 0x08000000
    try:
        _edge_proc = subprocess.Popen(args, creationflags=flags, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception: return False
    for _ in range(60):
        if _port_open(DEBUG_PORT): return True
        time.sleep(0.5)
    return _port_open(DEBUG_PORT)

def _ensure_edge(headless=True):
    if _port_open(DEBUG_PORT): return True
    if _spawn_edge(headless) and _port_open(DEBUG_PORT): return True
    if headless:
        _kill_edge()
        if _spawn_edge(False) and _port_open(DEBUG_PORT): return True
    return _port_open(DEBUG_PORT)

def _is_logged_in(ctx, site=SITE_WEB):
    try:
        r = ctx.request.get("%s/_api/web/title" % site, headers={"Accept": SP_JSON}, timeout=30000)
        if not r.ok: return False
        r.json(); return True
    except Exception: return False

def _is_logged_in(ctx, site=SITE_WEB, retries=1, timeout_ms=8000):
    for i in range(max(1, retries)):
        try:
            r = ctx.request.get("%s/_api/web/title" % site, headers={"Accept": SP_JSON}, timeout=timeout_ms)
            if r.ok:
                r.json(); return True
        except Exception:
            pass
        if i < retries - 1:
            try: (ctx.pages[0] if ctx.pages else ctx.new_page()).wait_for_timeout(1000)
            except Exception: time.sleep(1.0)
    return False

def _log_t(msg, t0=None):
    try:
        dt = ("" if t0 is None else (" (+%.1fs)" % (time.time() - t0)))
        print("[Planner %s] %s%s" % (time.strftime("%H:%M:%S"), msg, dt))
    except Exception:
        pass

def _ensure_live_session(ctx, wait_s=45):
    t0 = time.time()
    if _is_logged_in(ctx, timeout_ms=8000):
        return True
    page = ctx.pages[0] if ctx.pages else ctx.new_page()
    try:
        page.goto(SITE_WEB, wait_until="commit", timeout=15000)
    except Exception:
        pass
    waited = 0.0
    while waited < wait_s:
        if _is_logged_in(ctx, timeout_ms=8000):
            _log_t("session ready", t0); return True
        try: page.wait_for_timeout(1000)
        except Exception: time.sleep(1.0)
        waited += 1.0
    ok = _is_logged_in(ctx, timeout_ms=8000)
    _log_t("session %s after wait" % ("ready" if ok else "STILL DOWN"), t0)
    return ok

# --- v4.4 (A): hilo trabajador dueno de la conexion CDP ---------------------
# La API sincrona de Playwright no es thread-safe: el objeto debe usarse siempre
# desde el mismo hilo. Por eso centralizamos TODO el trafico a SharePoint en un
# unico hilo trabajador que mantiene viva la conexion. Efecto colateral: la
# serializacion deja de depender de un Lock injusto y pasa a ser una cola con
# prioridad, de modo que los guardados no se quedan sin turno.
_sp_q = queue.PriorityQueue()
_sp_seq = itertools.count()
_sp_thread = None
_sp_boot_lock = threading.Lock()
_sp_pw = None; _sp_browser = None; _sp_ctx = None
SP_STATS = {"jobs": 0, "connects": 0, "reconnects": 0, "errors": 0}

_CONN_HINTS = ("closed", "disconnect", "not connected", "target page",
               "browser has been", "connection", "websocket", "econnrefused")

def _looks_like_conn_error(e):
    t = str(e or "").lower()
    return any(h in t for h in _CONN_HINTS)

def _sp_teardown():
    global _sp_browser, _sp_ctx
    try:
        if _sp_browser is not None: _sp_browser.close()
    except Exception: pass
    _sp_browser = None; _sp_ctx = None

def _sp_connect(headless):
    """Abre (o reabre) la conexion CDP. Solo se llama desde el hilo trabajador."""
    global _sp_pw, _sp_browser, _sp_ctx
    if not _ensure_edge(headless): raise RuntimeError(EDGE_BLOCKED)
    if _sp_pw is None:
        _sp_pw = sync_playwright().start()
    _sp_browser = _sp_pw.chromium.connect_over_cdp(
        "http://127.0.0.1:%d" % DEBUG_PORT, timeout=30000)
    _sp_ctx = _sp_browser.contexts[0] if _sp_browser.contexts else _sp_browser.new_context()
    SP_STATS["connects"] += 1
    return _sp_ctx

def _sp_alive():
    if _sp_browser is None or _sp_ctx is None: return False
    try: return bool(_sp_browser.is_connected())
    except Exception: return False

def _sp_run(headless, fn):
    """Ejecuta fn(ctx) reutilizando el contexto; reconecta una vez si se cayo."""
    last = None
    for attempt in (0, 1):
        try:
            if not _sp_alive():
                _sp_teardown(); _sp_connect(headless)
                if attempt: SP_STATS["reconnects"] += 1
            return fn(_sp_ctx)
        except PermissionError:
            raise                       # no-session: es logica de negocio, no red
        except Exception as e:
            last = e
            if attempt == 0 and _looks_like_conn_error(e):
                _log_t("ctx: conexion caida (%s), reconectando" % str(e)[:80])
                _sp_teardown(); continue
            raise
    raise last

class _SpJob(object):
    __slots__ = ("fn", "headless", "ev", "value", "error")
    def __init__(self, fn, headless):
        self.fn = fn; self.headless = headless
        self.ev = threading.Event(); self.value = None; self.error = None

def _sp_worker_loop():
    while True:
        prio, seq, job = _sp_q.get()
        if job is None:
            _sp_q.task_done(); return
        try:
            job.value = _sp_run(job.headless, job.fn)
        except BaseException as e:
            job.error = e; SP_STATS["errors"] += 1
        finally:
            SP_STATS["jobs"] += 1
            job.ev.set(); _sp_q.task_done()

def _sp_ensure_worker():
    global _sp_thread
    if _sp_thread is not None and _sp_thread.is_alive(): return
    with _sp_boot_lock:
        if _sp_thread is not None and _sp_thread.is_alive(): return
        _sp_thread = threading.Thread(target=_sp_worker_loop,
                                      name="sp-worker", daemon=True)
        _sp_thread.start()

def _with_ctx_legacy(headless, fn):
    """Comportamiento v4.3: conexion nueva por operacion, protegida por Lock."""
    with _browser_lock:
        if not _ensure_edge(headless): raise RuntimeError(EDGE_BLOCKED)
        with sync_playwright() as p:
            browser = p.chromium.connect_over_cdp("http://127.0.0.1:%d" % DEBUG_PORT, timeout=30000)
            try:
                ctx = browser.contexts[0] if browser.contexts else browser.new_context()
                return fn(ctx)
            finally:
                try: browser.close()
                except Exception: pass

def _with_ctx(headless, fn, prio=SP_PRIO_UI):
    if not _PW_OK and not ensure_playwright(): raise RuntimeError(PW_MISSING)
    if not SP_PERSISTENT_CTX:
        return _with_ctx_legacy(headless, fn)
    # Reentrada: si ya estamos dentro del trabajador, usar el contexto vivo.
    if threading.current_thread() is _sp_thread and _sp_ctx is not None:
        return fn(_sp_ctx)
    _sp_ensure_worker()
    job = _SpJob(fn, headless)
    _sp_q.put((prio, next(_sp_seq), job))
    job.ev.wait()
    if job.error is not None: raise job.error
    return job.value

def _get_json(ctx, url):
    r = ctx.request.get(url, headers={"Accept": SP_JSON}, timeout=60000)
    if not r.ok: raise RuntimeError("GET %s -> %s" % (url, r.status))
    return r.json()

def _get_digest(ctx):
    r = ctx.request.post("%s/_api/contextinfo" % SITE_WEB, headers={"Accept": SP_JSON}, timeout=30000)
    if not r.ok: raise RuntimeError("No se pudo obtener FormDigest.")
    j = r.json()
    return j.get("FormDigestValue") or j.get("d", {}).get("GetContextWebInformation", {}).get("FormDigestValue")

def _list_base(site=SITE_WEB, title=LIST_TITLE):
    return "%s/_api/web/lists/GetByTitle('%s')" % (site, title.replace("'", "''"))

def _find_item_id(ctx, uid):
    safe = str(uid).replace("'", "''")
    url = "%s/items?$select=Id,%s&$filter=%s eq '%s'&$top=1" % (_list_base(), TITLE_FIELD, TITLE_FIELD, safe)
    vals = _get_json(ctx, url).get("value", [])
    return vals[0].get("Id") if vals else None

# --- v4.4 (D): cache uid -> Id ---------------------------------------------
# El camino rapido del guardado y el espejo buscaban CADA fila con un $filter.
# Los Id de SharePoint son estables, asi que los memorizamos. Si un MERGE falla
# porque el elemento ya no existe, se invalida la entrada y se vuelve a buscar.
_ID_CACHE = {}
_id_cache_lock = threading.Lock()
ID_CACHE_STATS = {"hit": 0, "miss": 0, "drop": 0}

def _cache_get(uid):
    if not ID_CACHE_ENABLED: return None
    with _id_cache_lock: return _ID_CACHE.get(uid)

def _cache_put(uid, item_id):
    if not ID_CACHE_ENABLED or not uid or item_id is None: return
    with _id_cache_lock: _ID_CACHE[uid] = int(item_id)

def _cache_drop(uid):
    with _id_cache_lock:
        if _ID_CACHE.pop(uid, None) is not None: ID_CACHE_STATS["drop"] += 1

def _item_id(ctx, uid):
    """Id del elemento usando cache; solo consulta SharePoint si no lo tiene."""
    cached = _cache_get(uid)
    if cached is not None:
        ID_CACHE_STATS["hit"] += 1; return cached
    ID_CACHE_STATS["miss"] += 1
    iid = _find_item_id(ctx, uid)
    if iid is not None: _cache_put(uid, iid)
    return iid

def fetch_all_items(ctx):
    url = "%s/items?$select=%s,%s&$top=2000" % (_list_base(), TITLE_FIELD, JSON_FIELD)
    out = []
    while url:
        data = _get_json(ctx, url); out.extend(data.get("value", []))
        nxt = data.get("@odata.nextLink") or data.get("odata.nextLink")
        if nxt and not nxt.startswith("http"): nxt = "%s/_api/%s" % (SITE_WEB, nxt.lstrip("/"))
        url = nxt
    return out

def existing_internal_names(ctx):
    url = "%s/fields?$select=InternalName&$top=500" % _list_base()
    return set(f.get("InternalName") for f in _get_json(ctx, url).get("value", []))

def _add_view_field(ctx, digest, name):
    try:
        ctx.request.post("%s/views/getbytitle('All Items')/viewfields/addviewfield('%s')" % (_list_base(), name),
                         headers={"Accept": SP_JSON, "X-RequestDigest": digest}, timeout=30000)
    except Exception: pass

def _create_field(ctx, digest, name, sptype):
    hdr = {"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest}
    if sptype == "Note":
        schema = ("<Field Type='Note' DisplayName='%s' Name='%s' StaticName='%s' "
                  "NumLines='6' RichText='FALSE' />" % (name, name, name))
        body = {"parameters": {"SchemaXml": schema, "Options": 8}}
        r = ctx.request.post("%s/fields/createfieldasxml" % _list_base(), headers=hdr,
                             data=json.dumps(body), timeout=60000)
    else:
        body = {"Title": name, "FieldTypeKind": _KIND[sptype]}
        r = ctx.request.post("%s/fields" % _list_base(), headers=hdr,
                             data=json.dumps(body), timeout=60000)
    ok = r.status in (200, 201)
    return ok, r.status, ("" if ok else (r.text() or "")[:180])

def ensure_columns(ctx, digest=None):
    global _columns_ready, LAST_ENSURE
    have = existing_internal_names(ctx)
    if digest is None: digest = _get_digest(ctx)
    created = []; errors = {}
    for name, sptype in NEW_COLUMNS:
        if name in have: continue
        try:
            ok, st, txt = _create_field(ctx, digest, name, sptype)
            if ok:
                created.append(name); _add_view_field(ctx, digest, name)
            else:
                errors[name] = "%s: %s" % (st, txt)
        except Exception as e:
            errors[name] = str(e)[:180]
    _columns_ready = True
    LAST_ENSURE = {"created": created, "errors": errors}
    return created, errors

def _norm_id(v):
    m = re.search(r"\d+", str(v or "")); return int(m.group(0)) if m else None

def map_status(v):
    s = (v or "").strip().lower()
    if s == "approved": return ("approved", "green")
    if s == "pending":  return ("pending", "amber")
    if s == "rejected": return ("rejected", "red")
    if s in ("na", "n/a"): return ("na", "red")
    if s == "": return ("not_created", "red")
    return ("other:" + s, "red")

def _resolve_appr_fields(ctx):
    """Autodetect internal names for Global status / Requester name / Service details."""
    if _APPR_FIELDS["ready"]:
        return _APPR_FIELDS
    try:
        url = "%s/fields?$select=InternalName,Title&$top=500" % _list_base(SITE_APPROVALS, LIST_APPROVALS)
        fields = _get_json(ctx, url).get("value", [])
        def find(titles):
            tl = [t.lower() for t in titles]
            for f in fields:
                if (f.get("Title") or "").strip().lower() in tl:
                    return f.get("InternalName")
            for f in fields:
                if (f.get("InternalName") or "").strip().lower() in tl:
                    return f.get("InternalName")
            return None
        _APPR_FIELDS["global"] = find(APPR_GLOBAL_TITLES) or APPR_STATUS_FIELD
        _APPR_FIELDS["requester"] = find(APPR_REQUESTER_TITLES)
        _APPR_FIELDS["service"] = find(APPR_SERVICE_TITLES)
    except Exception:
        _APPR_FIELDS["global"] = APPR_STATUS_FIELD
    _APPR_FIELDS["ready"] = True
    return _APPR_FIELDS

def resolve_status(ctx, approval_id, cache):
    key = str(approval_id or "")
    if key in cache: return cache[key]
    iid = _norm_id(approval_id)
    if iid is None:
        res = {"input": key, "resolvedId": None, "status": "not_created", "color": "red", "raw": None}
        cache[key] = res; return res
    flds = _resolve_appr_fields(ctx)
    gf = flds.get("global") or APPR_STATUS_FIELD
    sel = ",".join(sorted(set([f for f in ["Id", gf, flds.get("requester"), flds.get("service")] if f])))
    url = "%s/items(%d)?$select=%s" % (_list_base(SITE_APPROVALS, LIST_APPROVALS), iid, sel)
    try:
        it = _get_json(ctx, url); raw = it.get(gf)
        st, col = map_status(raw)
        res = {"input": key, "resolvedId": iid, "status": st, "color": col, "raw": raw,
               "requester": it.get(flds.get("requester")) if flds.get("requester") else None,
               "service": it.get(flds.get("service")) if flds.get("service") else None}
    except Exception:
        res = {"input": key, "resolvedId": iid, "status": "not_created", "color": "red", "raw": None}
    cache[key] = res; return res

def _num(v):
    if v in ("", None): return None
    try: return float(v) if isinstance(v, str) else v
    except Exception: return None

def _dt(v): return v if v else None

# Estado para las vistas de SharePoint. Si el ensayo esta cerrado manda el
# estado FINAL declarado; si sigue vivo, el estado manual en curso. Sin
# deducciones: el calculo fino vive en la app y aqui solo espejamos.
# Solo dos estados declarables. 'risk' (retirado) se lee como retraso.
_MANUAL_LABEL = {"ok": "on_track", "risk": "delayed", "delayed": "delayed"}
def _manual_status(row):
    if row.get("done"):
        fin = (row.get("finalStatus") or "ok").strip().lower()
        return "completed_" + _MANUAL_LABEL.get(fin, "on_track")
    started = bool(row.get("actualStart"))
    if not started and not row.get("confirmedStart"):
        return "queued"
    st = (row.get("manualState") or "ok").strip().lower()
    if st == "ok":
        return "running" if started else "confirmed"
    return _MANUAL_LABEL.get(st, "running")
def _coarse_status(row):
    if row.get("done"): return "completed"
    lc = row.get("launchConfirmed")
    if lc:
        try:
            y, m, d = [int(x) for x in str(lc)[:10].split("-")]
            return "running" if date(y, m, d) <= date.today() else "confirmed"
        except Exception: return "confirmed"
    return "queued"

def safe_body(bench, seq, row, resolved):
    coarse = _coarse_status(row)
    return {
        "TestType": row.get("type") or None,
        "Project": row.get("project") or None,
        "ApprovalID": row.get("approvalId") or None,
        "Priority": seq, "Seq": seq,
        "WeekendRun": bool(row.get("weekend")),
        "Done": bool(row.get("done")),
        "ResponsibleName": row.get("resp") or None,
        "Comments": row.get("comments") or None,
        "SamplesAvail": _dt(row.get("avail")),
        "LaunchConfirmed": _dt(row.get("launchConfirmed")),
        "StartOverride": _dt(row.get("startOverride")),
        "DurManual": _num(row.get("durManual")),
        "ManualUnit": row.get("manualUnit") or None,
        "Cycles": _num(row.get("cycles")),
        "CycleVal": _num(row.get("cycleVal")),
        "CycleUnit": row.get("cycleUnit") or None,
        "InputsReady": bool(row.get("inputs", True)),
        "ProgressActual": _num(row.get("progressActual")),
        "LastUpdate": _dt(row.get("lastUpdate")),
        "HistStart": _dt(row.get("histStart")),
        "HistEnd": _dt(row.get("histEnd")),
        "DoneDate": _dt(row.get("doneDate")),
        "ApprovalStatusText": (resolved or {}).get("status"),
        "BenchText": bench or None,
        "ModeText": row.get("durMode") or None,
        "StatusText": coarse,
        "DelayReason": row.get("delayReason") or None,
        # fecha real de arranque / progreso / estado declarado
        "ActualStart": _dt(row.get("actualStart")),
        "Progress100Date": _dt(row.get("progress100Date")),
        "ManualState": row.get("manualState") or "ok",
        "StatusManual": _manual_status(row),
        # estado FINAL congelado al cerrar
        "FinalStatus": row.get("finalStatus") or None,
        "FinalReason": row.get("finalReason") or None,
        "FinalBy": row.get("finalBy") or None,
        "FinalActualStart": _dt(row.get("finalActualStart")),
        "FinalCommittedEnd": _dt(row.get("finalCommittedEnd")),
        "TrueEnd": _dt(row.get("trueEnd")),
        "DowntimeDays": _num(row.get("downtimeDays")),
        "DowntimeNote": row.get("downtimeNote") or None,
        "FinalTrueStart": _dt(row.get("finalTrueStart")),
        "FinalTrueEnd": _dt(row.get("finalTrueEnd")),
        "FinalDelayDays": _num(row.get("finalDelayDays")),
        "FinalReasonCode": row.get("finalReasonCode") or None,
        "FinalComment": row.get("finalComment") or None,
        "FinalDowntimeDays": _num(row.get("finalDowntimeDays")),
        "FinalConfirmedBy": row.get("finalConfirmedBy") or None,
        "FinalConfirmedAt": _dt(row.get("finalConfirmedAt")),
        "BenchOrg": row.get("_benchOrg") or None,
        "Contingency": _num(row.get("cf")),
    }

def _merge_diag(ctx, digest, item_id, body):
    r = ctx.request.post("%s/items(%d)" % (_list_base(), int(item_id)),
        headers={"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest,
                 "IF-MATCH": "*", "X-HTTP-Method": "MERGE"}, data=json.dumps(body), timeout=60000)
    ok = r.status in (200, 204)
    return ok, r.status, ("" if ok else (r.text() or "")[:180])

def create_item(ctx, digest, uid, data):
    body = {TITLE_FIELD: uid, JSON_FIELD: data}
    r = ctx.request.post("%s/items" % _list_base(),
        headers={"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest},
        data=json.dumps(body), timeout=60000)
    if r.status not in (200, 201, 204): raise RuntimeError("crear %s: %s" % (r.status, r.text()[:200]))
    # v4.4 (D): SharePoint ya devuelve el Id al crear -> nos ahorramos un GET.
    try:
        iid = (r.json() or {}).get("Id")
        if iid: _cache_put(uid, int(iid)); return int(iid)
    except Exception: pass
    iid = _find_item_id(ctx, uid)
    if iid is not None: _cache_put(uid, iid)
    return iid

def update_item(ctx, digest, item_id, uid, data):
    ok, st, txt = _merge_diag(ctx, digest, item_id, {TITLE_FIELD: uid, JSON_FIELD: data})
    if not ok and st in (404, 400):
        # v4.4 (D): el Id cacheado ya no vale -> invalidar, rebuscar y reintentar.
        _cache_drop(uid)
        fresh = _find_item_id(ctx, uid)
        if fresh is None:
            fresh = create_item(ctx, digest, uid, data)
            return fresh
        _cache_put(uid, fresh)
        ok, st, txt = _merge_diag(ctx, digest, fresh, {TITLE_FIELD: uid, JSON_FIELD: data})
        item_id = fresh
    if not ok: raise RuntimeError("actualizar DataJson %s: %s" % (st, txt))
    return item_id

def recycle_item(ctx, digest, item_id):
    r = ctx.request.post("%s/items(%d)/recycle" % (_list_base(), int(item_id)),
        headers={"Accept": SP_JSON, "X-RequestDigest": digest}, timeout=60000)
    return r.status in (200, 204)

def do_load():
    def _do(ctx):
        t0 = time.time()
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        _log_t("load: session ok, fetching planner rows", t0)
        items = fetch_all_items(ctx)
        _log_t("load: fetched %d rows" % len(items), t0)
        return [{"uid": it.get(TITLE_FIELD), "data": it.get(JSON_FIELD)} for it in items if it.get(TITLE_FIELD)]
    return _with_ctx(True, _do)

# v4.4 (E): los 4 campos Choice iban en 4 MERGE sueltos (5 llamadas por fila).
# Se mandan dentro del MERGE principal -> 1 llamada. Si SharePoint rechaza algun
# valor de Choice, se detecta una sola vez y el proceso vuelve al modo separado.
_CHOICE_INLINE = {"ok": True}

def _mirror_upserts(ctx, digest, upserts, idmap=None):
    """Columnas espejo + Choice. La app nunca las lee (solo Title + DataJson):
    son decorativas para las vistas de SharePoint. v4.4: recibe idmap del
    guardado (D) y manda los Choice dentro del MERGE principal (E)."""
    cache = {}; mirrored = 0; mirror_err = 0; merge_errors = []; calls = 0
    idmap = idmap or {}
    for u in upserts:
        uid = u.get("uid")
        item_id = idmap.get(uid) or _cache_get(uid)
        if item_id is None:
            item_id = _find_item_id(ctx, uid); calls += 1
            if item_id is not None: _cache_put(uid, item_id)
        if not item_id: continue
        try:
            obj = json.loads(u.get("data")) if isinstance(u.get("data"), str) else (u.get("data") or {})
        except Exception: obj = {}
        if not isinstance(obj, dict): continue
        bench = obj.get("_bench"); seq = obj.get("_seq")
        resolved = resolve_status(ctx, obj.get("approvalId"), cache) if obj.get("approvalId") else None
        sb = safe_body(bench, seq, obj, resolved)
        choices = {"Bench": bench, "Mode": obj.get("durMode"),
                   "Status": _coarse_status(obj), "ApprovalStatus": (resolved or {}).get("status")}
        choices = dict((k, v) for k, v in choices.items() if v)
        done = False
        if _CHOICE_INLINE["ok"] and choices:
            body = dict(sb); body.update(choices)
            ok, st, txt = _merge_diag(ctx, digest, item_id, body); calls += 1
            if ok:
                mirrored += 1; done = True
            else:
                # Un Choice con valor no permitido tumba el MERGE entero:
                # a partir de aqui, modo separado para el resto del proceso.
                _CHOICE_INLINE["ok"] = False
                _log_t("mirror: Choice inline rechazado (%s), paso a modo separado" % st)
        if not done:
            ok, st, txt = _merge_diag(ctx, digest, item_id, sb); calls += 1
            if ok: mirrored += 1
            else:
                mirror_err += 1
                if len(merge_errors) < 3: merge_errors.append("uid=%s %s: %s" % (uid, st, txt))
            for fld, val in choices.items():
                try: _merge_diag(ctx, digest, item_id, {fld: val}); calls += 1
                except Exception: pass
    return {"mirrored": mirrored, "mirror_err": mirror_err,
            "merge_errors": merge_errors, "calls": calls,
            "choice_inline": _CHOICE_INLINE["ok"]}

# --- v4.4 (B): espejo con un unico hilo y fusion de peticiones --------------
# Antes se lanzaba un hilo NUEVO por cada guardado; diez ediciones seguidas
# encolaban diez pasadas completas, cada una con su conexion y su digest. Ahora
# hay un solo trabajador permanente: acumula por uid (se queda el ultimo dato
# de cada fila), espera MIRROR_DEBOUNCE_S y hace UNA pasada, troceada en grupos
# de MIRROR_CHUNK para que los guardados puedan colarse por prioridad.
_mirror_pending = {}
_mirror_ids = {}
_mirror_lock = threading.Lock()
_mirror_evt = threading.Event()
_mirror_thread = None
MIRROR_STATS = {"batches": 0, "rows": 0, "coalesced": 0, "errors": 0, "last": ""}

def _mirror_enqueue(upserts, idmap=None):
    """Acumula filas pendientes de espejo (la ultima version de cada uid gana)."""
    if not upserts: return
    with _mirror_lock:
        for u in upserts:
            uid = u.get("uid")
            if not uid: continue
            if uid in _mirror_pending: MIRROR_STATS["coalesced"] += 1
            _mirror_pending[uid] = u.get("data")
        if idmap: _mirror_ids.update(idmap)
    _mirror_evt.set()

def _mirror_run_batch(batch, ids):
    def _do(ctx):
        if not _ensure_live_session(ctx): return None
        digest = _get_digest(ctx)
        return _mirror_upserts(ctx, digest, batch, ids)
    return _with_ctx(True, _do, prio=SP_PRIO_BG)

def _mirror_worker_loop():
    while True:
        _mirror_evt.wait()
        time.sleep(MIRROR_DEBOUNCE_S)      # ventana de fusion
        _mirror_evt.clear()
        with _mirror_lock:
            batch = [{"uid": k, "data": v} for k, v in _mirror_pending.items()]
            ids = dict(_mirror_ids)
            _mirror_pending.clear(); _mirror_ids.clear()
        if not batch: continue
        t0 = time.time(); total = 0
        for i in range(0, len(batch), max(1, MIRROR_CHUNK)):
            chunk = batch[i:i + max(1, MIRROR_CHUNK)]
            try:
                res = _mirror_run_batch(chunk, ids)
                if res: total += res.get("mirrored", 0)
            except Exception as e:
                MIRROR_STATS["errors"] += 1
                _log_t("mirror: lote FALLIDO %s" % str(e)[:120])
        MIRROR_STATS["batches"] += 1; MIRROR_STATS["rows"] += len(batch)
        MIRROR_STATS["last"] = time.strftime("%H:%M:%S")
        _log_t("mirror: %d filas en %d lote(s), %d espejadas" %
               (len(batch), (len(batch) + MIRROR_CHUNK - 1) // MIRROR_CHUNK, total), t0)

def _mirror_ensure_worker():
    global _mirror_thread
    if _mirror_thread is not None and _mirror_thread.is_alive(): return
    with _mirror_lock_boot:
        if _mirror_thread is not None and _mirror_thread.is_alive(): return
        _mirror_thread = threading.Thread(target=_mirror_worker_loop,
                                          name="mirror-worker", daemon=True)
        _mirror_thread.start()

_mirror_lock_boot = threading.Lock()

def do_save(payload):
    upserts = payload.get("upserts", []) or []
    deletes = payload.get("deletes", []) or []
    actor = payload.get("actor", {}) or {}          # v4.0: {email, name} for the activity log
    def _do(ctx):
        t0 = time.time()
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        _log_t("save: session ok (%d upserts, %d deletes)" % (len(upserts), len(deletes)), t0)
        digest = _get_digest(ctx)
        # v4.3: ensure_columns ONCE per process (was running on every save)
        created_cols, ensure_errors = ([], {})
        global _columns_ready
        if not _columns_ready:
            try:
                created_cols, ensure_errors = ensure_columns(ctx, digest)
            except Exception as e:
                ensure_errors = {"__ensure__": str(e)[:180]}
            _log_t("save: ensure_columns done", t0)
        created = updated = deleted = 0
        idmap = {}
        # --- FAST PATH: only Title + DataJson (the only thing the app reads) ---
        for u in upserts:
            uid = u.get("uid"); data = u.get("data")
            if not uid: continue
            item_id = _item_id(ctx, uid)          # v4.4 (D): cache uid -> Id
            if item_id is None:
                item_id = create_item(ctx, digest, uid, data); created += 1
            else:
                item_id = update_item(ctx, digest, item_id, uid, data); updated += 1
            if item_id: idmap[uid] = item_id
        for uid in deletes:
            item_id = _item_id(ctx, uid)
            if item_id is not None and recycle_item(ctx, digest, item_id): deleted += 1
            _cache_drop(uid)                       # el Id ya no es valido
        _log_t("save: fast write done (created=%d updated=%d deleted=%d)" % (created, updated, deleted), t0)
        # activity log (best-effort)
        if created or updated or deleted:
            detail = "created %d, updated %d, deleted %d" % (created, updated, deleted)
            try: _append_activity(ctx, digest, actor.get("email"), actor.get("name"), "edit", detail)
            except Exception: pass
        # --- MIRROR: off the critical path ---
        mirror_info = {"mode": "disabled"}
        if MIRROR_ENABLED and upserts:
            if MIRROR_IN_BACKGROUND:
                try:
                    # v4.4 (B): encolar en el trabajador unico, sin crear hilos.
                    _mirror_ensure_worker()
                    _mirror_enqueue(list(upserts), idmap)
                    mirror_info = {"mode": "queued", "pending": len(_mirror_pending)}
                except Exception as e:
                    mirror_info = {"mode": "queue-failed", "error": str(e)[:120]}
            else:
                mirror_info = _mirror_upserts(ctx, digest, upserts, idmap)
                mirror_info["mode"] = "inline"
                _log_t("save: mirror(inline) done", t0)
        _log_t("save: RESPONDING", t0)
        return {"ok": True, "created": created, "updated": updated, "deleted": deleted,
                "columns_created": created_cols, "ensure_errors": ensure_errors,
                "mirror": mirror_info}
    return _with_ctx(True, _do, prio=SP_PRIO_SAVE)      # v4.4 (A): adelanta a los sondeos

def do_approvals(ids):
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        cache = {}; out = {}
        for aid in ids or []:
            out[str(aid)] = resolve_status(ctx, aid, cache)
        return out
    return _with_ctx(True, _do)

def do_approvals_all():
    """Read-only: full list of approvals (Id + Global status + Requester name + Service details)."""
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        flds = _resolve_appr_fields(ctx)
        gf = flds.get("global") or APPR_STATUS_FIELD
        rq = flds.get("requester"); sv = flds.get("service")
        sel = ",".join(sorted(set([f for f in ["Id", gf, rq, sv] if f])))
        base = _list_base(SITE_APPROVALS, LIST_APPROVALS)
        url = "%s/items?$select=%s&$top=2000&$orderby=Id desc" % (base, sel)
        out = []
        while url:
            data = _get_json(ctx, url)
            for it in data.get("value", []):
                iid = it.get("Id")
                if iid is None: continue
                raw = it.get(gf)
                st, col = map_status(raw)
                out.append({"id": iid, "status": st, "color": col, "raw": raw,
                            "requester": (it.get(rq) if rq else None),
                            "service": (it.get(sv) if sv else None)})
            nxt = data.get("@odata.nextLink") or data.get("odata.nextLink")
            if nxt and not nxt.startswith("http"):
                nxt = "%s/_api/%s" % (SITE_APPROVALS, nxt.lstrip("/"))
            url = nxt
        return out
    return _with_ctx(True, _do)

def _kpi_existing_names(ctx):
    url = "%s/fields?$select=InternalName&$top=500" % _list_base(SITE_WEB, LIST_KPI)
    return set(f.get("InternalName") for f in _get_json(ctx, url).get("value", []))

def ensure_kpi_columns(ctx, digest):
    """Create any missing columns in the KPI history list (list itself must already exist)."""
    have = _kpi_existing_names(ctx)
    created = []; errors = {}
    for name, sptype in KPI_COLUMNS:
        if name in have: continue
        hdr = {"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest}
        base = _list_base(SITE_WEB, LIST_KPI)
        try:
            if sptype == "Note":
                schema = ("<Field Type='Note' DisplayName='%s' Name='%s' StaticName='%s' NumLines='6' RichText='FALSE' />" % (name, name, name))
                r = ctx.request.post("%s/fields/createfieldasxml" % base, headers=hdr,
                                     data=json.dumps({"parameters": {"SchemaXml": schema, "Options": 8}}), timeout=60000)
            else:
                r = ctx.request.post("%s/fields" % base, headers=hdr,
                                     data=json.dumps({"Title": name, "FieldTypeKind": _KIND[sptype]}), timeout=60000)
            if r.status in (200, 201):
                created.append(name)
                try:
                    ctx.request.post("%s/views/getbytitle('All Items')/viewfields/addviewfield('%s')" % (base, name),
                                     headers={"Accept": SP_JSON, "X-RequestDigest": digest}, timeout=30000)
                except Exception: pass
            else:
                errors[name] = "%s: %s" % (r.status, (r.text() or "")[:150])
        except Exception as e:
            errors[name] = str(e)[:150]
    return created, errors

def _kpi_find(ctx, year, week, org):
    base = _list_base(SITE_WEB, LIST_KPI)
    flt = "Year eq %d and CW eq %d and Org eq '%s'" % (int(year), int(week), str(org).replace("'", "''"))
    url = "%s/items?$select=Id&$filter=%s&$top=1" % (base, flt)
    try:
        vals = _get_json(ctx, url).get("value", [])
        return vals[0].get("Id") if vals else None
    except Exception:
        return None

def _kpi_isodate(v):
    """SharePoint DateTime wants full ISO; accept 'YYYY-MM-DD' and expand it."""
    if not v: return None
    v = str(v)
    if len(v) == 10: return v + "T00:00:00Z"
    return v

def _kpi_body(s):
    return {
        "Title": "%s-CW%s-%s" % (s.get("year"), str(s.get("cw")).zfill(2), s.get("org")),
        "Year": _num(s.get("year")), "CW": _num(s.get("cw")), "SnapDate": _kpi_isodate(s.get("date")),
        "Org": s.get("org") or "ALL",
        "Benches": _num(s.get("benches")), "TotalBenches": _num(s.get("total")),
        "Launches": _num(s.get("starts")),
        "Finishing": _num(s.get("ends")), "AccruingHours": _num(s.get("running")),
        # Retiradas en 4.9.3: se vacian (ver KPI_RETIRED).
        "NoApprovedID": None, "Behind": None, "RiskBehind": None,
        # NotStarted se sigue escribiendo con el mismo valor que DelayedDeclared,
        # solo como respaldo: la app lee primero DelayedDeclared.
        "NotStarted": _num(s.get("delayed")),
        "DelayedDeclared": _num(s.get("delayedDeclared", s.get("delayed"))),
        "OccHoursBusy": _num(s.get("occBusy")), "OccHoursCap": _num(s.get("occCap")),
        "OccPct": _num(s.get("occPct")),
        "Unplanned": _num(s.get("unplanned")),
        "ReasonResources": _num((s.get("reasons") or {}).get("ReasonResources")),
        "ReasonParts": _num((s.get("reasons") or {}).get("ReasonParts")),
        "ReasonBreakdown": _num((s.get("reasons") or {}).get("ReasonBreakdown")),
        "ReasonTestDrop": _num((s.get("reasons") or {}).get("ReasonTestDrop")),
        "ReasonPriority": _num((s.get("reasons") or {}).get("ReasonPriority")),
        "ReasonOther": _num((s.get("reasons") or {}).get("ReasonOther")),
        "KPIJson": json.dumps(s, ensure_ascii=False),
    }

def _kpi_create_min(ctx, digest, base, title):
    """Create a row with only Title; return its Id (robust against Choice/type issues)."""
    r = ctx.request.post("%s/items" % base,
        headers={"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest},
        data=json.dumps({"Title": title}), timeout=60000)
    if r.status not in (200, 201, 204):
        return None, "%s %s" % (r.status, (r.text() or "")[:150])
    safe = str(title).replace("'", "''")
    url = "%s/items?$select=Id&$filter=Title eq '%s'&$top=1&$orderby=Id desc" % (base, safe)
    try:
        vals = _get_json(ctx, url).get("value", [])
        return (vals[0].get("Id") if vals else None), ""
    except Exception as e:
        return None, str(e)[:120]

def do_save_kpis(snaps):
    """Write weekly KPI snapshots into TestBenchKPIHistory, FIELD BY FIELD so one bad
       column (e.g. a Choice 'Org' with unexpected options) never blocks the whole row."""
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        created_cols, col_errors = ([], {})
        try:
            created_cols, col_errors = ensure_kpi_columns(ctx, digest)
        except Exception as e:
            col_errors = {"__ensure__": str(e)[:150]}
        base = _list_base(SITE_WEB, LIST_KPI)
        have = set()
        try: have = _kpi_existing_names(ctx)
        except Exception: pass
        created = updated = 0; errors = []; field_errors = {}
        for s in (snaps or []):
            body = _kpi_body(s)
            title = body["Title"]
            iid = _kpi_find(ctx, s.get("year"), s.get("cw"), s.get("org"))
            if iid is None:
                iid, cerr = _kpi_create_min(ctx, digest, base, title)
                if iid is None:
                    errors.append("create %s: %s" % (title, cerr)); continue
                created += 1
            else:
                updated += 1
            for k, v in body.items():
                if k == "Title": continue
                if have and k not in have:
                    field_errors[k] = "column not found"; continue
                ok, st, txt = _merge_diag_list(ctx, digest, base, iid, {k: v})
                if not ok:
                    field_errors[k] = "%s %s" % (st, txt)
        return {"ok": True, "created": created, "updated": updated,
                "columns_created": created_cols, "col_errors": col_errors,
                "field_errors": field_errors, "errors": errors[:5]}
    return _with_ctx(True, _do)

def _merge_diag_list(ctx, digest, base, item_id, body):
    r = ctx.request.post("%s/items(%d)" % (base, int(item_id)),
        headers={"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest,
                 "IF-MATCH": "*", "X-HTTP-Method": "MERGE"}, data=json.dumps(body), timeout=60000)
    ok = r.status in (200, 204)
    return ok, r.status, ("" if ok else (r.text() or "")[:150])

def do_load_kpis():
    """Read the whole KPI history back (for weighted views)."""
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        base = _list_base(SITE_WEB, LIST_KPI)
        # v4.9.3: pedir SOLO las columnas que existen. Si se pide por $select una
        # columna que aun no se ha creado (p. ej. antes de la primera foto con
        # esta version), SharePoint devuelve error y no se cargaria NADA.
        try: have = _kpi_existing_names(ctx)
        except Exception: have = set()
        cols = [n for n, _ in KPI_COLUMNS if (not have or n in have)]
        if "KPIJson" in cols: cols = [c for c in cols if c != "KPIJson"] + ["KPIJson"]
        sel = ",".join(cols)
        url = "%s/items?$select=%s&$top=2000&$orderby=Year asc,CW asc" % (base, sel)
        out = []
        while url:
            data = _get_json(ctx, url)
            out.extend(data.get("value", []))
            nxt = data.get("@odata.nextLink") or data.get("odata.nextLink")
            if nxt and not nxt.startswith("http"): nxt = "%s/_api/%s" % (SITE_WEB, nxt.lstrip("/"))
            url = nxt
        return out
    return _with_ctx(True, _do)

# ---- v3.6: shared config lists (responsibles / projects / benches) --------
def _list_field_names(ctx, title):
    url = "%s/fields?$select=InternalName&$top=500" % _list_base(SITE_WEB, title)
    return set(f.get("InternalName") for f in _get_json(ctx, url).get("value", []))

# v4.5: create a SharePoint list if missing (BaseTemplate 100 = generic list).
def _ensure_list_exists(ctx, digest, title):
    try:
        _get_json(ctx, "%s/fields?$select=InternalName&$top=1" % _list_base(SITE_WEB, title))
        return True
    except Exception:
        pass
    try:
        hdr = {"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest}
        body = {"Title": title, "BaseTemplate": 100, "AllowContentTypes": True, "ContentTypesEnabled": False}
        r = ctx.request.post("%s/_api/web/lists" % SITE_WEB, headers=hdr, data=json.dumps(body), timeout=60000)
        if r.status in (200, 201):
            _log_t("list created: %s" % title); return True
        _log_t("list create %s -> %s" % (title, r.status))
    except Exception as e:
        _log_t("list create %s FAILED %s" % (title, str(e)[:80]))
    try:
        _get_json(ctx, "%s/fields?$select=InternalName&$top=1" % _list_base(SITE_WEB, title))
        return True
    except Exception:
        return False

def _ensure_list_columns(ctx, digest, title, coldefs):
    have = _list_field_names(ctx, title)
    created = []; errors = {}
    base = _list_base(SITE_WEB, title)
    for name, sptype in coldefs:
        if name in have: continue
        hdr = {"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest}
        try:
            if sptype == "Note":
                schema = ("<Field Type='Note' DisplayName='%s' Name='%s' StaticName='%s' NumLines='6' RichText='FALSE' />" % (name, name, name))
                r = ctx.request.post("%s/fields/createfieldasxml" % base, headers=hdr,
                                     data=json.dumps({"parameters": {"SchemaXml": schema, "Options": 8}}), timeout=60000)
            else:
                r = ctx.request.post("%s/fields" % base, headers=hdr,
                                     data=json.dumps({"Title": name, "FieldTypeKind": _KIND[sptype]}), timeout=60000)
            if r.status in (200, 201):
                created.append(name)
                try:
                    ctx.request.post("%s/views/getbytitle('All Items')/viewfields/addviewfield('%s')" % (base, name),
                                     headers={"Accept": SP_JSON, "X-RequestDigest": digest}, timeout=30000)
                except Exception: pass
            else:
                errors[name] = "%s: %s" % (r.status, (r.text() or "")[:150])
        except Exception as e:
            errors[name] = str(e)[:150]
    return created, errors

def _list_read(ctx, title, cols):
    # v4.5: ROBUST read. Only $select columns that actually exist, so a missing or
    # renamed column (e.g. WhenTs) no longer breaks the whole GET (0 rows).
    base = _list_base(SITE_WEB, title)
    want = ["Id", "Title"] + [c for c, _ in cols]
    try:
        have = _list_field_names(ctx, title)
        sel = ",".join([c for c in want if c in have or c in ("Id", "Title")])
    except Exception:
        sel = "Id,Title"
    if not sel:
        sel = "Id,Title"
    url = "%s/items?$select=%s&$top=2000&$orderby=Id asc" % (base, sel)
    out = []
    while url:
        data = _get_json(ctx, url); out.extend(data.get("value", []))
        nxt = data.get("@odata.nextLink") or data.get("odata.nextLink")
        if nxt and not nxt.startswith("http"): nxt = "%s/_api/%s" % (SITE_WEB, nxt.lstrip("/"))
        url = nxt
    return out

def _list_create_min(ctx, digest, base, title):
    r = ctx.request.post("%s/items" % base,
        headers={"Accept": SP_JSON, "Content-Type": SP_JSON, "X-RequestDigest": digest},
        data=json.dumps({"Title": title}), timeout=60000)
    if r.status not in (200, 201, 204): return None
    safe = str(title).replace("'", "''")
    url = "%s/items?$select=Id&$filter=Title eq '%s'&$top=1&$orderby=Id desc" % (base, safe)
    try:
        vals = _get_json(ctx, url).get("value", [])
        return vals[0].get("Id") if vals else None
    except Exception:
        return None

def _reconcile_list(ctx, digest, title, coldefs, rows):
    """Make the SharePoint list match `rows` (dicts with Title + column values)."""
    created_cols, col_errors = _ensure_list_columns(ctx, digest, title, coldefs)
    base = _list_base(SITE_WEB, title)
    existing = {}
    for it in _list_read(ctx, title, coldefs):
        t = it.get("Title")
        if t is not None: existing[t] = it.get("Id")
    incoming = set()
    created = updated = deleted = 0; field_errors = {}
    for row in rows:
        t = row.get("Title")
        if t is None: continue
        incoming.add(t)
        iid = existing.get(t)
        if iid is None:
            iid = _list_create_min(ctx, digest, base, t)
            if iid is None: continue
            created += 1
        else:
            updated += 1
        for k, v in row.items():
            if k == "Title": continue
            ok, st, txt = _merge_diag_list(ctx, digest, base, iid, {k: v})
            if not ok: field_errors[k] = "%s %s" % (st, txt)
    for t, iid in existing.items():
        if t not in incoming:
            try:
                ctx.request.post("%s/items(%d)/recycle" % (base, int(iid)),
                    headers={"Accept": SP_JSON, "X-RequestDigest": digest}, timeout=60000)
                deleted += 1
            except Exception: pass
    return {"created": created, "updated": updated, "deleted": deleted,
            "columns_created": created_cols, "col_errors": col_errors, "field_errors": field_errors}

def _sorted_titles(rows):
    rows = sorted(rows, key=lambda it: (it.get("Seq") if it.get("Seq") is not None else 1e9, it.get("Id") or 0))
    return [it.get("Title") for it in rows if it.get("Title")]

def do_load_config():
    """Read the three shared config lists. Missing lists just come back empty."""
    def _do(ctx):
        t0 = time.time()
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        _log_t("config: session ok, reading 3 catalog lists", t0)
        out = {"responsibles": [], "projects": [], "benches": []}
        try:
            out["responsibles"] = _sorted_titles(_list_read(ctx, LIST_RESP, RESP_COLUMNS))
        except Exception as e:
            out["responsibles_error"] = str(e)[:120]
        try:
            out["projects"] = _sorted_titles(_list_read(ctx, LIST_PROJ, PROJ_COLUMNS))
        except Exception as e:
            out["projects_error"] = str(e)[:120]
        try:
            rows = sorted(_list_read(ctx, LIST_BENCH, BENCH_COLUMNS),
                          key=lambda it: (it.get("Seq") if it.get("Seq") is not None else 1e9, it.get("Id") or 0))
            out["benches"] = [{"name": it.get("Title"), "groupKey": it.get("GroupKey"),
                               "groupName": it.get("GroupName"), "dot": it.get("Dot"),
                               "org": it.get("Org"), "seq": it.get("Seq")}
                              for it in rows if it.get("Title")]
        except Exception as e:
            out["benches_error"] = str(e)[:120]
        _log_t("config: catalogs read (resp=%d proj=%d bench=%d)" % (
            len(out.get("responsibles") or []), len(out.get("projects") or []),
            len(out.get("benches") or [])), t0)
        return out
    return _with_ctx(True, _do)

def do_save_config(cfg):
    resp = cfg.get("responsibles") or []
    proj = cfg.get("projects") or []
    bench = cfg.get("benches") or []
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        result = {"ok": True}
        # v3.7: people now live in TestBenchUsers. We FREEZE TestBenchResponsibles:
        # if the client does not send a non-empty 'responsibles' array we DO NOT
        # touch that list (never wipe it). It is only reconciled if explicitly sent.
        if resp:
            try:
                rows = [{"Title": n, "Seq": i + 1} for i, n in enumerate(resp) if n]
                result["responsibles"] = _reconcile_list(ctx, digest, LIST_RESP, RESP_COLUMNS, rows)
            except Exception as e:
                result["responsibles"] = {"error": str(e)[:150]}
        else:
            result["responsibles"] = {"skipped": "frozen (people come from TestBenchUsers)"}
        try:
            rows = [{"Title": n, "Seq": i + 1} for i, n in enumerate(proj) if n]
            result["projects"] = _reconcile_list(ctx, digest, LIST_PROJ, PROJ_COLUMNS, rows)
        except Exception as e:
            result["projects"] = {"error": str(e)[:150]}
        try:
            rows = []
            for b in bench:
                if not b or not b.get("name"): continue
                rows.append({"Title": b.get("name"), "GroupKey": b.get("groupKey"),
                             "GroupName": b.get("groupName"), "Dot": b.get("dot"),
                             "Org": b.get("org"), "Seq": _num(b.get("seq"))})
            result["benches"] = _reconcile_list(ctx, digest, LIST_BENCH, BENCH_COLUMNS, rows)
        except Exception as e:
            result["benches"] = {"error": str(e)[:150]}
        return result
    return _with_ctx(True, _do)

def do_diag_config():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        info = {}
        for title, cols in ((LIST_RESP, RESP_COLUMNS), (LIST_PROJ, PROJ_COLUMNS), (LIST_BENCH, BENCH_COLUMNS)):
            try:
                have = _list_field_names(ctx, title)
                info[title] = {"exists": True, "count": len(_list_read(ctx, title, cols)),
                               "missingColumns": [n for n, _ in cols if n not in have]}
            except Exception as e:
                info[title] = {"exists": False, "error": str(e)[:120]}
        return {"site": SITE_WEB, "lists": info}
    return _with_ctx(True, _do)

# ---- v3.7: user detection, roles and version broadcast --------------------
def _norm_role(v):
    s = (v or "").strip().lower().replace(" ", "")
    if s in ("admin", "administrator"): return "admin"
    if s in ("editor", "edit", "write"): return "editor"
    return "readonly"

def _email_from_currentuser(it):
    """Extract a clean email from the SharePoint currentuser payload."""
    for k in ("Email", "UserPrincipalName"):
        v = (it.get(k) or "").strip()
        if v and "@" in v:
            return v.lower()
    login = (it.get("LoginName") or "").strip()
    if "|" in login:
        tail = login.split("|")[-1]
        if "@" in tail:
            return tail.lower()
    return ""

def _read_users(ctx):
    """Return list of dicts {title,email,role,responsibleName} from TestBenchUsers."""
    rows = _list_read(ctx, LIST_USERS, USER_COLUMNS)
    out = []
    for it in rows:
        out.append({
            "title": it.get("Title") or "",
            "email": (it.get("Email") or "").strip().lower(),
            "role": _norm_role(it.get("Role")),
            "responsibleName": it.get("ResponsibleName") or "",
        })
    return out

def _seed_users_if_empty(ctx, digest):
    """If the list exists but is empty, seed it with SEED_USERS. Never deletes."""
    try:
        _ensure_list_columns(ctx, digest, LIST_USERS, USER_COLUMNS)
    except Exception:
        pass
    try:
        existing = _read_users(ctx)
    except Exception:
        return False  # list probably missing; skip silently
    if existing:
        return False
    base = _list_base(SITE_WEB, LIST_USERS)
    for (title, email, role, resp) in SEED_USERS:
        iid = _list_create_min(ctx, digest, base, title)
        if iid is None:
            continue
        for k, v in (("Email", email), ("Role", role), ("ResponsibleName", resp)):
            try: _merge_diag_list(ctx, digest, base, iid, {k: v})
            except Exception: pass
    return True

def do_whoami():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        # who is logged in (SSO)
        email = ""; display = ""
        try:
            it = _get_json(ctx, "%s/_api/web/currentuser?$select=Title,Email,LoginName,UserPrincipalName" % SITE_WEB)
            email = _email_from_currentuser(it)
            display = (it.get("Title") or "").strip()
        except Exception:
            pass
        # ensure list columns + seed if empty
        users = []
        try:
            digest = _get_digest(ctx)
            _seed_users_if_empty(ctx, digest)
            users = _read_users(ctx)
        except Exception as e:
            users = []
        me = None
        for u in users:
            if u["email"] and u["email"] == email:
                me = u; break
        role = me["role"] if me else "readonly"
        resp = me["responsibleName"] if me else ""
        if not display:
            display = (me["title"] if me else "") or (resp or email)
        try:
            digest = _get_digest(ctx)
            _append_activity(ctx, digest, email, display, "connect", "role=%s" % role)
        except Exception: pass
        return {"email": email, "displayName": display, "role": role,
                "responsibleName": resp, "isAdmin": role == "admin",
                "users": users}
    return _with_ctx(True, _do)

def do_save_users(payload):
    users = (payload or {}).get("users", []) or []
    rows = []
    for u in users:
        email = (u.get("email") or "").strip().lower()
        if not email: continue
        rows.append({"Title": (u.get("title") or email),
                     "Email": email,
                     "Role": _norm_role(u.get("role")).capitalize().replace("Readonly", "ReadOnly"),
                     "ResponsibleName": u.get("responsibleName") or None})
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        res = _reconcile_list(ctx, digest, LIST_USERS, USER_COLUMNS, rows)
        return {"ok": True, "users": res}
    return _with_ctx(True, _do)

def _ver_parts(v):
    "4.9.2 -> (4, 9, 2). Comparacion numerica por tramos (4.10 > 4.9)."
    return tuple(int(x) for x in re.findall(r"\d+", str(v or "")))

def _ver_cmp(a, b):
    pa, pb = _ver_parts(a), _ver_parts(b)
    n = max(len(pa), len(pb))
    pa = pa + (0,) * (n - len(pa)); pb = pb + (0,) * (n - len(pb))
    return (pa > pb) - (pa < pb)

def _publish_version(ctx, digest):
    """Best-effort: write APP_VERSION into TestBenchAppConfig / 'CurrentVersion'."""
    if not PUBLISH_VERSION:
        return False  # MODO PRUEBA: no tocar la lista compartida
    try:
        _ensure_list_columns(ctx, digest, LIST_APPCFG, APPCFG_COLUMNS)
    except Exception:
        return False
    base = _list_base(SITE_WEB, LIST_APPCFG)
    try:
        vals = _get_json(ctx, "%s/items?$select=Id,Title,Value&$filter=Title eq 'CurrentVersion'&$top=1" % base).get("value", [])
    except Exception:
        return False
    iid = vals[0].get("Id") if vals else None
    if iid is None:
        iid = _list_create_min(ctx, digest, base, "CurrentVersion")
        if iid is None: return False
    else:
        # v4.9.2: NUNCA rebajar la version publicada. Antes, cualquiera que abriese
        # una version antigua escribia SU version en la lista, y el resto del equipo
        # (con versiones mas nuevas) veia el aviso de "version antigua". Ahora solo
        # se escribe si esta version es estrictamente superior a la publicada.
        cur = (vals[0].get("Value") or "").strip()
        if cur and _ver_cmp(APP_VERSION, cur) <= 0:
            return True
    ok, st, txt = _merge_diag_list(ctx, digest, base, iid, {"Value": APP_VERSION})
    return ok

def publish_version_bg():
    """Run once at startup (best-effort) so other open apps learn the new version."""
    try:
        def _do(ctx):
            if not _ensure_live_session(ctx): return False
            digest = _get_digest(ctx)
            return _publish_version(ctx, digest)
        _with_ctx(True, _do)
    except Exception:
        pass

def do_app_version():
    if not PUBLISH_VERSION:
        # MODO PRUEBA: no leemos la lista; reportamos igual que el backend para
        # que TU app 4.7 no muestre el banner de version.
        return {"backend": APP_VERSION, "published": APP_VERSION}
    def _do(ctx):
        published = APP_VERSION
        try:
            if _ensure_live_session(ctx):
                base = _list_base(SITE_WEB, LIST_APPCFG)
                vals = _get_json(ctx, "%s/items?$select=Title,Value&$filter=Title eq 'CurrentVersion'&$top=1" % base).get("value", [])
                if vals and (vals[0].get("Value") or "").strip():
                    published = (vals[0].get("Value") or "").strip()
        except Exception:
            pass
        return {"backend": APP_VERSION, "published": published}
    try:
        return _with_ctx(True, _do)
    except Exception:
        return {"backend": APP_VERSION, "published": APP_VERSION}

# ---- v4.0/v4.3: live presence (connected users) --------------------------
def _initials(name, email):
    src = (name or "").strip()
    if src:
        parts = [p for p in re.split(r"\s+", src) if p]
        if len(parts) >= 2:
            return (parts[0][0] + parts[-1][0]).upper()
        return parts[0][:2].upper()
    return (email or "?")[:2].upper()

def _color_for(email):
    palette = ["#23BCA3", "#4C8DFF", "#E0A458", "#E86A92", "#B08BEA",
               "#F2A64B", "#3DD68C", "#6BB5FF", "#F5A623", "#9AD14B"]
    s = (email or "x"); h = 0
    for ch in s:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return palette[h % len(palette)]

_presence_cols_ready = False
def do_presence():
    def _do(ctx):
        global _presence_cols_ready
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        email = ""; display = ""
        try:
            it = _get_json(ctx, "%s/_api/web/currentuser?$select=Title,Email,LoginName,UserPrincipalName" % SITE_WEB)
            email = _email_from_currentuser(it); display = (it.get("Title") or "").strip()
        except Exception: pass
        digest = _get_digest(ctx)
        if not _presence_cols_ready:
            try: _ensure_list_exists(ctx, digest, LIST_PRESENCE); _ensure_list_columns(ctx, digest, LIST_PRESENCE, PRESENCE_COLUMNS); _presence_cols_ready = True
            except Exception: pass
        base = _list_base(SITE_WEB, LIST_PRESENCE)
        now = time.time()
        now_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now))
        if email:
            safe = email.replace("'", "''")
            try:
                vals = _get_json(ctx, "%s/items?$select=Id&$filter=%s eq '%s'&$top=1" % (base, TITLE_FIELD, safe)).get("value", [])
            except Exception: vals = []
            iid = vals[0].get("Id") if vals else None
            if iid is None:
                iid = _list_create_min(ctx, digest, base, email)
            if iid is not None:
                # v4.3: single MERGE with all presence fields (was field-by-field)
                body = {"Email": email, "DisplayName": display or email,
                        "Initials": _initials(display, email), "Color": _color_for(email),
                        "LastSeen": now_iso}
                try: _merge_diag_list(ctx, digest, base, iid, body)
                except Exception: pass
        online = []
        try:
            sel = "Title,Email,DisplayName,Initials,Color,LastSeen"
            rows = _get_json(ctx, "%s/items?$select=%s&$top=200" % (base, sel)).get("value", [])
            for it in rows:
                ls = it.get("LastSeen")
                if not ls: continue
                try:
                    ts = time.mktime(time.strptime(str(ls)[:19], "%Y-%m-%dT%H:%M:%S")) - time.timezone
                except Exception:
                    continue
                if now - ts <= PRESENCE_TTL:
                    online.append({"email": it.get("Email") or it.get("Title"),
                                   "name": it.get("DisplayName") or it.get("Title"),
                                   "initials": it.get("Initials") or _initials(it.get("DisplayName"), it.get("Email")),
                                   "color": it.get("Color") or _color_for(it.get("Email")),
                                   "me": (it.get("Email") or "").lower() == (email or "").lower()})
        except Exception: pass
        online.sort(key=lambda u: (not u.get("me"), (u.get("name") or "").lower()))
        return {"me": email, "online": online}
    try:
        return _with_ctx(True, _do, prio=SP_PRIO_BG)   # v4.4: nunca delante de un save
    except Exception as e:
        return {"me": "", "online": [], "error": str(e)[:120]}

# ---- v4.0: activity log ----------------------------------------------------
_activity_cols_ready = False
def _append_activity(ctx, digest, email, name, action, detail):
    # v4.4: las columnas se aseguran UNA vez por proceso (antes, en cada save).
    global _activity_cols_ready
    if not _activity_cols_ready:
        try:
            _ensure_list_exists(ctx, digest, LIST_ACTIVITY)   # v4.5
            _ensure_list_columns(ctx, digest, LIST_ACTIVITY, ACTIVITY_COLUMNS)
            _activity_cols_ready = True
        except Exception: pass
    base = _list_base(SITE_WEB, LIST_ACTIVITY)
    when = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    title = "%s | %s" % (when, (email or "?"))
    iid = _list_create_min(ctx, digest, base, title)
    if iid is None: return False
    body = {"Email": email or "", "DisplayName": name or email or "",
            "Action": action or "", "Detail": detail or "", "WhenTs": when}
    try: _merge_diag_list(ctx, digest, base, iid, body)
    except Exception: pass
    return True

def do_activity_log(top=200):
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        base = _list_base(SITE_WEB, LIST_ACTIVITY)
        # v4.5: ROBUST read. The old fixed $select=...,WhenTs returned ZERO rows if a
        # column was missing/renamed. Now discover real columns, select only those
        # that exist, order by Id desc, and fall back to the row Title if needed.
        try:
            have = _list_field_names(ctx, LIST_ACTIVITY)
        except Exception:
            have = set()
        wanted = ["Email", "DisplayName", "Action", "Detail", "WhenTs"]
        selcols = ["Id", "Title"] + [c for c in wanted if c in have]
        sel = ",".join(selcols)
        url = "%s/items?$select=%s&$top=%d&$orderby=Id desc" % (base, sel, int(top))
        out = []
        try:
            for it in _get_json(ctx, url).get("value", []):
                title = it.get("Title") or ""
                w = it.get("WhenTs"); em = it.get("Email")
                if (not w or not em) and " | " in title:
                    tw, te = title.split(" | ", 1)
                    w = w or tw; em = em or te
                out.append({"email": em, "name": it.get("DisplayName") or em,
                            "action": it.get("Action"), "detail": it.get("Detail"),
                            "when": w})
        except Exception as e:
            return {"rows": [], "error": str(e)[:200]}
        return {"rows": out, "columns": sorted(list(have))}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"rows": [], "error": str(e)[:200]}

def do_diag_activity():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        info = {"list": LIST_ACTIVITY}
        try:
            have = _list_field_names(ctx, LIST_ACTIVITY)
            info["exists"] = True
            info["missingColumns"] = [n for n, _ in ACTIVITY_COLUMNS if n not in have]
            info["present"] = [n for n, _ in ACTIVITY_COLUMNS if n in have]
            try:
                cnt = _get_json(ctx, "%s/items?$select=Id&$top=1&$orderby=Id desc" % _list_base(SITE_WEB, LIST_ACTIVITY)).get("value", [])
                info["hasRows"] = bool(cnt)
            except Exception: pass
        except Exception as e:
            info["exists"] = False; info["error"] = str(e)[:150]
        return info
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"list": LIST_ACTIVITY, "error": str(e)[:150]}

def do_log_event(payload):
    p = payload or {}
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        ok = _append_activity(ctx, digest, p.get("email"), p.get("name"),
                              p.get("action") or "event", p.get("detail") or "")
        return {"ok": bool(ok)}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"ok": False, "error": str(e)[:150]}

# ---- v4.0: silent self-update ---------------------------------------------
def _release_base():
    return "%s/_api/web/GetFolderByServerRelativeUrl('%s')" % (
        RELEASE_SITE, RELEASE_FOLDER.replace("'", "''"))

def do_update_info():
    info = {"backend": APP_VERSION, "release_version": "", "canServerUpdate": bool(RELEASE_FOLDER)}
    if not RELEASE_FOLDER:
        return info
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        url = "%s/Files('manifest.json')/$value" % _release_base()
        try:
            r = ctx.request.get(url, timeout=30000)
            if r.ok:
                man = json.loads(r.text())
                info["release_version"] = str(man.get("version") or "")
                info["files"] = man.get("files") or []
        except Exception: pass
        return info
    try:
        return _with_ctx(True, _do)
    except Exception:
        return info

def do_self_update():
    if not RELEASE_FOLDER:
        return {"ok": False, "error": "RELEASE_FOLDER no configurado en el backend."}
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        base = _release_base()
        try:
            r = ctx.request.get("%s/Files('manifest.json')/$value" % base, timeout=30000)
            man = json.loads(r.text()) if r.ok else {}
        except Exception as e:
            return {"ok": False, "error": "No se pudo leer manifest.json: %s" % str(e)[:120]}
        files = man.get("files") or []
        written = []; errors = {}
        for fn in files:
            try:
                fr = ctx.request.get("%s/Files('%s')/$value" % (base, str(fn).replace("'", "''")), timeout=120000)
                if not fr.ok:
                    errors[fn] = "HTTP %s" % fr.status; continue
                dest = HERE / fn
                dest.parent.mkdir(parents=True, exist_ok=True)
                with open(str(dest), "wb") as f:
                    f.write(fr.body())
                written.append(fn)
            except Exception as e:
                errors[fn] = str(e)[:120]
        try:
            digest = _get_digest(ctx)
            _append_activity(ctx, digest, "", "system", "self_update",
                             "-> v%s: %s" % (man.get("version"), ", ".join(written)))
        except Exception: pass
        return {"ok": len(written) > 0, "version": man.get("version"),
                "written": written, "errors": errors}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"ok": False, "error": str(e)[:150]}

def do_release_file(name):
    if not RELEASE_FOLDER:
        return {"ok": False, "error": "RELEASE_FOLDER no configurado."}
    import base64 as _b64
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        url = "%s/Files('%s')/$value" % (_release_base(), str(name).replace("'", "''"))
        r = ctx.request.get(url, timeout=120000)
        if not r.ok:
            return {"ok": False, "error": "HTTP %s" % r.status}
        return {"ok": True, "name": name, "b64": _b64.b64encode(r.body()).decode("ascii")}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"ok": False, "error": str(e)[:150]}

# ---- v4.7: export the current Gantt to a nice, editable Excel -------------
def do_export_gantt(payload):
    """v4.8: render a DAILY Gantt matrix to an .xlsx (openpyxl): month band + CW
    band + day-number header, weekend/holiday shading, one merged colored cell per
    test/segment (colour by project), a project legend, frozen header & first
    column. Falls back to the old weekly layout if 'days' is not provided."""
    p = payload or {}
    title = (p.get("title") or "Gantt")
    days = p.get("days") or []
    weeks = p.get("weeks") or []
    rows = p.get("rows") or []
    legend = p.get("legend") or []
    daily = bool(days)
    cols = days if daily else weeks
    try:
        import base64 as _b64, io as _io
        from openpyxl import Workbook
        from openpyxl.styles import PatternFill, Font, Alignment, Border, Side
        from openpyxl.utils import get_column_letter

        wb = Workbook(); ws = wb.active; ws.title = "Gantt"
        thin = Side(style="thin", color="D9D9D9")
        border = Border(left=thin, right=thin, top=thin, bottom=thin)
        hdr_fill = PatternFill("solid", fgColor="0C1D2C")
        hdr_font = Font(bold=True, color="FFFFFF", size=10)
        day_font = Font(bold=False, color="FFFFFF", size=8)
        month_font = Font(bold=True, color="0C1D2C", size=10)
        center = Alignment(horizontal="center", vertical="center", wrap_text=True)
        left = Alignment(horizontal="left", vertical="center")
        we_fill = PatternFill("solid", fgColor="24384A")     # weekend shade (day header)
        hol_fill = PatternFill("solid", fgColor="5A2530")     # holiday shade
        today_fill = PatternFill("solid", fgColor="23BCA3")
        cell_we = PatternFill("solid", fgColor="EFF3F6")      # weekend shade (body grid)

        nC = len(cols)
        ncols = 1 + nC

        # Title row
        ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=max(2, ncols))
        c = ws.cell(row=1, column=1, value=title)
        c.font = Font(bold=True, size=13, color="0C1D2C"); c.alignment = left

        # Row 2 = month bands (merge consecutive equal months)
        ws.cell(row=2, column=1, value="").fill = hdr_fill
        i = 0
        while i < nC:
            m = cols[i].get("month"); span = 0
            while i + span < nC and cols[i + span].get("month") == m:
                span += 1
            c0 = 2 + i; c1 = 2 + i + span - 1
            if span > 1:
                ws.merge_cells(start_row=2, start_column=c0, end_row=2, end_column=c1)
            mc = ws.cell(row=2, column=c0, value=m); mc.font = month_font; mc.alignment = center
            mc.fill = PatternFill("solid", fgColor="E9EEF2")
            i += span

        # Row 3 = CW bands (merge consecutive equal CW). First col header label.
        a = ws.cell(row=3, column=1, value="Bench / Test"); a.fill = hdr_fill; a.font = hdr_font; a.alignment = center; a.border = border
        i = 0
        while i < nC:
            wv = cols[i].get("cw"); span = 0
            while i + span < nC and cols[i + span].get("cw") == wv:
                span += 1
            c0 = 2 + i; c1 = 2 + i + span - 1
            if span > 1:
                ws.merge_cells(start_row=3, start_column=c0, end_row=3, end_column=c1)
            cc = ws.cell(row=3, column=c0, value="CW%s" % wv)
            cc.fill = hdr_fill; cc.font = hdr_font; cc.alignment = center; cc.border = border
            i += span

        # Row 4 (daily only) = day numbers with weekend/holiday/today shading
        header_rows = 3
        if daily:
            header_rows = 4
            ws.cell(row=4, column=1, value="").fill = hdr_fill
            for di, d in enumerate(cols):
                cc = ws.cell(row=4, column=2 + di, value=d.get("day"))
                cc.font = day_font; cc.alignment = center; cc.border = border
                if d.get("today"): cc.fill = today_fill; cc.font = Font(bold=True, color="06231F", size=8)
                elif d.get("hol"): cc.fill = hol_fill
                elif d.get("we"): cc.fill = we_fill
                else: cc.fill = hdr_fill

        # Body
        r0 = header_rows + 1
        for ri, row in enumerate(rows):
            rr = r0 + ri
            lc = ws.cell(row=rr, column=1, value=row.get("label")); lc.alignment = left
            lc.border = border; lc.font = Font(size=10, bold=True, color="1D2D3B")
            lc.fill = PatternFill("solid", fgColor="F4F7F9")
            # light grid across the row (weekend shading on empty cells for daily)
            for di in range(nC):
                gcell = ws.cell(row=rr, column=2 + di); gcell.border = border
                if daily and (cols[di].get("we") or cols[di].get("hol")):
                    gcell.fill = cell_we
            # place each segment on FREE contiguous columns (no MergedCell clash)
            occupied = set()
            for seg in (row.get("cells") or []):
                c0 = max(0, int(seg.get("c0", 0)))
                c1 = min(nC - 1, max(c0, int(seg.get("c1", c0))))
                while c0 <= c1 and c0 in occupied:
                    c0 += 1
                if c0 > c1:
                    continue
                end = c0
                while end + 1 <= c1 and (end + 1) not in occupied:
                    end += 1
                col0 = 2 + c0; col1 = 2 + end
                if col1 > col0:
                    ws.merge_cells(start_row=rr, start_column=col0, end_row=rr, end_column=col1)
                cell = ws.cell(row=rr, column=col0)
                cell.value = seg.get("text") or ""
                hexcol = (seg.get("color") or "#8CA0AD").lstrip("#")
                cell.fill = PatternFill("solid", fgColor=hexcol.upper())
                cell.font = Font(size=9, color=("111111" if seg.get("dim") else "0B1620"), bold=not seg.get("dim"))
                cell.alignment = Alignment(horizontal="left", vertical="center", wrap_text=False)
                cell.border = border
                for cc in range(c0, end + 1):
                    occupied.add(cc)

        # Project legend (below the table)
        if legend:
            lr = r0 + len(rows) + 1
            t = ws.cell(row=lr, column=1, value="Leyenda de proyectos")
            t.font = Font(bold=True, size=11, color="0C1D2C")
            for li, lg in enumerate(legend):
                rr = lr + 1 + li
                sw = ws.cell(row=rr, column=1); sw.fill = PatternFill("solid", fgColor=(lg.get("color") or "#8CA0AD").lstrip("#").upper())
                sw.border = border
                nm = ws.cell(row=rr, column=2, value=lg.get("name"))
                nm.font = Font(size=10, color="1D2D3B"); nm.alignment = left

        # sizing + freeze
        ws.column_dimensions["A"].width = 34
        dayw = 2.9 if daily else 5.4
        for di in range(nC):
            ws.column_dimensions[get_column_letter(2 + di)].width = dayw
        ws.row_dimensions[1].height = 22
        ws.freeze_panes = "B%d" % (header_rows + 1)

        bio = _io.BytesIO(); wb.save(bio); bio.seek(0)
        b64 = _b64.b64encode(bio.read()).decode("ascii")
        safe = re.sub(r"[^A-Za-z0-9]+", "_", title)[:40].strip("_") or "gantt"
        fname = "%s_%s.xlsx" % (safe, time.strftime("%Y%m%d"))
        return {"ok": True, "b64": b64, "filename": fname}
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}

# ---- v4.5: auxiliary resources (catalog + manual blocks) ------------------
_res_cols_ready = False
def _ensure_res_lists(ctx, digest):
    global _res_cols_ready
    if _res_cols_ready: return
    try:
        _ensure_list_exists(ctx, digest, LIST_RESOURCES)
        _ensure_list_columns(ctx, digest, LIST_RESOURCES, RESOURCE_COLUMNS)
        _ensure_list_exists(ctx, digest, LIST_RESBLOCKS)
        _ensure_list_columns(ctx, digest, LIST_RESBLOCKS, RESBLOCK_COLUMNS)
        _res_cols_ready = True
    except Exception as e:
        _log_t("resources: ensure lists failed %s" % str(e)[:100])

def do_load_resources():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        _ensure_res_lists(ctx, digest)
        out = {"resources": [], "blocks": []}
        try:
            rows = sorted(_list_read(ctx, LIST_RESOURCES, RESOURCE_COLUMNS),
                          key=lambda it: (it.get("Seq") if it.get("Seq") is not None else 1e9, it.get("Id") or 0))
            out["resources"] = [{"name": it.get("Title"), "groupKey": it.get("GroupKey"),
                                 "groupName": it.get("GroupName"), "dot": it.get("Dot"),
                                 "code": it.get("EquipmentCode"), "org": it.get("Org"),
                                 "seq": it.get("Seq")} for it in rows if it.get("Title")]
        except Exception as e:
            out["resources_error"] = str(e)[:120]
        try:
            for it in _list_read(ctx, LIST_RESBLOCKS, RESBLOCK_COLUMNS):
                out["blocks"].append({"id": it.get("Id"), "title": it.get("Title"),
                                      "resource": it.get("Resource"),
                                      "start": (it.get("StartDate") or "")[:10],
                                      "end": (it.get("EndDate") or "")[:10],
                                      "reason": it.get("Reason"), "note": it.get("Note"),
                                      "author": it.get("Author")})
        except Exception as e:
            out["blocks_error"] = str(e)[:120]
        return out
    try:
        return _with_ctx(True, _do, prio=SP_PRIO_BG)
    except Exception as e:
        return {"resources": [], "blocks": [], "error": str(e)[:150]}

def do_save_resources(cfg):
    resources = (cfg or {}).get("resources") or []
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        _ensure_res_lists(ctx, digest)
        rows = []
        for i, r in enumerate(resources):
            if not r or not r.get("name"): continue
            rows.append({"Title": r.get("name"), "GroupKey": r.get("groupKey"),
                         "GroupName": r.get("groupName"), "Dot": r.get("dot"),
                         "EquipmentCode": r.get("code"), "Org": r.get("org"),
                         "Seq": _num(r.get("seq")) if r.get("seq") is not None else (i + 1)})
        try:
            res = _reconcile_list(ctx, digest, LIST_RESOURCES, RESOURCE_COLUMNS, rows)
            return {"ok": True, "resources": res}
        except Exception as e:
            return {"ok": False, "error": str(e)[:150]}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"ok": False, "error": str(e)[:150]}

def _res_iso(v):
    if not v: return None
    v = str(v)
    return v + "T00:00:00Z" if len(v) == 10 else v

def do_save_resource_block(payload):
    p = payload or {}
    actor = p.get("actor", {}) or {}
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        _ensure_res_lists(ctx, digest)
        base = _list_base(SITE_WEB, LIST_RESBLOCKS)
        iid = p.get("id")
        title = p.get("title") or ("BLK-%d" % int(time.time() * 1000))
        if not iid:
            iid = _list_create_min(ctx, digest, base, title)
            if iid is None:
                return {"ok": False, "error": "no se pudo crear el bloqueo"}
        body = {"Resource": p.get("resource") or "", "StartDate": _res_iso(p.get("start")),
                "EndDate": _res_iso(p.get("end")), "Reason": p.get("reason") or "",
                "Note": p.get("note") or "", "Author": (actor.get("name") or actor.get("email") or "")}
        ok, st, txt = _merge_diag_list(ctx, digest, base, int(iid), body)
        try: _append_activity(ctx, digest, actor.get("email"), actor.get("name"), "res_block",
                              "%s %s->%s (%s)" % (p.get("resource"), p.get("start"), p.get("end"), p.get("reason")))
        except Exception: pass
        return {"ok": bool(ok), "id": iid, "status": st}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"ok": False, "error": str(e)[:150]}

def do_delete_resource_block(payload):
    p = payload or {}
    iid = p.get("id")
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        digest = _get_digest(ctx)
        base = _list_base(SITE_WEB, LIST_RESBLOCKS)
        if iid is None: return {"ok": False, "error": "sin id"}
        try:
            r = ctx.request.post("%s/items(%d)/recycle" % (base, int(iid)),
                                 headers={"Accept": SP_JSON, "X-RequestDigest": digest}, timeout=60000)
            return {"ok": r.status in (200, 204)}
        except Exception as e:
            return {"ok": False, "error": str(e)[:120]}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"ok": False, "error": str(e)[:150]}

def do_diag_resources():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        info = {}
        for title, cols in ((LIST_RESOURCES, RESOURCE_COLUMNS), (LIST_RESBLOCKS, RESBLOCK_COLUMNS)):
            try:
                have = _list_field_names(ctx, title)
                info[title] = {"exists": True, "count": len(_list_read(ctx, title, cols)),
                               "missingColumns": [n for n, _ in cols if n not in have]}
            except Exception as e:
                info[title] = {"exists": False, "error": str(e)[:120]}
        return {"site": SITE_WEB, "lists": info}
    try:
        return _with_ctx(True, _do)
    except Exception as e:
        return {"error": str(e)[:150]}

def do_diag_users():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        try:
            have = _list_field_names(ctx, LIST_USERS)
            users = _read_users(ctx)
            return {"list": LIST_USERS, "exists": True, "count": len(users),
                    "missingColumns": [n for n, _ in USER_COLUMNS if n not in have],
                    "roles": {u["email"]: u["role"] for u in users}}
        except Exception as e:
            return {"list": LIST_USERS, "exists": False, "error": str(e)[:150]}
    return _with_ctx(True, _do)

def do_diag():
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        have = existing_internal_names(ctx)
        present = [n for n, _ in NEW_COLUMNS if n in have]
        missing = [n for n, _ in NEW_COLUMNS if n not in have]
        return {"site": SITE_WEB, "list": LIST_TITLE,
                "newColumnsPresent": present, "newColumnsMissing": missing,
                "lastEnsure": LAST_ENSURE, "totalFields": len(have)}
    return _with_ctx(True, _do)

def do_diag_kpi():
    """Diagnostics for the KPI history list: which expected columns exist / are missing + their type."""
    def _do(ctx):
        if not _ensure_live_session(ctx): raise PermissionError("no-session")
        base = _list_base(SITE_WEB, LIST_KPI)
        url = "%s/fields?$select=InternalName,Title,TypeAsString&$top=500" % base
        info = {}
        try:
            for f in _get_json(ctx, url).get("value", []):
                info[f.get("InternalName")] = f.get("TypeAsString")
        except Exception as e:
            return {"list": LIST_KPI, "error": "cannot read fields: %s" % str(e)[:150]}
        expected = [n for n, _ in KPI_COLUMNS]
        present = {n: info.get(n) for n in expected if n in info}
        missing = [n for n in expected if n not in info]
        return {"site": SITE_WEB, "list": LIST_KPI, "listExists": True,
                "expectedPresent": present, "expectedMissing": missing,
                "totalFields": len(info)}
    return _with_ctx(True, _do)

def has_session():
    if not _PW_OK and not ensure_playwright(): return False
    try: return _with_ctx(True, _ensure_live_session)
    except Exception: return False

def _shutdown_now():
    print("\n[Planner] Cerrando backend. Hasta luego."); _kill_edge(); os._exit(0)

def _watchdog():
    while True:
        time.sleep(3); now = time.time()
        with _state_lock:
            started = STARTED_AT; last = LAST_BEAT; got = GOT_BEAT
        if now - started < GRACE_S: continue
        if got:
            if AUTOCLOSE_ON_IDLE and (now - last > IDLE_TIMEOUT): _shutdown_now()
        else:
            if now - started > NOCONN_TIMEOUT:
                print("\n[Planner] Nunca se conecto la app; cierro por seguridad."); _shutdown_now()

def _open_browser_once(port):
    global _opened_browser
    if _opened_browser: return
    _opened_browser = True
    try: webbrowser.open("http://127.0.0.1:%d" % port)
    except Exception: pass

if _FLASK_OK:
    @app.route("/")
    def index(): return send_from_directory(str(HERE), HTML_FILE)
    @app.route("/api/health", methods=["GET"])
    def api_health():
        try: return jsonify({"session": bool(has_session())})
        except Exception as e: return jsonify({"session": False, "error": str(e)}), 200
    @app.route("/api/load", methods=["POST"])
    def api_load():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True  # activity keeps us alive
        try: return jsonify(do_load())
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/save", methods=["POST"])
    def api_save():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True  # activity keeps us alive
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_save(payload))
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/approvals", methods=["POST"])
    def api_approvals():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_approvals(payload.get("ids", [])))
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/approvals_all", methods=["POST", "GET"])
    def api_approvals_all():
        try:
            return jsonify(do_approvals_all())
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/save_kpis", methods=["POST"])
    def api_save_kpis():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_save_kpis(payload.get("snapshots", [])))
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/load_kpis", methods=["POST", "GET"])
    def api_load_kpis():
        try:
            return jsonify(do_load_kpis())
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/diag", methods=["GET", "POST"])
    def api_diag():
        try: return app.response_class(json.dumps(do_diag(), ensure_ascii=False, indent=2), mimetype="application/json")
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/diag_kpi", methods=["GET", "POST"])
    def api_diag_kpi():
        try: return app.response_class(json.dumps(do_diag_kpi(), ensure_ascii=False, indent=2), mimetype="application/json")
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/load_config", methods=["POST", "GET"])
    def api_load_config():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try: return jsonify(do_load_config())
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/save_config", methods=["POST"])
    def api_save_config():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_save_config(payload))
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/diag_config", methods=["GET", "POST"])
    def api_diag_config():
        try: return app.response_class(json.dumps(do_diag_config(), ensure_ascii=False, indent=2), mimetype="application/json")
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/whoami", methods=["POST", "GET"])
    def api_whoami():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try: return jsonify(do_whoami())
        except PermissionError: return jsonify({"error": "no-session", "role": "readonly"}), 401
        except Exception as e: return jsonify({"error": str(e), "role": "readonly"}), 500
    @app.route("/api/save_users", methods=["POST"])
    def api_save_users():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_save_users(payload))
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/app_version", methods=["POST", "GET"])
    def api_app_version():
        try: return jsonify(do_app_version())
        except Exception as e: return jsonify({"backend": APP_VERSION, "published": APP_VERSION, "error": str(e)}), 200
    @app.route("/api/diag_users", methods=["GET", "POST"])
    def api_diag_users():
        try: return app.response_class(json.dumps(do_diag_users(), ensure_ascii=False, indent=2), mimetype="application/json")
        except PermissionError: return jsonify({"error": "no-session"}), 401
        except Exception as e: return jsonify({"error": str(e)}), 500
    @app.route("/api/presence", methods=["POST", "GET"])
    def api_presence():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try: return jsonify(do_presence())
        except PermissionError: return jsonify({"online": [], "error": "no-session"}), 200
        except Exception as e: return jsonify({"online": [], "error": str(e)}), 200
    @app.route("/api/activity_log", methods=["POST", "GET"])
    def api_activity_log():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_activity_log(payload.get("top", 200)))
        except Exception as e: return jsonify({"rows": [], "error": str(e)}), 200
    @app.route("/api/log_event", methods=["POST"])
    def api_log_event():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_log_event(payload))
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/update_info", methods=["POST", "GET"])
    def api_update_info():
        try: return jsonify(do_update_info())
        except Exception as e: return jsonify({"backend": APP_VERSION, "error": str(e)}), 200
    @app.route("/api/self_update", methods=["POST"])
    def api_self_update():
        try: return jsonify(do_self_update())
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/release_file", methods=["POST"])
    def api_release_file():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_release_file(payload.get("name")))
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/export_gantt", methods=["POST"])
    def api_export_gantt():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_export_gantt(payload))
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/diag_activity", methods=["GET", "POST"])
    def api_diag_activity():
        try: return app.response_class(json.dumps(do_diag_activity(), ensure_ascii=False, indent=2), mimetype="application/json")
        except Exception as e: return jsonify({"error": str(e)}), 200
    @app.route("/api/load_resources", methods=["POST", "GET"])
    def api_load_resources():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try: return jsonify(do_load_resources())
        except PermissionError: return jsonify({"resources": [], "blocks": [], "error": "no-session"}), 200
        except Exception as e: return jsonify({"resources": [], "blocks": [], "error": str(e)}), 200
    @app.route("/api/save_resources", methods=["POST"])
    def api_save_resources():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_save_resources(payload))
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/save_resource_block", methods=["POST"])
    def api_save_resource_block():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_save_resource_block(payload))
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/delete_resource_block", methods=["POST"])
    def api_delete_resource_block():
        try:
            payload = request.get_json(force=True, silent=True) or {}
            return jsonify(do_delete_resource_block(payload))
        except Exception as e: return jsonify({"ok": False, "error": str(e)}), 200
    @app.route("/api/diag_resources", methods=["GET", "POST"])
    def api_diag_resources():
        try: return app.response_class(json.dumps(do_diag_resources(), ensure_ascii=False, indent=2), mimetype="application/json")
        except Exception as e: return jsonify({"error": str(e)}), 200
    @app.route("/api/heartbeat", methods=["POST"])
    def api_heartbeat():
        global LAST_BEAT, GOT_BEAT
        with _state_lock: LAST_BEAT = time.time(); GOT_BEAT = True
        return jsonify({"ok": True})
    @app.route("/api/shutdown", methods=["POST"])
    def api_shutdown():
        threading.Timer(0.5, _shutdown_now).start(); return jsonify({"ok": True})

if __name__ == "__main__":
    if not _FLASK_OK: raise SystemExit("Instala: pip install flask flask-cors playwright")
    port = int(os.environ.get("TOOLKIT_PORT", 5001))
    print("=" * 62)
    print(" BW Test Bench Planner - backend HIBRIDO (v%s)" % APP_VERSION)
    if not PUBLISH_VERSION:
        print(" *** MODO PRUEBA: no se publica version (TestBenchAppConfig intacto) ***")
    print(" Planner    :", SITE_WEB, "/", LIST_TITLE)
    print(" Config     :", LIST_RESP, "/", LIST_PROJ, "/", LIST_BENCH)
    print(" Users/Roles:", LIST_USERS, "(Admin/Editor/ReadOnly)")
    print(" Presencia  :", LIST_PRESENCE, "| Log:", LIST_ACTIVITY)
    print(" Recursos   :", LIST_RESOURCES, "| Bloqueos:", LIST_RESBLOCKS)
    print(" Mirror     :", ("ON/cola-fusionada" if (MIRROR_ENABLED and MIRROR_IN_BACKGROUND) else ("ON/inline" if MIRROR_ENABLED else "OFF")))
    print(" Conexion   :", ("CDP persistente + cola con prioridad" if SP_PERSISTENT_CTX else "clasica v4.3 (una por operacion)"))
    print(" Auto-update:", (RELEASE_FOLDER or "(desactivado)"))
    print(" Aprobaciones (solo lectura):", SITE_APPROVALS, "/", LIST_APPROVALS)
    print(" Sin autocierre por inactividad (se cierra al cerrar la pestana).")
    print(" Diagnostico: http://127.0.0.1:%d/api/diag" % port)
    print(" Abriendo   : http://127.0.0.1:%d" % port)
    print("=" * 62)
    threading.Timer(1.5, _open_browser_once, args=(port,)).start()
    threading.Thread(target=_watchdog, daemon=True).start()
    # v3.7: broadcast this version so other open apps can warn they are stale
    threading.Timer(3.0, publish_version_bg).start()
    app.run(port=port, debug=False, use_reloader=False, threaded=True)