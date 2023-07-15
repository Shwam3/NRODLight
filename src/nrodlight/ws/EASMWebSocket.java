package nrodlight.ws;

import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.db.DBHandler;
import nrodlight.db.Queries;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class EASMWebSocket extends WebSocketServer
{
    private final AtomicBoolean serverClosed = new AtomicBoolean(false);
    private static JSONObject delayData = null;
    private ScheduledFuture<?> certExpirationTask;
    private EASMWebSocketFactory wsf;

    public EASMWebSocket()
    {
        super(new InetSocketAddress(NRODLight.config.optInt("WSPort",8443)),
                4, // Number of worker threads
                Collections.singletonList(new EASMDraft_6455()));
        super.setReuseAddr(true);

        wsf = new EASMWebSocketFactory();
        setWebSocketFactory(wsf);

        reloadSSLContext();
    }

    private void reloadSSLContext()
    {
        if (certExpirationTask != null)
            certExpirationTask.cancel(false);

        Pair<SSLContext, Date> sslData = getSSLContextFromLetsEncrypt();
        SSLContext sslContext;
        if (sslData != null)
        {
            sslContext = sslData.getLeft();
            List<String> ciphers = Arrays.asList(
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            );
            List<String> protocols = Arrays.asList("TLSv1.2", "TLSv1.3");

            SSLEngine engine = sslContext.createSSLEngine();

            String[] ciphersAvailable = ciphers.stream().filter(Arrays.asList(engine.getEnabledCipherSuites())::contains).toArray(String[]::new);
            String[] protocolsAvailable = protocols.stream().filter(Arrays.asList(engine.getEnabledProtocols())::contains).toArray(String[]::new);

            SSLParameters sslParameters = new SSLParameters(ciphersAvailable, protocolsAvailable);
            sslParameters.setUseCipherSuitesOrder(true);

            wsf.updateSSLDetails(sslContext, sslParameters);

            long delay = sslData.getRight().getTime() - System.currentTimeMillis() - TimeUnit.HOURS.convert(12, TimeUnit.MILLISECONDS);
            if (delay <= 0)
                printWebSocket("WSS cert very close to/already expired", true);
            certExpirationTask = NRODLight.getExecutor().schedule(this::reloadSSLContext, delay, TimeUnit.MILLISECONDS);

            printWebSocket(String.format("Created new SSL Context with %s protocol(s) and %s as ciphers", String.join(", ", protocolsAvailable), String.join(", ", ciphersAvailable)), false, true);
        }
        else
        {
            printWebSocket("Unable to create SSL Context", true);
            certExpirationTask = null;
        }
    }

    private Pair<SSLContext, Date> getSSLContextFromLetsEncrypt()
    {
        SSLContext context;
        Date expiry;
        File certDir = new File(NRODLight.EASM_STORAGE_DIR, "certs");
        try {
            context = SSLContext.getInstance("TLS");

            byte[] keyBytes = parseDERFromPEM(Files.readAllBytes(new File(certDir, "privkey.pem").toPath()));

            X509Certificate cert;
            try (FileInputStream fis = new FileInputStream(new File(certDir, "cert.pem"))) {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(fis);
            }
            expiry = cert.getNotAfter();

            X509Certificate chain;
            try (FileInputStream fis = new FileInputStream(new File(certDir, "chain.pem"))) {
                chain = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(fis);
            }
            if (chain.getNotAfter().getTime() < cert.getNotAfter().getTime())
                expiry = chain.getNotAfter();

            if (expiry.getTime() < System.currentTimeMillis()) {
                printWebSocket("WSS Certificate expired (" + expiry + ")", true);
                return null;
            }

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

            KeyStore keystore = KeyStore.getInstance("JKS");
            keystore.load(null);
            keystore.setCertificateEntry("1", chain);
            keystore.setCertificateEntry("1", cert);
            keystore.setKeyEntry("1", key, new char[0], new Certificate[]{cert, chain});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(keystore, new char[0]);

            context.init(kmf.getKeyManagers(), null, null);
        }
        catch (IOException | KeyManagementException | KeyStoreException | InvalidKeySpecException |
                UnrecoverableKeyException | NoSuchAlgorithmException | CertificateException e)
        {
            NRODLight.printThrowable(e, "WebSocket");
            return null;
        }
        return new Pair<>(context, expiry);
    }

    private static byte[] parseDERFromPEM(byte[] pem)
    {
        String data = new String(pem);
        String[] tokens = data.split("-----BEGIN PRIVATE KEY-----");
        tokens = tokens[1].split("-----END PRIVATE KEY-----");
        return Base64.getDecoder().decode(tokens[0].replace("\r\n", "").replace("\n", ""));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake)
    {
        if (conn instanceof EASMWebSocketImpl)
        {
            EASMWebSocketImpl easmconn = (EASMWebSocketImpl)conn;
            easmconn.setIP(handshake.hasFieldValue("X-Forwarded-For") ? handshake.getFieldValue("X-Forwarded-For") :
                (handshake.hasFieldValue("CF-Connecting-IP") ? handshake.getFieldValue("CF-Connecting-IP") :
                    (conn.getRemoteSocketAddress().getAddress().getHostAddress())));

            printWebSocket(String.format("Open connection to %s from %s%s",
                    easmconn.getIP(),
                    handshake.hasFieldValue("Origin") ? handshake.getFieldValue("Origin") : "Unknown",
                    handshake.hasFieldValue("User-Agent") ? " (" + handshake.getFieldValue("User-Agent") + ")" : ""
                ), false);

            JSONObject message = new JSONObject();
            JSONObject content = new JSONObject();
            content.put("type", "HELLO");
            content.put("messageID", "%nextid%");
            content.put("timestamp", System.currentTimeMillis());
            content.put("areas", new JSONArray());
            content.put("options", Collections.singletonList("split_full_messages"));
            message.put("Message", content);
            easmconn.send(message.toString());

            RateMonitor.getInstance().onWSOpen();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote)
    {
        printWebSocket(
            String.format("Close connection %s %s (%s%s)",
                remote ? "from" : "to",
                conn instanceof EASMWebSocketImpl ? ((EASMWebSocketImpl)conn).getIP() : conn.getRemoteSocketAddress().getAddress().getHostAddress(),
                code,
                reason != null && !reason.isEmpty() ? "/" + reason : ""
            ),
        false);
    }

    @Override
    public void onMessage(WebSocket conn, String message)
    {
        if (conn instanceof EASMWebSocketImpl)
            ((EASMWebSocketImpl) conn).receive(message);
        else
            printWebSocket("Message (" + conn.getRemoteSocketAddress().getAddress().getHostAddress() + "):\n  " + message, false);
    }

    @Override
    public void onError(WebSocket conn, Exception ex)
    {
        String ip = "Unknown";
        if (conn != null)
        {
            try
            {
                ip = conn instanceof EASMWebSocketImpl ? ((EASMWebSocketImpl)conn).getIP() :
                    conn.getRemoteSocketAddress().getAddress().getHostAddress();
            }
            catch (Exception ignored) {}

            printWebSocket("Error (" + ip + "): " + ex.toString(), true);
            conn.close(CloseFrame.NORMAL, ex.getMessage());
        }
    }

    @Override
    public void onStart()
    {
        printWebSocket("Started on " + getPort(), false, true);
    }

    @Override
    public Collection<WebSocket> getConnections()
    {
        return super.getConnections().stream().filter(c -> c != null && c.isOpen()).collect(Collectors.toList());
    }

    public long getConnectionCount()
    {
        return super.getConnections().stream().filter(c -> c != null && c.isOpen()).count();
    }

    @Override
    public void stop(int timeout) throws InterruptedException
    {
        serverClosed.set(true);
        if (certExpirationTask != null)
            certExpirationTask.cancel(false);
        super.stop(timeout);
    }

    public boolean isClosed()
    {
        return serverClosed.get();
    }

    public static void printWebSocket(String message, boolean toErr)
    {
        printWebSocket(message, toErr, false);
    }

    public static void printWebSocket(String message, boolean toErr, boolean forceStdout)
    {
        if (toErr)
            NRODLight.printErr("[WebSocket] " + message);
        else
            NRODLight.printOut("[WebSocket] " + message, forceStdout);
    }

    public static JSONObject updateDelayData() throws SQLException
    {
        if (NRODLight.config.optBoolean("delays_active", true)) {
            final long start = System.nanoTime();
            long queryEnd = -1;

            final Connection conn = DBHandler.getConnection();
            final String[] columns = {"train_id", "train_id_current", "schedule_uid", "start_timestamp", "current_delay",
                    "next_expected_update", "off_route", "finished", "last_update", "tds", "loc_origin", "loc_dest", "origin_dep",
                    "toc_code"};
            final JSONArray resultData = new JSONArray();
            try (final PreparedStatement psDelayData = conn.prepareStatement(Queries.DELAY_DATA)) {
                try (final ResultSet r = psDelayData.executeQuery()) {
                    queryEnd = System.nanoTime();
                    while (r.next()) {
                        final JSONObject train = new JSONObject();
                        for (int i = 0; i < columns.length; i++) {
                            if ("tds".equals(columns[i]))
                                train.put(columns[i], Arrays.asList(r.getString(i + 1).split(",")));
                            else
                                train.put(columns[i], r.getObject(i + 1));
                        }

                        resultData.put(train);
                    }
                }
            }

            final JSONObject content = new JSONObject();
            content.put("timestamp_data", Long.toString(System.currentTimeMillis()));
            content.put("message", resultData);

            NRODLight.printOut(String.format("[Delays] Updated in %.2fms (query %.2fms)", (System.nanoTime() - start) / 1000000d, queryEnd == -1 ? -1 : (queryEnd - start) / 1000000d));

            delayData = content;
        }
        else
            NRODLight.printOut("[Delays] Delay data updates disabled");

        return delayData;
    }

    public static JSONObject getDelayData()
    {
        return delayData;
    }

    public static class Pair<T, U> {
        private final T t;
        private final U u;

        public Pair(T t, U u) {
            this.t = t;
            this.u = u;
        }

        public T getLeft()
        {
            return t;
        }

        public U getRight()
        {
            return u;
        }
    }
}
