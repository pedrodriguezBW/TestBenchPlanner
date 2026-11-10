package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import com.borgwarner.tbp.aplicacion.puertos.EscrituraDesactivada;
import com.borgwarner.tbp.aplicacion.puertos.SinSesionAlmacen;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Conexión con SharePoint a través de un Microsoft Edge propio (perfil
 * .edge_profile) controlado por CDP, igual que el backend 4.9.4: la aplicación
 * no maneja credenciales; usa la sesión SSO corporativa que Edge ya tiene.
 *
 * Playwright no admite varios hilos con el mismo objeto, así que cada «carril»
 * es un hilo con SU PROPIA conexión CDP al mismo Edge (mismas cookies):
 *
 *  - Carril principal: TODAS las escrituras (y lo que se pida con
 *    {@link #ejecutar}). Una sola cola con prioridades (guardar 0, interfaz 3,
 *    segundo plano 7), como antes: las escrituras siguen en orden.
 *  - Carriles de lectura (planner.sharepoint.carriles-lectura, 2 por defecto):
 *    las consultas ({@link #consultar}). Así leer el estado de los Approval ID o
 *    los recursos ya no bloquea la carga de la planificación ni un guardado.
 *    Con 0 carriles de lectura todo va por el principal (comportamiento anterior).
 *
 * Una consulta pedida por una pestaña que se ha cerrado o recargado se descarta
 * si aún no ha empezado (Cancelacion); una escritura nunca se descarta.
 * Si la conexión de un carril se cae, se reconecta una vez.
 */
public class ConexionEdge implements AutoCloseable {

    public static final int PRIORIDAD_GUARDADO = 0;
    public static final int PRIORIDAD_INTERFAZ = 3;
    /** Consultas que completan la pantalla pero no la bloquean (estados de aprobación). */
    public static final int PRIORIDAD_SECUNDARIA = 5;
    public static final int PRIORIDAD_FONDO = 7;

    public static final int CARRILES_LECTURA_POR_DEFECTO = 2;

    private static final Logger LOG = LoggerFactory.getLogger(ConexionEdge.class);
    private static final List<String> PISTAS_CONEXION = List.of("closed", "disconnect", "not connected", "target page",
            "browser has been", "connection", "websocket", "econnrefused");
    private static final long SONDEO_CANCELACION_MS = 200;

    /** Tarea que recibe el contexto de Edge. */
    @FunctionalInterface
    public interface Tarea<T> {
        T ejecutar(BrowserContext contexto);
    }

    /** Conexión CDP de un carril (sustituible en las pruebas). */
    interface Enlace {
        /** Contexto vivo; conecta (o reconecta) si hace falta. */
        BrowserContext contexto();

        /** Olvida la conexión actual: la siguiente llamada a contexto() reconecta. */
        void reconectar();

        void cerrar();
    }

    /** Trabajo encolado. */
    private record Trabajo(int prioridad, long orden, Tarea<Object> tarea, CompletableFuture<Object> resultado,
                           Cancelacion.Testigo testigo) implements Comparable<Trabajo> {
        @Override
        public int compareTo(Trabajo otro) {
            int p = Integer.compare(prioridad, otro.prioridad);
            return p != 0 ? p : Long.compare(orden, otro.orden);
        }
    }

    /** Un hilo con su conexión, que atiende una cola. */
    private final class Carril {
        private final String nombre;
        private final boolean lectura;
        private final PriorityBlockingQueue<Trabajo> cola;
        private final Thread hilo;
        private Enlace enlace;

        Carril(String nombre, boolean lectura, PriorityBlockingQueue<Trabajo> cola) {
            this.nombre = nombre;
            this.lectura = lectura;
            this.cola = cola;
            this.hilo = new Thread(this::bucle, nombre);
            this.hilo.setDaemon(true);
        }

        private Enlace enlace() {
            if (enlace == null) {
                enlace = fabrica.get();
            }
            return enlace;
        }

        private void bucle() {
            CARRIL_ACTUAL.set(this);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Trabajo trabajo;
                    try {
                        trabajo = cola.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (trabajo.resultado().isDone()) {
                        continue; // cancelado mientras esperaba
                    }
                    if (trabajo.testigo() != null && trabajo.testigo().cancelado()) {
                        descartadas.incrementAndGet();
                        trabajo.resultado().completeExceptionally(new PeticionCancelada());
                        continue;
                    }
                    Cancelacion.Testigo anterior = Cancelacion.vincular(trabajo.testigo());
                    try {
                        trabajo.resultado().complete(conReintento(trabajo.tarea()));
                    } catch (Throwable t) {
                        trabajo.resultado().completeExceptionally(t);
                    } finally {
                        Cancelacion.vincular(anterior);
                    }
                }
            } finally {
                CARRIL_ACTUAL.remove();
                if (enlace != null) {
                    try {
                        enlace.cerrar();
                    } catch (RuntimeException ignorada) {
                        // al cerrar la aplicación
                    }
                }
            }
        }

        private Object conReintento(Tarea<Object> tarea) {
            try {
                return tarea.ejecutar(enlace().contexto());
            } catch (SinSesionAlmacen | EscrituraDesactivada | PeticionCancelada e) {
                throw e; // no es un problema de conexión
            } catch (RuntimeException e) {
                if (!pareceErrorDeConexion(e)) {
                    throw e;
                }
                LOG.warn("Conexión con Edge caída en {} ({}), reconectando", nombre, e.getMessage());
                enlace().reconectar();
                return tarea.ejecutar(enlace().contexto());
            }
        }
    }

    private static final ThreadLocal<Carril> CARRIL_ACTUAL = new ThreadLocal<>();

    private final int puerto;
    private final Path carpetaPerfil;
    private final Supplier<Enlace> fabrica;
    private final AtomicLong contador = new AtomicLong();
    private final AtomicLong descartadas = new AtomicLong();
    private final PriorityBlockingQueue<Trabajo> colaPrincipal = new PriorityBlockingQueue<>();
    private final PriorityBlockingQueue<Trabajo> colaLectura = new PriorityBlockingQueue<>();
    private final Carril principal;
    private final List<Carril> lectores = new ArrayList<>();
    private final Object cerrojoEdge = new Object();
    private Process procesoEdge;

    public ConexionEdge(int puerto, Path carpetaPerfil) {
        this(puerto, carpetaPerfil, CARRILES_LECTURA_POR_DEFECTO);
    }

    public ConexionEdge(int puerto, Path carpetaPerfil, int carrilesLectura) {
        this.puerto = puerto;
        this.carpetaPerfil = carpetaPerfil;
        this.fabrica = EnlaceEdge::new;
        this.principal = new Carril("sp-principal", false, colaPrincipal);
        crearLectores(carrilesLectura);
        arrancar();
    }

    /** Para las pruebas: conexiones simuladas. */
    ConexionEdge(int carrilesLectura, Supplier<Enlace> fabrica) {
        this.puerto = 0;
        this.carpetaPerfil = null;
        this.fabrica = fabrica;
        this.principal = new Carril("sp-principal", false, colaPrincipal);
        crearLectores(carrilesLectura);
        arrancar();
    }

    private void crearLectores(int carrilesLectura) {
        for (int i = 1; i <= Math.max(0, carrilesLectura); i++) {
            lectores.add(new Carril("sp-lectura-" + i, true, colaLectura));
        }
    }

    private void arrancar() {
        principal.hilo.start();
        lectores.forEach(c -> c.hilo.start());
    }

    public int carrilesLectura() {
        return lectores.size();
    }

    /** Consultas descartadas porque su pestaña ya no existía (diagnóstico y pruebas). */
    public long consultasDescartadas() {
        return descartadas.get();
    }

    /** ¿El hilo actual es un carril de solo lectura? (ClienteSharePoint no deja escribir desde ahí). */
    public static boolean enCarrilDeLectura() {
        Carril c = CARRIL_ACTUAL.get();
        return c != null && c.lectura;
    }

    /**
     * Ejecuta una tarea en el carril PRINCIPAL y espera su resultado. Es el
     * camino de todas las escrituras; nunca se cancela.
     */
    public <T> T ejecutar(int prioridad, Tarea<T> tarea) {
        return encolar(colaPrincipal, prioridad, tarea, null);
    }

    /**
     * Ejecuta una CONSULTA (solo lectura) en un carril de lectura. Si la pestaña
     * que la pidió se cierra antes de que empiece, se descarta.
     */
    public <T> T consultar(int prioridad, Tarea<T> tarea) {
        PriorityBlockingQueue<Trabajo> cola = lectores.isEmpty() ? colaPrincipal : colaLectura;
        return encolar(cola, prioridad, tarea, Cancelacion.actual());
    }

    @SuppressWarnings("unchecked")
    private <T> T encolar(PriorityBlockingQueue<Trabajo> cola, int prioridad, Tarea<T> tarea, Cancelacion.Testigo testigo) {
        Carril actual = CARRIL_ACTUAL.get();
        if (actual != null) {
            // Reentrada desde un carril: se ejecuta en el acto con su propia conexión.
            return tarea.ejecutar(actual.enlace().contexto());
        }
        if (testigo != null && testigo.cancelado()) {
            descartadas.incrementAndGet();
            throw new PeticionCancelada();
        }
        CompletableFuture<Object> futuro = new CompletableFuture<>();
        Tarea<Object> envoltura = tarea::ejecutar;
        Trabajo trabajo = new Trabajo(prioridad, contador.incrementAndGet(), envoltura, futuro, testigo);
        cola.add(trabajo);
        try {
            return (T) (testigo == null ? futuro.get() : esperarCancelable(cola, trabajo));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando a SharePoint", e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) {
                throw re;
            }
            if (causa instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(causa);
        }
    }

    private Object esperarCancelable(PriorityBlockingQueue<Trabajo> cola, Trabajo trabajo)
            throws InterruptedException, ExecutionException {
        while (true) {
            try {
                return trabajo.resultado().get(SONDEO_CANCELACION_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException sigue) {
                if (trabajo.testigo().cancelado()) {
                    if (cola.remove(trabajo)) {
                        descartadas.incrementAndGet();
                    }
                    // Si ya había empezado, termina por su cuenta; aquí nadie lo espera.
                    trabajo.resultado().completeExceptionally(new PeticionCancelada());
                    throw new PeticionCancelada();
                }
            }
        }
    }

    private static boolean pareceErrorDeConexion(Exception e) {
        String texto = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return PISTAS_CONEXION.stream().anyMatch(texto::contains);
    }

    // ---- conexión real con Edge (una por carril) --------------------------------

    /** Playwright + CDP propios del carril; se crean en su hilo, como exige Playwright. */
    private final class EnlaceEdge implements Enlace {
        private Playwright playwright;
        private Browser navegador;
        private BrowserContext contexto;

        @Override
        public BrowserContext contexto() {
            if (navegador != null && contexto != null && navegador.isConnected()) {
                return contexto;
            }
            reconectar();
            asegurarEdge();
            if (playwright == null) {
                // No se descarga ningún navegador: se usa el Edge instalado del usuario.
                playwright = Playwright.create(new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            }
            navegador = playwright.chromium().connectOverCDP("http://127.0.0.1:" + puerto,
                    new BrowserType.ConnectOverCDPOptions().setTimeout(30000));
            contexto = navegador.contexts().isEmpty() ? navegador.newContext() : navegador.contexts().get(0);
            return contexto;
        }

        @Override
        public void reconectar() {
            try {
                if (navegador != null) {
                    navegador.close(); // con CDP solo desconecta: Edge sigue abierto para los demás carriles
                }
            } catch (RuntimeException ignorada) {
                // ya estaba cerrada
            }
            navegador = null;
            contexto = null;
        }

        @Override
        public void cerrar() {
            reconectar();
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        }
    }

    /** Arranca Edge sin ventana; si no responde, lo intenta con ventana (inicio de sesión SSO). Un carril cada vez. */
    private void asegurarEdge() {
        synchronized (cerrojoEdge) {
            if (puertoAbierto()) {
                return;
            }
            if (lanzarEdge(true) && puertoAbierto()) {
                return;
            }
            detenerEdge();
            if (lanzarEdge(false) && puertoAbierto()) {
                return;
            }
            if (!puertoAbierto()) {
                throw new IllegalStateException("No se pudo abrir Edge con depuracion remota. Avisa a IT.");
            }
        }
    }

    private boolean lanzarEdge(boolean sinVentana) {
        try {
            Files.createDirectories(carpetaPerfil);
            for (String cerrojo : List.of("SingletonLock", "SingletonCookie", "SingletonSocket", "lockfile")) {
                Files.deleteIfExists(carpetaPerfil.resolve(cerrojo));
            }
            List<String> argumentos = new ArrayList<>();
            argumentos.add(ejecutableEdge());
            if (sinVentana) {
                argumentos.add("--headless=new");
                argumentos.add("--disable-gpu");
            }
            argumentos.addAll(List.of("--remote-debugging-port=" + puerto, "--user-data-dir=" + carpetaPerfil,
                    "--no-first-run", "--no-default-browser-check", "--no-service-autorun",
                    "--disable-background-networking", "--disk-cache-size=67108864", "--media-cache-size=33554432",
                    "--disable-features=Translate,MediaRouter,OptimizationHints", "about:blank"));
            procesoEdge = new ProcessBuilder(argumentos).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            for (int i = 0; i < 60; i++) {
                if (puertoAbierto()) {
                    return true;
                }
                Thread.sleep(500);
            }
            return puertoAbierto();
        } catch (IOException e) {
            LOG.warn("No se pudo lanzar Edge: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String ejecutableEdge() {
        for (String variable : List.of("ProgramFiles(x86)", "ProgramFiles", "LocalAppData")) {
            String base = System.getenv(variable);
            if (base != null) {
                Path ruta = Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe");
                if (Files.exists(ruta)) {
                    return ruta.toString();
                }
            }
        }
        return "msedge";
    }

    private boolean puertoAbierto() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", puerto), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void detenerEdge() {
        if (procesoEdge != null && procesoEdge.isAlive()) {
            procesoEdge.destroy();
        }
        procesoEdge = null;
    }

    @Override
    public void close() {
        List<Carril> todos = new ArrayList<>(lectores);
        todos.add(principal);
        // Cada carril cierra su propia conexión al salir de su bucle (en su hilo).
        todos.forEach(c -> c.hilo.interrupt());
        for (Carril c : todos) {
            try {
                c.hilo.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (cerrojoEdge) {
            detenerEdge();
        }
    }
}
