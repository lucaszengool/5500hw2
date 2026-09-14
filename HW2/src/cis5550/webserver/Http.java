package cis5550.webserver;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Small helpers for the parts of HTTP/1.1 that are pure data conversion: date
 * formatting, MIME type lookup and percent-decoding.
 *
 * SimpleDateFormat is *not* thread-safe and many worker threads run at once,
 * so each thread keeps its own formatters instead of sharing one. The Date:
 * header is additionally cached for a second at a time: it is the same for
 * every response written in the same second, and formatting it per request
 * turned out to be one of the more expensive things the server did.
 *
 * Carried over unchanged from HW1.
 *
 * @author Guang Zeng
 */
class Http {

  /** The date format required on the wire (RFC 2616, Section 3.3.1). */
  private static final String RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

  /** Obsolete formats we accept on input only, for robustness. */
  private static final String RFC1036 = "EEEE, dd-MMM-yy HH:mm:ss zzz";
  private static final String ASCTIME = "EEE MMM d HH:mm:ss yyyy";

  /** Per-thread output formatter, so no two threads share one instance. */
  private static final ThreadLocal<SimpleDateFormat> OUTPUT_FORMAT = new ThreadLocal<SimpleDateFormat>() {
    protected SimpleDateFormat initialValue() {
      return formatter(RFC1123);
    }
  };

  /** The Date: header value we last rendered, and the second it belongs to. */
  private static volatile String cachedDate = null;
  private static volatile long cachedDateSecond = -1;

  private Http() {
  }

  private static SimpleDateFormat formatter(String pattern) {
    SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("GMT"));
    f.setLenient(false);
    return f;
  }

  /**
   * The current time, formatted for the Date: header.
   *
   * HTTP timestamps have one-second resolution, so the string only has to be
   * rebuilt once per second. A race here is harmless: two threads may both
   * format the same second and one of them wins the write.
   */
  static String currentDate() {
    long second = System.currentTimeMillis() / 1000L;
    String cached = cachedDate;
    if ((second == cachedDateSecond) && (cached != null)) {
      return cached;
    }
    String fresh = formatDate(second * 1000L);
    cachedDate = fresh;
    cachedDateSecond = second;
    return fresh;
  }

  /**
   * Renders a timestamp in the RFC 1123 format used by the Date: and
   * Last-Modified: headers, e.g. "Sun, 06 Nov 1994 08:49:37 GMT".
   */
  static String formatDate(long millis) {
    return OUTPUT_FORMAT.get().format(new Date(millis));
  }

  /**
   * Parses a date from a request header. The assignment only requires the
   * rfc1123 format; the two obsolete formats from RFC 2616 Section 3.3.1 are
   * accepted as well, since some old clients still send them.
   *
   * @return the time in milliseconds since the epoch, or null if the value
   *         could not be parsed. A date we do not understand must be ignored
   *         (RFC 2616, Section 14.25), not treated as an error.
   */
  static Long parseDate(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    for (String pattern : new String[] { RFC1123, RFC1036, ASCTIME }) {
      try {
        return Long.valueOf(formatter(pattern).parse(v).getTime());
      } catch (ParseException pe) {
        /* Try the next format. */
      }
    }
    return null;
  }

  /**
   * Maps a file name to the Content-Type we advertise for it. The assignment
   * specifies exactly four cases; anything else is application/octet-stream.
   * Extensions are matched case-insensitively.
   */
  static String contentTypeFor(String fileName) {
    String n = fileName.toLowerCase(Locale.US);
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (n.endsWith(".txt")) {
      return "text/plain";
    }
    if (n.endsWith(".html")) {
      return "text/html";
    }
    return "application/octet-stream";
  }

  /**
   * Percent-decodes a URL path (e.g. "%20" to a space). Written by hand rather
   * than with URLDecoder because URLDecoder also turns '+' into a space, which
   * is correct for query strings but wrong for path segments.
   *
   * Invalid escapes are left as-is rather than rejected, so a file whose name
   * genuinely contains a '%' still works.
   */
  static String decodePath(String path) {
    if (path.indexOf('%') < 0) {
      return path;
    }
    StringBuilder out = new StringBuilder(path.length());
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if ((c == '%') && ((i + 2) < path.length()) && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
        bytes.write((hexValue(path.charAt(i + 1)) << 4) | hexValue(path.charAt(i + 2)));
        i += 2;
      } else {
        /* Flush any escape sequence we have collected before appending. */
        if (bytes.size() > 0) {
          out.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
          bytes.reset();
        }
        out.append(c);
      }
    }
    if (bytes.size() > 0) {
      out.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  private static boolean isHex(char c) {
    return ((c >= '0') && (c <= '9')) || ((c >= 'a') && (c <= 'f')) || ((c >= 'A') && (c <= 'F'));
  }

  private static int hexValue(char c) {
    if ((c >= '0') && (c <= '9')) {
      return c - '0';
    }
    return (Character.toLowerCase(c) - 'a') + 10;
  }
}
