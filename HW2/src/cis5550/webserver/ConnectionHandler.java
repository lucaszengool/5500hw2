package cis5550.webserver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cis5550.tools.Logger;

/**
 * Handles every request that arrives on a single client connection, one after
 * another, until the client goes away.
 *
 * One of these runs on a worker thread; the thread that accepts connections
 * never does any protocol work, so a slow client cannot stall the server.
 *
 * For each request, a matching route (if any) is tried first; only a request
 * that matches no route falls back to serving a static file, exactly as in
 * HW1.
 *
 * @author Guang Zeng
 */
class ConnectionHandler {

  private static final Logger logger = Logger.getLogger(ConnectionHandler.class);

  /**
   * Upper bound on the size of the request line plus headers. Without this, a
   * client that never sends the terminating double CRLF could make us buffer
   * until we run out of memory.
   */
  private static final int MAX_HEADER_BYTES = 64 * 1024;

  /** Buffer size used when copying file data onto the socket. */
  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  private final Socket sock;
  private final Server server;

  ConnectionHandler(Socket sockArg, Server serverArg) {
    sock = sockArg;
    server = serverArg;
  }

  /**
   * Reads requests from this connection and answers them until the client
   * closes its end, asks us to close, or sends something we cannot recover
   * from. The socket is always closed before this returns.
   */
  void handleConnection() {
    try {
      /*
       * Disable Nagle's algorithm: our responses are written as one buffered
       * flush, and waiting to coalesce them with the next write only adds
       * latency.
       */
      sock.setTcpNoDelay(true);
      sock.setSoTimeout(Server.SOCKET_TIMEOUT_MS);

      InputStream in = new BufferedInputStream(sock.getInputStream());
      OutputStream out = new BufferedOutputStream(sock.getOutputStream());

      while (true) {
        byte[] headerBlock;
        try {
          headerBlock = readHeaderBlock(in);
        } catch (HttpException he) {
          /* The headers were too long to be a real request. */
          sendError(out, he, false);
          out.flush();
          break;
        }

        if (headerBlock == null) {
          /* Clean end of stream: the client has finished with us. */
          break;
        }

        boolean keepGoing = handleOneRequest(headerBlock, in, out);
        out.flush();
        if (!keepGoing) {
          break;
        }
      }
    } catch (SocketTimeoutException ste) {
      logger.debug("Idle timeout on connection from " + sock.getRemoteSocketAddress());
    } catch (IOException ioe) {
      /* Broken pipes and connection resets are routine; just log and move on. */
      logger.debug("I/O error on connection from " + sock.getRemoteSocketAddress() + ": " + ioe);
    } finally {
      try {
        sock.close();
      } catch (IOException ioe) {
        /* Nothing useful left to do. */
      }
    }
  }

  /**
   * Parses and answers one request.
   *
   * @return true if the connection can be reused for another request.
   */
  private boolean handleOneRequest(byte[] headerBlock, InputStream in, OutputStream out) throws IOException {
    RawRequest req;
    byte[] bodyBytes;
    try {
      req = RawRequest.parse(headerBlock);

      /*
       * Unlike HW1, the body is not just discarded: a route may call
       * req.body()/bodyAsBytes(), and query parameters may live in the body
       * too (see handleDynamicRequest). It still has to come off the stream
       * either way, or we would read it as the start of the next request.
       */
      bodyBytes = readFully(in, req.contentLength());
    } catch (HttpException he) {
      logger.warn("Rejecting request: " + he.getMessage());
      sendError(out, he, false);
      return !he.fatal;
    }

    logger.info(req.method + " " + req.url + " from " + sock.getRemoteSocketAddress());

    boolean isHead = req.method.equals("HEAD");
    boolean keepAlive;
    try {
      keepAlive = respond(req, bodyBytes, out, isHead);
    } catch (HttpException he) {
      logger.warn("Request for " + req.url + " failed: " + he.getMessage());
      sendError(out, he, isHead);
      keepAlive = !he.fatal;
    }

    return keepAlive && !req.wantsClose();
  }

