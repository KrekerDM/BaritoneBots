package io.github.krekerdm.baritonebots.manager.link;

import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.Hello;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Reject;
import io.github.krekerdm.baritonebots.manager.ManagerLoop;
import io.github.krekerdm.baritonebots.manager.util.Log;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCP JSON-lines listener for bot clients (SPEC §2). One virtual reader thread and one writer thread per
 * connection; every decoded message is posted to the manager loop, except {@code result} replies, which complete
 * their query futures directly.
 */
public final class LinkServer {
    /** The first line must be {@code hello}. */
    private static final int HELLO_TIMEOUT_MS = 15_000;
    /** Bots send {@code status} at least every few seconds; silence this long means a dead peer. */
    private static final int IDLE_TIMEOUT_MS = 120_000;

    /** Callbacks, always invoked on the manager loop. */
    public interface Handler {
        /** {@code hello} arrived; the handler answers with {@code welcome} (and {@link LinkSession#accept()}) or rejects. */
        void onHello(LinkSession session, Hello hello);

        /** Any message after {@code hello} except {@code result}. */
        void onMessage(LinkSession session, Envelope message);

        void onClosed(LinkSession session);
    }

    private final ManagerLoop loop;
    private final Handler handler;
    private final Set<LinkSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    public LinkServer(ManagerLoop loop, Handler handler) {
        this.loop = loop;
        this.handler = handler;
    }

    public void start(String bind, int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bind, port));
        server = ss;
        Thread.ofPlatform().daemon().name("link-accept").start(this::acceptLoop);
        Log.info("link listening on %s:%d", bind, port);
    }

    /** The bound port (useful when started with port 0), or -1 when not listening. */
    public int port() {
        ServerSocket ss = server;
        return ss == null ? -1 : ss.getLocalPort();
    }

    private void acceptLoop() {
        ServerSocket ss = server;
        while (ss != null && !ss.isClosed()) {
            try {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(HELLO_TIMEOUT_MS);
                LinkSession session = new LinkSession(s);
                sessions.add(session);
                Thread.ofVirtual().name("link-read-" + session.id()).start(() -> readLoop(session, s));
                Thread.ofVirtual().name("link-write-" + session.id()).start(session::writerLoop);
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    Log.warn("link accept failed: %s", e.getMessage());
                }
            }
        }
    }

    private void readLoop(LinkSession session, Socket socket) {
        try {
            LineCodec codec = new LineCodec(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            String first = codec.readLine();
            if (first == null) {
                return;
            }
            Envelope hello = Envelope.decode(first);
            if (!hello.is(MessageTypes.HELLO)) {
                session.send(Envelope.of(MessageTypes.REJECT, new Reject(Reject.PROTOCOL)));
                Log.warn("link %s: first message was '%s', not hello", session.remote(), hello.t());
                Thread.sleep(200);
                return;
            }
            Hello h = hello.payload(Hello.class);
            session.botId(h.botId());
            loop.post(() -> handler.onHello(session, h));
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            while (!session.closed()) {
                String line = codec.readLine();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                Envelope e;
                try {
                    e = Envelope.decode(line);
                } catch (IllegalArgumentException bad) {
                    Log.warn("link %s: dropped malformed line: %s", session.botId(), bad.getMessage());
                    continue;
                }
                if (e.is(MessageTypes.RESULT)) {
                    session.onResult(e);
                } else {
                    loop.post(() -> handler.onMessage(session, e));
                }
            }
        } catch (SocketTimeoutException e) {
            Log.warn("link %s (%s): timed out", session.botId(), session.remote());
        } catch (IOException | RuntimeException e) {
            if (!session.closed()) {
                Log.warn("link %s (%s): %s", session.botId(), session.remote(), e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            session.close();
            sessions.remove(session);
            loop.post(() -> handler.onClosed(session));
        }
    }

    public void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        sessions.forEach(LinkSession::close);
    }
}
