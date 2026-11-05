package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.SinSesionAlmacen;
import com.borgwarner.tbp.aplicacion.puertos.EscrituraDesactivada;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conexión con SharePoint a través de un Microsoft Edge propio (perfil
 * .edge_profile) controlado por CDP, igual que el backend 4.9.4: la aplicación
 * no maneja credenciales; usa la sesión SSO corporativa que Edge ya tiene.
 *
 * Playwright no admite varios hilos, así que TODO el tráfico pasa por un único
 * hilo trabajador con una cola de prioridades (guardar 0, interfaz 3, segundo
 * plano 7). Si la conexión se cae, se reconecta una vez.
 */
public class ConexionEdge implements AutoCloseable {

    public static final int PRIORIDAD_GUARDADO = 0;
    public static final int PRIORIDAD_INTERFAZ = 3;
    public static final int PRIORIDAD_FONDO = 7;

    private static final Logger LOG = LoggerFactory.getLogger(ConexionEdge.class);
    private static final List<String> PISTAS_CONEXION = List.of("closed", "disconnect", "not connected", "target page",
            "browser has been", "connection", "websocket", "econnrefused");

    /** Trabajo encolado. */
    private record Trabajo(int prioridad, long orden, Callable<Object> tarea, CompletableFuture<Object> resultado)
            implements Comparable<Trabajo> {
        @Override
        public int compareTo(Trabajo otro) {
            int p = Integer.compare(prioridad, otro.prioridad);
            return p != 0 ? p : Long.compare(orden, otro.orden);
        }
    }

    private final int puerto;
    private final Path carpetaPerfil;
    private final PriorityBlockingQueue<Trabajo> cola = new PriorityBlockingQueue<>();
    private final AtomicLong contador = new AtomicLong();
    private final Thread trabajador;
    private Process procesoEdge;
    private Playwright playwright;
    private Browser navegador;
    private BrowserContext contexto;

    public ConexionEdge(int puerto, Path carpetaPerfil) {
        this.puerto = puerto;
        this.carpetaPerfil = carpetaPerfil;
        this.trabajador = new Thread(this::bucle, "sp-worker");
        this.trabajador.setDaemon(true);
        this.trabajador.start();
    }

    /** Ejecuta una tarea con el contexto de Edge en el hilo trabajador y espera su resultado. */
    @SuppressWarnings("unchecked")
    public <T> T ejecutar(int prioridad, Tarea<T> tarea) {
        if (Thread.currentThread() == trabajador) {
            return tarea.ejecutar(contextoVivo()); // reentrada desde el propio trabajador
        }
        CompletableFuture<Object> futuro = new CompletableFuture<>();
        cola.add(new Trabajo(prioridad, contador.incrementAndGet(), () -> tarea.ejecutar(contextoVivo()), futuro));
        try {
            return (T) futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando a SharePoint", e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(causa);
        }
    }

    /** Tarea que recibe el contexto de Edge. */
    @FunctionalInterface
    public interface Tarea<T> {
        T ejecutar(BrowserContext contexto);
    }

    private void bucle() {
        while (true) {
            Trabajo trabajo;
            try {
                trabajo = cola.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                trabajo.resultado().complete(conReintento(trabajo.tarea()));
            } catch (Throwable t) {
                trabajo.resultado().completeExceptionally(t);
            }
        }
    }

    private Object conReintento(Callable<Object> tarea) throws Exception {
        try {
            return tarea.call();
        } catch (SinSesionAlmacen | EscrituraDesactivada e) {
            throw e; // no es un problema de conexión
        } catch (Exception e) {
            if (!pareceErrorDeConexion(e)) {
                throw e;
            }
            LOG.warn("Conexión con Edge caída ({}), reconectando", e.getMessage());
            cerrarConexion();
            return tarea.call();
        }
    }

    private static boolean pareceErrorDeConexion(Exception e) {
        String texto = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return PISTAS_CONEXION.stream().anyMatch(texto::contains);
    }

    private BrowserContext contextoVivo() {
        if (navegador != null && contexto != null && navegador.isConnected()) {
            return contexto;
        }
        cerrarConexion();
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

    private void cerrarConexion() {
        try {
            if (navegador != null) {
                navegador.close();
            }
        } catch (RuntimeException ignorada) {
            // ya estaba cerrada
        }
        navegador = null;
        contexto = null;
    }

    /** Arranca Edge sin ventana; si no responde, lo intenta con ventana (inicio de sesión SSO). */
    private void asegurarEdge() {
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
        trabajador.interrupt();
        cerrarConexion();
        if (playwright != null) {
            playwright.close();
        }
        detenerEdge();
    }
}