  /**
   * Tries to match the request against a registered route; falls back to
   * static file serving if none matches.
   *
   * @return true if the connection may be kept alive (subject to the
   *         Connection: close check the caller also makes); false if the
   *         response has already forced the connection closed (write() was
   *         used).
   * @throws HttpException for any status other than 200/206/304, and for a
   *         route that threw without having called write().
   */
  private boolean respond(RawRequest req, byte[] bodyBytes, OutputStream out, boolean isHead)
      throws HttpException, IOException {

    /* We speak exactly one version of the protocol. */
    if (!req.protocol.equals("HTTP/1.1")) {
      throw new HttpException(505, "HTTP Version Not Supported");
    }

    String path = req.path();
    String hostHeader = req.header("host");
    Server.MatchedRoute matched = server.findRoute(hostHeader, req.method, path);
    if (matched != null) {
      return handleDynamicRequest(req, bodyBytes, path, matched, out);
    }

    respondStatic(req, hostHeader, out, isHead);
    return true;
  }

  /**
   * Invokes a matched route (with its before()/after() filters, extra
   * credit) and sends whatever it produces.
   *
   * @return true if the connection may be kept alive; false if write() was
   *         used (the response is framed with Connection: close and cannot
   *         be reused).
   */
  private boolean handleDynamicRequest(RawRequest req, byte[] bodyBytes, String path,
      Server.MatchedRoute matched, OutputStream out) throws HttpException, IOException {

    Map<String, String> queryParams = new HashMap<String, String>();
    parseQueryParams(req.rawQueryString(), queryParams);

    String contentType = req.header("content-type");
    if ((contentType != null)
        && contentType.toLowerCase(Locale.US).startsWith("application/x-www-form-urlencoded")) {
      parseQueryParams(new String(bodyBytes, StandardCharsets.UTF_8), queryParams);
    }

    InetSocketAddress remoteAddr = (InetSocketAddress) sock.getRemoteSocketAddress();
    RequestImpl request = new RequestImpl(req.method, path, req.protocol, req.headers(),
        queryParams, matched.params, remoteAddr, bodyBytes, server);
    ResponseImpl response = new ResponseImpl(out);

    /* EXTRA CREDIT: before() filters may halt() the request, skipping the route entirely. */
    for (Route filter : server.beforeFilters()) {
      try {
        filter.handle(request, response);
      } catch (Exception e) {
        logger.warn("A before() filter threw an exception", e);
        if (response.isWriteCalled()) {
          return false;
        }
        throw new HttpException(500, "Internal Server Error");
      }
      if (response.isHalted()) {
        response.sendNormal(null);
        return true;
      }
      if (response.isWriteCalled()) {
        return false;
      }
    }

    Object result;
    try {
      result = matched.route.handle(request, response);
    } catch (Exception e) {
      logger.warn("Route for " + req.url + " threw an exception", e);
      if (response.isWriteCalled()) {
        /* The response has already been (partially) sent; nothing more we can do. */
        return false;
      }
      throw new HttpException(500, "Internal Server Error");
    }

    if (response.isWriteCalled()) {
      return false;
    }

    /* EXTRA CREDIT: after() filters run once the route has returned, before the response is sent. */
    for (Route filter : server.afterFilters()) {
      try {
        filter.handle(request, response);
      } catch (Exception e) {
        logger.warn("An after() filter threw an exception", e);
        if (response.isWriteCalled()) {
          return false;
        }
        throw new HttpException(500, "Internal Server Error");
      }
      if (response.isWriteCalled()) {
        return false;
      }
    }

    response.sendNormal(result);
    return true;
  }

  /**
   * Parses a string of URL-encoded key=value pairs separated by '&' (as found
   * either after the '?' in a URL, or in a form-urlencoded body) into
   * {@code out}, URL-decoding both keys and values. If a key appears more
   * than once, its values are combined with commas, per the assignment.
   */
  private static void parseQueryParams(String raw, Map<String, String> out) {
    if ((raw == null) || raw.isEmpty()) {
      return;
    }
    for (String pair : raw.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = (eq < 0) ? pair : pair.substring(0, eq);
      String rawValue = (eq < 0) ? "" : pair.substring(eq + 1);
      String key;
      String value;
      try {
        key = URLDecoder.decode(rawKey, "UTF-8");
        value = URLDecoder.decode(rawValue, "UTF-8");
      } catch (UnsupportedEncodingException uee) {
        /* UTF-8 is always supported; this can never actually happen. */
        continue;
      }
      String existing = out.get(key);
      out.put(key, (existing == null) ? value : (existing + "," + value));
    }
  }

