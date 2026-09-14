package cis5550.webserver;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The request line and headers of one incoming HTTP request, parsed from the
 * bytes that precede the double CRLF, before the message body (if any) has
 * been read off the socket.
 *
 * This plays the same role that HW1's package-private {@code Request} class
 * did. It is renamed here because HW2 introduces a public {@link Request}
 * interface that the framework hands to applications, and the two names
 * would otherwise collide.
 *
 * @author Guang Zeng
 */
class RawRequest {

  final String method;
  final String url;
  final String protocol;

  /** Header names are lower-cased on the way in, since they are not case sensitive. */
  private final Map<String, String> headers;

  private RawRequest(String methodArg, String urlArg, String protocolArg, Map<String, String> headersArg) {
    method = methodArg;
    url = urlArg;
    protocol = protocolArg;
    headers = headersArg;
  }

  /** The full (lower-cased-key) header map, handed to RequestImpl as-is. */
  Map<String, String> headers() {
    return headers;
  }

  /** Returns the value of a header, or null. The name must be lower case. */
  String header(String name) {
    return headers.get(name);
  }

  /**
   * The number of body bytes that follow the headers.
   *
   * @throws HttpException 400 if a Content-Length header is present but is not
   *         a non-negative integer -- we would not know where the next request
   *         on this connection begins.
   */
  long contentLength() throws HttpException {
    String value = headers.get("content-length");
    if (value == null) {
      return 0;
    }
    try {
      long len = Long.parseLong(value.trim());
      if (len < 0) {
        throw HttpException.badRequest("negative Content-Length: " + value);
      }
      return len;
    } catch (NumberFormatException nfe) {
      throw HttpException.badRequest("malformed Content-Length: " + value);
    }
  }

  /** True if the client asked us to close the connection after this response. */
  boolean wantsClose() {
    String value = headers.get("connection");
    return (value != null) && value.trim().equalsIgnoreCase("close");
  }

  /**
   * The path part of the request URL, percent-decoded, with any query string or
   * fragment removed and with a leading slash guaranteed.
   *
   * An absolute URL ("GET http://host/x HTTP/1.1") is also accepted, as HTTP/1.1
   * requires; in that case the scheme and authority are stripped off.
   */
  String path() {
    String p = url;

    /* Strip scheme://authority if this is an absolute URL. */
    int schemeEnd = p.indexOf("://");
    if (schemeEnd >= 0) {
      int slash = p.indexOf('/', schemeEnd + 3);
      p = (slash < 0) ? "/" : p.substring(slash);
    }

    /* Strip the query string and the fragment; neither names a file. */
    int cut = p.indexOf('?');
    if (cut >= 0) {
      p = p.substring(0, cut);
    }
    cut = p.indexOf('#');
    if (cut >= 0) {
      p = p.substring(0, cut);
    }

    p = Http.decodePath(p);
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    return p;
  }

  /**
   * The raw (not percent/URL-decoded) query string -- the part of the URL
   * after '?' and before any '#' -- or null if the URL has none.
   */
  String rawQueryString() {
    String p = url;

    int schemeEnd = p.indexOf("://");
    if (schemeEnd >= 0) {
      int slash = p.indexOf('/', schemeEnd + 3);
      p = (slash < 0) ? "/" : p.substring(slash);
    }

    int hash = p.indexOf('#');
    if (hash >= 0) {
      p = p.substring(0, hash);
    }

    int q = p.indexOf('?');
    return (q < 0) ? null : p.substring(q + 1);
  }

  /** True if the raw URL, or its decoded form, contains "..". */
  boolean urlEscapesRoot() {
    return url.contains("..") || path().contains("..");
  }

  /**
   * Parses the block of bytes up to (and including) the double CRLF.
   *
   * The bytes are only converted to text at this point, not while they are
   * being read, so that binary bodies are never run through a character
   * decoder.
   *
   * @throws HttpException 400 if the request line is malformed, a header line
   *         has no colon, or the mandatory Host header is missing.
   */
  static RawRequest parse(byte[] headerBlock) throws HttpException {
    BufferedReader in = new BufferedReader(new InputStreamReader(
        new ByteArrayInputStream(headerBlock), StandardCharsets.ISO_8859_1));

    try {
      /* RFC 2616, Section 4.1: ignore any empty lines before the request line. */
      String requestLine = in.readLine();
      while ((requestLine != null) && requestLine.isEmpty()) {
        requestLine = in.readLine();
      }
      if (requestLine == null) {
        throw HttpException.badRequest("empty request");
      }

      /*
       * The request line is "method SP url SP protocol". Splitting on runs of
       * whitespace tolerates the extra spaces that some clients send; anything
       * that does not yield exactly three fields is a bad request, which covers
       * a missing method, URL or protocol.
       */
      String[] parts = requestLine.trim().split("\\s+");
      if (parts.length != 3) {
        throw HttpException.badRequest("malformed request line: " + requestLine);
      }

      Map<String, String> headers = new HashMap<String, String>();
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isEmpty()) {
          break;
        }
        int colon = line.indexOf(':');
        if (colon < 0) {
          throw HttpException.badRequest("header without a colon: " + line);
        }
        String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
        String value = line.substring(colon + 1).trim();
        headers.put(name, value);
      }

      /* HTTP/1.1 requires a Host header on every request (Section 14.23). */
      if (!headers.containsKey("host")) {
        throw HttpException.badRequest("no Host header");
      }

      return new RawRequest(parts[0], parts[1], parts[2], headers);
    } catch (IOException ioe) {
      /* Cannot happen: we are reading from a byte array in memory. */
      throw HttpException.badRequest("could not read headers: " + ioe);
    }
  }
}
