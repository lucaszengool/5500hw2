package cis5550.webserver;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import cis5550.tools.Logger;

/**
 * A fixed pool of worker threads that handle accepted connections.
 *
 * The thread that runs the accept loop only ever calls {@link #submit}, which
 * hands the socket to a BlockingQueue and returns immediately. A fixed number of
 * workers block on that queue, so an idle server has no runnable threads at all
 * and consumes no CPU -- there is no polling anywhere.
 *
 * Carried over from HW1; the only change is that each worker's
 * {@link ConnectionHandler} now holds a reference to the {@link Server}
 * itself (so it can look up routes and the current static-file root) instead
 * of a fixed {@code File}.
 *
 * @author Guang Zeng
 */
class ThreadPool {

  private static final Logger logger = Logger.getLogger(ThreadPool.class);

  /**
   * The queue of connections waiting for a worker. It is unbounded, so the
   * accept loop never blocks; in practice it stays near-empty, because a
   * connection is only queued for as long as every worker is busy.
   */
  private final BlockingQueue<Socket> pending = new LinkedBlockingQueue<Socket>();

  private final Server server;

  ThreadPool(int numWorkers, Server serverArg) {
    server = serverArg;
    for (int i = 0; i < numWorkers; i++) {
      Thread t = new Worker("Worker " + i);
      t.setDaemon(true);
      t.start();
    }
    logger.info("Started " + numWorkers + " worker threads");
  }

  /** Hands a newly accepted connection to the next free worker. */
  void submit(Socket sock) {
    /*
     * put() on an unbounded queue cannot actually block, but it is declared to
     * throw InterruptedException, and dropping the connection is the only
     * sensible response if we are ever interrupted here.
     */
    try {
      pending.put(sock);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      closeQuietly(sock);
    }
  }

  private static void closeQuietly(Socket sock) {
    try {
      sock.close();
    } catch (IOException ioe) {
      /* Nothing useful left to do. */
    }
  }

  /**
   * A worker takes one connection at a time off the queue and stays with it
   * until the client is done, then comes back for the next one.
   */
  private class Worker extends Thread {

    Worker(String name) {
      super(name);
    }

    public void run() {
      while (true) {
        Socket sock;
        try {
          sock = pending.take();
        } catch (InterruptedException ie) {
          logger.info(getName() + " interrupted; exiting");
          return;
        }

        try {
          new ConnectionHandler(sock, server).handleConnection();
        } catch (RuntimeException re) {
          /*
           * A bug in the handler must not take a worker out of the pool
           * permanently, or the server would slowly grind to a halt.
           */
          logger.error("Uncaught exception while handling a connection", re);
          closeQuietly(sock);
        }
      }
    }
  }
}