  /**
   * Validates the request and, if it names a file we can serve, writes it back.
   * Unchanged from HW1, except that the static-file root now comes from the
   * Server (it may be set, or change, after the server has already started).
   *
   * @throws HttpException for any status other than 200/206/304.
   */
  private void respondStatic(RawRequest req, String hostHeader, OutputStream out, boolean isHead)
      throws HttpException, IOException {

    String m = req.method;
    if (!m.equals("GET") && !m.equals("HEAD") && !m.equals("POST") && !m.equals("PUT")) {
      throw new HttpException(501, "Not Implemented");
    }
    if (m.equals("POST") || m.equals("PUT")) {
      throw new HttpException(405, "Not Allowed");
    }

    File root = server.staticFilesRootFor(hostHeader);
    if (root == null) {
      /* staticFiles.location() was never called: nothing to serve. */
      throw new HttpException(404, "Not Found");
    }

    /*
     * Refuse anything containing "..", before touching the file system, so a
     * client cannot walk out of the directory it was given. Both the raw and
     * the percent-decoded form are checked, so "%2e%2e" does not slip past.
     */
    if (req.urlEscapesRoot()) {
      throw new HttpException(403, "Forbidden");
    }

    String path = req.path();
    File file = new File(root, path);
    logger.debug("Mapping " + path + " to " + file.getPath());

    /*
     * A directory "exists", but there is nothing sensible to send for it, so it
     * is treated as not found rather than as a readable file.
     */
    if (!file.exists() || !file.isFile()) {
      throw new HttpException(404, "Not Found");
    }
    if (!file.canRead()) {
      throw new HttpException(403, "Forbidden");
    }

    long lastModified = file.lastModified();

    /*
     * Conditional request (extra credit). HTTP timestamps have one-second
     * resolution, so the file's mtime is truncated before comparing; otherwise
     * a file saved mid-second would look newer than a date we ourselves sent.
     */
    Long since = Http.parseDate(req.header("if-modified-since"));
    if ((since != null) && (((lastModified / 1000L) * 1000L) <= since.longValue())) {
      List<String> headers = new ArrayList<String>();
      headers.add("Content-Length: 0");
      headers.add("Last-Modified: " + Http.formatDate(lastModified));
      sendHeaders(out, 304, "Not Modified", headers);
      return;
    }

    long fileLength = file.length();
    long first = 0;
    long last = fileLength - 1;
    boolean partial = false;

    /*
     * Range request (extra credit). A range we cannot parse, or one that does
     * not fit the file, is ignored and the whole file is sent instead -- RFC
     * 7233 allows that, and it saves us from having to implement 416.
     */
    long[] range = parseRange(req.header("range"), fileLength);
    if (range != null) {
      first = range[0];
      last = range[1];
      partial = true;
    }

    long length = (fileLength == 0) ? 0 : ((last - first) + 1);

    List<String> headers = new ArrayList<String>();
    headers.add("Content-Type: " + Http.contentTypeFor(file.getName()));
    headers.add("Content-Length: " + length);
    headers.add("Last-Modified: " + Http.formatDate(lastModified));
    headers.add("Accept-Ranges: bytes");
    if (partial) {
      headers.add("Content-Range: bytes " + first + "-" + last + "/" + fileLength);
    }

    sendHeaders(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", headers);

    /*
     * A HEAD response carries exactly the headers a GET would have produced,
     * but no body at all.
     */
    if (!isHead && (length > 0)) {
      sendFile(out, file, first, length);
    }
  }

  /**
   * Parses a Range header of the form "bytes=X-Y", "bytes=X-" or "bytes=-N".
   *
   * @return {first, last} byte offsets, both inclusive, or null if the header is
   *         absent, unparseable, or does not name a satisfiable range.
   */
  private long[] parseRange(String value, long fileLength) {
    if ((value == null) || (fileLength <= 0)) {
      return null;
    }

    String v = value.trim();
    if (!v.toLowerCase(Locale.US).startsWith("bytes=")) {
      return null;
    }
    v = v.substring("bytes=".length()).trim();

    /* Only a single range needs to be supported. */
    if (v.indexOf(',') >= 0) {
      return null;
    }

    int dash = v.indexOf('-');
    if (dash < 0) {
      return null;
    }

    String fromText = v.substring(0, dash).trim();
    String toText = v.substring(dash + 1).trim();

    try {
      long first;
      long last;
      if (fromText.isEmpty()) {
        /* "-N": the last N bytes of the file. */
        if (toText.isEmpty()) {
          return null;
        }
        long suffix = Long.parseLong(toText);
        if (suffix <= 0) {
          return null;
        }
        first = Math.max(0, fileLength - suffix);
        last = fileLength - 1;
      } else {
        first = Long.parseLong(fromText);
        last = toText.isEmpty() ? (fileLength - 1) : Long.parseLong(toText);
      }

      if ((first < 0) || (first >= fileLength) || (last < first)) {
        return null;
      }
      if (last >= fileLength) {
        last = fileLength - 1;
      }
      return new long[] { first, last };
    } catch (NumberFormatException nfe) {
      return null;
    }
  }

  /**
   * Reads bytes until the double CRLF that ends the headers.
   *
   * Requests may arrive in any granularity -- possibly one byte at a time -- so
   * this keeps reading rather than assuming a whole request shows up in one
   * chunk. The underlying stream is buffered, so reading a byte at a time is
   * cheap and lets us stop exactly at the end of the headers without consuming
   * any of the body.
   *
   * @return the header bytes, or null if the client closed the connection
   *         cleanly before starting a new request.
   * @throws HttpException 400 if the headers exceed {@link #MAX_HEADER_BYTES}.
   */
  private byte[] readHeaderBlock(InputStream in) throws IOException, HttpException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /* How many bytes of the CR LF CR LF terminator we have matched so far. */
    int matched = 0;

    while (matched < 4) {
      int b = in.read();
      if (b < 0) {
        if (buffer.size() == 0) {
          return null;
        }
        /* Truncated request: nothing to answer, so just drop the connection. */
        logger.debug("Connection closed in the middle of a request");
        return null;
      }

      buffer.write(b);
      if (buffer.size() > MAX_HEADER_BYTES) {
        throw HttpException.badRequest("headers longer than " + MAX_HEADER_BYTES + " bytes");
      }

      boolean expectCR = ((matched == 0) || (matched == 2));
      if (expectCR ? (b == '\r') : (b == '\n')) {
        matched++;
      } else {
        /*
         * A stray CR restarts the match at one, not zero, so that "\r\r\n\r\n"
         * is still recognised.
         */
        matched = (b == '\r') ? 1 : 0;
      }
    }

    return buffer.toByteArray();
  }

