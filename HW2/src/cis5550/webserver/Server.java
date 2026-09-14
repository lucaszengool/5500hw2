package cis5550.webserver;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import cis5550.tools.Logger;

/**
 * CIS 5550 HW2: a dynamic HTTP/1.1 web server, in the style of the Spark
 * Framework.
 *
 * An application configures this class entirely through its static methods
 * ({@link #port}, {@link #get}, {@link #put}, {@link #post},
 * {@link staticFiles#location}, plus the extra-credit {@link #host},
 * {@link #before} and {@link #after}) and never constructs a Server itself --
 * there is exactly one, held in {@link #instanceField}, created lazily the
 * first time any of those methods is called. The server itself starts
 * listening the first time {@link #get}, {@link #put}, {@link #post} or
 * {@link staticFiles#location} is called (not on {@link #port} or
 * {@link #host}, since the assignment guarantees port() is called first if
 * it is called at all, and host() only ever redirects where later
 * registrations go), on a background thread so that the application's own
 * main() is free to return.
 *
 * Requests are matched against the registered routes first; a request that
 * matches no route falls back to static file serving, exactly as HW1 did.
 * Both the route table and the static-file root are scoped per virtual host
 * (see {@link #host}); a request whose Host: header does not match any
 * virtual host uses whatever was registered before the first host() call.
 *
 * @author Guang Zeng
 */
public class Server {

  private static final Logger logger = Logger.getLogger(Server.class);

  /** The value we report in the Server: response header. */
  static final String SERVER_NAME = "GuangZeng/2.0";

  /** Size of the worker pool. */
  public static final int NUM_WORKERS = 100;

  /**
   * How long a connection may sit without sending anything before we reclaim
   * its worker. Without this, clients that vanish without closing their sockets
   * would tie up workers indefinitely.
   */
  static final int SOCKET_TIMEOUT_MS = 30 * 1000;

  /** The single Server instance, created lazily on first use. */
  private static Server instanceField = null;

  /** Whether the background accept thread has been started yet. */
  private static boolean started = false;

  /** The port to listen on; defaults to 80, per the assignment. */
  private int port = 80;

  /** Routes and static-file root registered before the first host() call. */
  private final HostConfig defaultHost = new HostConfig();

  /** One entry per virtual host named in a host() call so far. */
  private final Map<String, HostConfig> virtualHosts = new ConcurrentHashMap<String, HostConfig>();

  /**
   * Where get()/put()/post()/staticFiles.location() currently add to: the
   * default host until host() is called, and then whichever host was named
   * most recently.
   */
  private volatile HostConfig currentHost = defaultHost;

  /** Filters run (in registration order) before a matched route is invoked; extra credit. */
  private final List<Route> beforeFilters = new CopyOnWriteArrayList<Route>();

  /** Filters run (in registration order) after a matched route returns; extra credit. */
  private final List<Route> afterFilters = new CopyOnWriteArrayList<Route>();

  private Server() {
  }

  private static synchronized Server instance() {
    if (instanceField == null) {
      instanceField = new Server();
    }
    return instanceField;
  }

  /** Sets the port the server will listen on. Must be called before any other API method, if at all. */
  public static void port(int portArg) {
    instance().port = portArg;
  }

  public static void get(String path, Route route) {
    addRouteAndStart("GET", path, route);
  }

  public static void put(String path, Route route) {
    addRouteAndStart("PUT", path, route);
  }

  public static void post(String path, Route route) {
    addRouteAndStart("POST", path, route);
  }

  private static void addRouteAndStart(String method, String path, Route route) {
    instance().currentHost.routes.add(new RouteEntry(method, path, route));
    ensureStarted();
  }

  /**
   * EXTRA CREDIT: switches which virtual host subsequent get()/put()/post()/
   * staticFiles.location() calls apply to. Everything registered before the
   * first call to host() forms the default configuration, used for any
   * request whose Host: header (port ignored) does not match a host named in
   * some host() call.
   */
  public static void host(String h) {
    Server s = instance();
    HostConfig cfg = s.virtualHosts.get(h);
    if (cfg == null) {
      cfg = new HostConfig();
      s.virtualHosts.put(h, cfg);
    }
    s.currentHost = cfg;
  }

  /**
   * EXTRA CREDIT: registers a filter to run before a matched route is
   * invoked, for every request regardless of which host it matched. Its
   * return value is ignored; it can end the request early by calling
   * {@link Response#halt}.
   */
  public static void before(Route filter) {
    instance().beforeFilters.add(filter);
    ensureStarted();
  }

  /**
   * EXTRA CREDIT: registers a filter to run after a matched route returns
   * (but before the response is sent), for every request regardless of which
   * host it matched. Its return value is ignored.
   */
  public static void after(Route filter) {
    instance().afterFilters.add(filter);
    ensureStarted();
  }

  /** Namespace for the one static-file-serving configuration method, per the assignment's API. */
  public static class staticFiles {
    private staticFiles() {
    }

    public static void location(String path) {
      instance().currentHost.staticFilesRoot = new File(path);
      ensureStarted();
    }
  }

  /** Starts the background accept-loop thread, the first time this is called. */
  private static synchronized void ensureStarted() {
    final Server s = instance();
    if (started) {
      return;
    }
    started = true;

    /*
     * Not a daemon thread: the application's main() is expected to configure
     * routes and return, and the JVM must stay alive to keep serving requests
     * after it does.
     */
    Thread t = new Thread("cis5550-webserver-accept") {
      public void run() {
        s.runAcceptLoop();
      }
    };
    t.start();
  }

