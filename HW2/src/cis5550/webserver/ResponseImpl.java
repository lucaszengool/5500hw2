package cis5550.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Response object handed to route handlers.
 *
 * Everything a route sets (status, headers, body) is buffered here in memory
 * until either 1) the route returns normally, in which case
 * {@link ConnectionHandler} calls {@link #sendNormal} to decide what to send
 * based on the route's return value and whatever was buffered, or 2) the
 * application calls {@link #write}, which immediately commits the status
 * line and headers and switches the response into a raw streaming mode for
 * the remainder of the request -- after which every other setter becomes a
 * no-op, per the Response interface's documentation.
 *
 * @author Guang Zeng
 */
class ResponseImpl implements Response {

  private final OutputStream out;

  private int statusCode = 200;
  private String reasonPhrase = "OK";
  private String contentType = "text/html";

  /** Headers set with header(), in the order they were added; duplicates are kept as separate lines. */
  private final List<String[]> headers = new ArrayList<String[]>();

  /** The most recent value passed to body()/bodyAsBytes(), or null if neither has been called. */
  private byte[] pendingBody = null;

  private boolean writeCalled = false;

  /** EXTRA CREDIT (filters): set by halt(), to short-circuit a before() filter's request. */
  private boolean halted = false;

  ResponseImpl(OutputStream outArg) {
    out = outArg;
  }

  public void body(String bodyArg) {
    if (writeCalled) {
      return;
    }
    pendingBody = (bodyArg == null) ? new byte[0] : bodyArg.getBytes(StandardCharsets.UTF_8);
  }

  public void bodyAsBytes(byte[] bodyArg) {
    if (writeCalled) {
      return;
    }
    pendingBody = bodyArg;
  }

  public void header(String name, String value) {
    if (writeCalled) {
      return;
    }
    headers.add(new String[] { name, value });
  }

  public void type(String contentTypeArg) {
    if (writeCalled) {
      return;
    }
    contentType = contentTypeArg;
  }

  public void status(int statusCodeArg, String reasonPhraseArg) {
    if (writeCalled) {
      return;
    }
    statusCode = statusCodeArg;
    reasonPhrase = reasonPhraseArg;
  }

  public void write(byte[] b) throws Exception {
    if (!writeCalled) {
      writeCalled = true;
      sendStreamingHeaders();
    }
    out.write(b);
  }

  /**
   * EXTRA CREDIT: redirects the client to {@code url} with the given
   * response code (301, 302, 303, 307 or 308). Implemented simply as the
   * status code plus a Location: header, which is all a redirect is on the
   * wire; the usual status()/header() precedence and write()-lockout rules
   * apply exactly as they would if the route had called those directly.
   */
  public void redirect(String url, int responseCode) {
    status(responseCode, reasonPhraseForRedirect(responseCode));
    header("Location", url);
  }

  private static String reasonPhraseForRedirect(int code) {
    switch (code) {
      case 301:
        return "Moved Permanently";
      case 302:
        return "Found";
      case 303:
        return "See Other";
      case 307:
        return "Temporary Redirect";
      case 308:
        return "Permanent Redirect";
      default:
        return "Redirect";
    }
  }

  /**
   * EXTRA CREDIT (filters): called from a before() filter to end the request
   * immediately with the given status code and reason phrase, skipping the
   * route (and any later filters). {@link ConnectionHandler} checks
   * {@link #isHalted} after every before() filter and, if set, sends the
   * response right away instead of continuing.
   */
  public void halt(int statusCodeArg, String reasonPhraseArg) {
    halted = true;
    statusCode = statusCodeArg;
    reasonPhrase = reasonPhraseArg;
  }

  /** Whether write() has been called on this response yet. */
  boolean isWriteCalled() {
    return writeCalled;
  }

  /** EXTRA CREDIT (filters): whether halt() has been called on this response yet. */
  boolean isHalted() {
    return halted;
  }

  /**
   * Sends the final response for a route that returned normally without ever
   * calling write(). Follows the precedence documented on {@link Response}:
   * a non-null return value wins, otherwise whatever body()/bodyAsBytes() set
   * most recently, otherwise no body at all.
   */
  void sendNormal(Object routeReturnValue) throws IOException {
    byte[] body;
    if (routeReturnValue != null) {
      body = routeReturnValue.toString().getBytes(StandardCharsets.UTF_8);
    } else if (pendingBody != null) {
      body = pendingBody;
    } else {
      body = new byte[0];
    }

    List<String> headerLines = new ArrayList<String>();
    headerLines.add("Content-Type: " + contentType);
    for (String[] h : headers) {
      headerLines.add(h[0] + ": " + h[1]);
    }
    headerLines.add("Content-Length: " + body.length);

    writeStatusAndHeaders(statusCode, reasonPhrase, headerLines);
    if (body.length > 0) {
      out.write(body);
    }
  }

  /**
   * Commits the status line and headers the first time write() is called.
   * Per the assignment: adds "Connection: close" and omits Content-Length,
   * since the total length isn't known in advance.
   */
  private void sendStreamingHeaders() throws IOException {
    List<String> headerLines = new ArrayList<String>();
    headerLines.add("Content-Type: " + contentType);
    for (String[] h : headers) {
      headerLines.add(h[0] + ": " + h[1]);
    }
    headerLines.add("Connection: close");

    writeStatusAndHeaders(statusCode, reasonPhrase, headerLines);
  }

  private void writeStatusAndHeaders(int status, String reason, List<String> headerLines) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
    sb.append("Server: ").append(Server.SERVER_NAME).append("\r\n");
    sb.append("Date: ").append(Http.currentDate()).append("\r\n");
    for (String h : headerLines) {
      sb.append(h).append("\r\n");
    }
    sb.append("\r\n");
    out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
  }
}