  /** Reads and returns exactly n bytes, however slowly they arrive. */
  private byte[] readFully(InputStream in, long n) throws IOException {
    if (n <= 0) {
      return new byte[0];
    }
    byte[] result = new byte[(int) n];
    int offset = 0;
    while (offset < result.length) {
      int got = in.read(result, offset, result.length - offset);
      if (got < 0) {
        throw new IOException("connection closed with " + (result.length - offset) + " body bytes outstanding");
      }
      offset += got;
    }
    return result;
  }

  /** Writes a status line, the given headers, and the blank line that ends them. */
  private void sendHeaders(OutputStream out, int status, String reason, List<String> headers) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
    sb.append("Server: ").append(Server.SERVER_NAME).append("\r\n");
    sb.append("Date: ").append(Http.currentDate()).append("\r\n");
    for (String h : headers) {
      sb.append(h).append("\r\n");
    }
    sb.append("\r\n");

    /*
     * Written straight to the OutputStream as bytes rather than through a
     * Writer, so there is no second layer of buffering that could leave the
     * headers half-sent when the file data follows.
     */
    out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
  }

  /**
   * Sends an error response, with a short plain-text body repeating the status
   * so that a browser has something to display. Also used for the 500 that a
   * route's uncaught exception produces, so its body is always this generic
   * text, never whatever the route may have set via body()/bodyAsBytes().
   */
  private void sendError(OutputStream out, HttpException he, boolean isHead) throws IOException {
    byte[] body = (he.status + " " + he.reason + "\r\n").getBytes(StandardCharsets.UTF_8);

    List<String> headers = new ArrayList<String>();
    headers.add("Content-Type: text/plain");
    headers.add("Content-Length: " + body.length);
    if (he.fatal) {
      headers.add("Connection: close");
    }

    sendHeaders(out, he.status, he.reason, headers);
    if (!isHead) {
      out.write(body);
    }
  }

  /** Copies length bytes of the file, starting at offset first, onto the socket. */
  private void sendFile(OutputStream out, File file, long first, long length) throws IOException {
    RandomAccessFile raf = new RandomAccessFile(file, "r");
    try {
      raf.seek(first);
      byte[] buffer = new byte[COPY_BUFFER_BYTES];
      long remaining = length;
      while (remaining > 0) {
        int want = (int) Math.min(buffer.length, remaining);
        int got = raf.read(buffer, 0, want);
        if (got < 0) {
          /*
           * The file shrank while we were sending it. We have already promised a
           * Content-Length, so the only honest thing left is to drop the
           * connection rather than send a short response.
           */
          throw new IOException("file " + file.getPath() + " shrank while it was being sent");
        }
        out.write(buffer, 0, got);
        remaining -= got;
      }
    } finally {
      raf.close();
    }
  }
}