  /** Every before() filter registered so far, in registration order. */
  List<Route> beforeFilters() {
    return beforeFilters;
  }

  /** Every after() filter registered so far, in registration order. */
  List<Route> afterFilters() {
    return afterFilters;
  }

  /**
   * The directory to serve static files from for a request with the given
   * Host: header value, or null if no location() has been configured for
   * that host (or for the default configuration, if the host matches none).
   */
  File staticFilesRootFor(String hostHeader) {
    return resolveHost(hostHeader).staticFilesRoot;
  }

  /**
   * Looks for a route registered under the given Host: header's
   * configuration whose method matches exactly and whose path pattern
   * matches the given path.
   *
   * @return the matching route together with any named path parameters it
   *         extracted, or null if no route matches. If more than one route
   *         matches, the first one registered is used.
   */
  MatchedRoute findRoute(String hostHeader, String method, String path) {
    HostConfig cfg = resolveHost(hostHeader);
    for (RouteEntry entry : cfg.routes) {
      if (!entry.method.equals(method)) {
        continue;
      }
      Map<String, String> params = matchPath(entry.pathPattern, path);
      if (params != null) {
        return new MatchedRoute(entry.route, params);
      }
    }
    return null;
  }

  /**
   * Picks the HostConfig a request should use: the virtual host whose name
   * (registered via {@link #host}) matches the request's Host: header,
   * ignoring any port suffix and matching case-sensitively as the handout's
   * example does; or the default configuration if none matches.
   */
  private HostConfig resolveHost(String hostHeader) {
    String name = hostNameOnly(hostHeader);
    HostConfig cfg = (name == null) ? null : virtualHosts.get(name);
    return (cfg != null) ? cfg : defaultHost;
  }

  /** Strips an optional ":port" suffix off a Host: header value. */
  private static String hostNameOnly(String hostHeader) {
    if (hostHeader == null) {
      return null;
    }
    String h = hostHeader.trim();
    if (h.startsWith("[")) {
      /* An IPv6 literal, e.g. "[::1]:8080"; keep the bracketed address as-is. */
      int end = h.indexOf(']');
      return (end < 0) ? h : h.substring(0, end + 1);
    }
    int colon = h.lastIndexOf(':');
    return (colon < 0) ? h : h.substring(0, colon);
  }

  /**
   * Checks whether {@code path} matches {@code pattern}, where a pattern
   * segment of the form ":name" matches any single path segment and binds it
   * to "name".
   *
   * Both strings are split on '/'; per the assignment, a match requires an
   * equal number of pieces, with each pair of pieces either identical or the
   * pattern piece being a named parameter.
   *
   * @return the named parameters extracted from the match, or null if the
   *         path does not match the pattern at all.
   */
  static Map<String, String> matchPath(String pattern, String path) {
    String[] patternParts = pattern.split("/", -1);
    String[] pathParts = path.split("/", -1);
    if (patternParts.length != pathParts.length) {
      return null;
    }

    Map<String, String> params = new HashMap<String, String>();
    for (int i = 0; i < patternParts.length; i++) {
      String patternPart = patternParts[i];
      String pathPart = pathParts[i];
      if (patternPart.startsWith(":") && (patternPart.length() > 1)) {
        params.put(patternPart.substring(1), pathPart);
      } else if (!patternPart.equals(pathPart)) {
        return null;
      }
    }
    return params;
  }

  /**
   * Everything that is scoped per virtual host: the route table and the
   * static-file root. One of these is the "default" configuration (used
   * before host() is ever called, and for any host that matches no host()
   * call); one more is created per distinct name passed to host().
   */
  private static final class HostConfig {
    final List<RouteEntry> routes = new CopyOnWriteArrayList<RouteEntry>();
    volatile File staticFilesRoot = null;
  }

  /** One registered route: which method and path pattern it answers, and its handler. */
  private static final class RouteEntry {
    final String method;
    final String pathPattern;
    final Route route;

    RouteEntry(String methodArg, String pathPatternArg, Route routeArg) {
      method = methodArg;
      pathPattern = pathPatternArg;
      route = routeArg;
    }
  }

  /** A route together with the named path parameters extracted for one specific request. */
  static final class MatchedRoute {
    final Route route;
    final Map<String, String> params;

    MatchedRoute(Route routeArg, Map<String, String> paramsArg) {
      route = routeArg;
      params = paramsArg;
    }
  }

  /** Accepts connections forever and hands each one to the worker pool. */
  private void runAcceptLoop() {
    ThreadPool pool = new ThreadPool(NUM_WORKERS, this);

    ServerSocket serverSocket = null;
    try {
      serverSocket = new ServerSocket(port);
      logger.info("Listening on port " + port);

      while (true) {
        try {
          Socket sock = serverSocket.accept();
          logger.debug("Incoming connection from " + sock.getRemoteSocketAddress());
          pool.submit(sock);
        } catch (IOException ioe) {
          /*
           * One failed accept (e.g. the client gave up between the handshake and
           * our call) must not bring the whole server down; log it and keep
           * listening.
           */
          logger.error("Could not accept a connection", ioe);
        }
      }
    } catch (IOException ioe) {
      logger.fatal("Could not listen on port " + port, ioe);
      System.err.println("Could not listen on port " + port + ": " + ioe.getMessage());
    } finally {
      if (serverSocket != null) {
        try {
          serverSocket.close();
        } catch (IOException ioe) {
          /* We are on our way out anyway. */
        }
      }
    }
  }
}
