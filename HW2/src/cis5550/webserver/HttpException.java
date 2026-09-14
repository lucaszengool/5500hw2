package cis5550.webserver;

/**
 * An error condition that should be reported to the client as an HTTP status
 * code instead of a normal response.
 *
 * Anything the request handler cannot fulfil (a malformed request line, an
 * unsupported method, a missing file, an uncaught exception in a route, ...)
 * is signalled by throwing one of these; the connection handler catches it in
 * a single place and turns it into a response, so the "happy path" code does
 * not have to be interrupted by error handling at every step.
 *
 * Carried over unchanged from HW1.
 *
 * @author Guang Zeng
 */
class HttpException extends Exception {

  private static final long serialVersionUID = 1L;

  /** The numeric status code, e.g. 404. */
  final int status;

  /** The reason phrase that goes on the status line, e.g. "Not Found". */
  final String reason;

  /**
   * True if the connection is no longer usable after this error. This is the
   * case for 400 Bad Request: once we have failed to parse the request we no
   * longer know where the current request ends, so we cannot safely go looking
   * for the next one on the same connection.
   */
  final boolean fatal;

  HttpException(int statusArg, String reasonArg) {
    this(statusArg, reasonArg, false, null);
  }

  HttpException(int statusArg, String reasonArg, boolean fatalArg, String detail) {
    super(statusArg + " " + reasonArg + ((detail == null) ? "" : (" (" + detail + ")")));
    status = statusArg;
    reason = reasonArg;
    fatal = fatalArg;
  }

  /**
   * 400 Bad Request. The detail is only used for the log; it is never sent to
   * the client. Always fatal for the connection; see {@link #fatal}.
   */
  static HttpException badRequest(String detail) {
    return new HttpException(400, "Bad Request", true, detail);
  }
}
