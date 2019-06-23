package nrodlight.ws;

import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.db.DBHandler;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class EASMWebSocket extends WebSocketServer
{
    private static final AtomicBoolean serverClosed = new AtomicBoolean(false);
    private static JSONObject delayData = null;

    public EASMWebSocket()
    {
        super(new InetSocketAddress(NRODLight.config.optInt("WSPort",8443)));
        super.setReuseAddr(true);

        Pair<SSLContext, Date> sslData = getSSLContextFromLetsEncrypt();
        SSLContext sslContext;
        if (sslData != null)
        {
            sslContext = sslData.t;
            List<String> ciphers = Arrays.asList(
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
                    "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256",
                    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256"
                );

            SSLEngine engine = sslContext.createSSLEngine();
            List<String> ciphersAvailable = Arrays.asList(engine.getEnabledCipherSuites());
            ciphers = ciphers.stream().filter(ciphersAvailable::contains).collect(Collectors.toList());

            setWebSocketFactory(new EASMWebSocketFactory(sslContext, engine.getEnabledProtocols(), ciphers.toArray(new String[0])));

            long delay = sslData.u.getTime() - System.currentTimeMillis() - TimeUnit.HOURS.convert(12, TimeUnit.MILLISECONDS);

            Executors.newSingleThreadScheduledExecutor().schedule(() ->
            {
                try { NRODLight.webSocket.stop(0); }
                catch (InterruptedException ignored) {}

                NRODLight.ensureServerOpen();
            }, delay, TimeUnit.MILLISECONDS);
        }
        else
            printWebSocket("Unable to create SSL Context", true);
    }

    private Pair<SSLContext, Date> getSSLContextFromLetsEncrypt()
    {
        SSLContext context;
        Date expiry;
        File certDir = new File(NRODLight.EASM_STORAGE_DIR, "certs");
        try
        {
            context = SSLContext.getInstance("TLS");

            byte[] keyBytes = parseDERFromPEM(Files.readAllBytes(new File(certDir , "privkey.pem").toPath()), "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");

            X509Certificate cert;
            try (FileInputStream fis = new FileInputStream(new File(certDir, "fullchain.pem")))
            {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(fis);
            }
            expiry = cert.getNotAfter();

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

            KeyStore keystore = KeyStore.getInstance("JKS");
            keystore.load(null);
            keystore.setCertificateEntry("cert-alias", cert);
            keystore.setKeyEntry("key-alias", key, new char[0], new Certificate[]{cert});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(keystore, new char[0]);

            context.init(kmf.getKeyManagers(), null, null);
        }
        catch (IOException | KeyManagementException | KeyStoreException | InvalidKeySpecException | UnrecoverableKeyException | NoSuchAlgorithmException | CertificateException e)
        {
            NRODLight.printThrowable(e, "WebSocket");
            return null;
        }
        return new Pair<>(context, expiry);
    }

    private static byte[] parseDERFromPEM(byte[] pem, String beginDelimiter, String endDelimiter)
    {
        String data = new String(pem);
        String[] tokens = data.split(beginDelimiter);
        tokens = tokens[1].split(endDelimiter);
        return DatatypeConverter.parseBase64Binary(tokens[0]);
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
            content.put("timestamp", System.currentTimeMillis());
            content.put("areas", new JSONArray());
            content.put("options", new JSONArray());
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
            catch (Exception e) {}

            printWebSocket("Error (" + ip + "):", true);
            conn.close(CloseFrame.NORMAL, ex.getMessage());
        }
        NRODLight.printThrowable(ex, "WebSocket" + (conn != null ? "-" + ip : ""));
    }

    @Override
    public void onStart()
    {
        printWebSocket("Started", false);
    }

    @Override
    public Collection<WebSocket> getConnections()
    {
        return super.getConnections().stream().filter(c -> c != null && c.isOpen()).collect(Collectors.toList());
    }

    @Override
    public void stop(int timeout) throws InterruptedException
    {
        serverClosed.set(true);
        super.stop(timeout);
    }

    public boolean isClosed()
    {
        return serverClosed.get();
    }

    public static void printWebSocket(String message, boolean toErr)
    {
        if (toErr)
            NRODLight.printErr("[WebSocket] " + message);
        else
            NRODLight.printOut("[WebSocket] " + message);
    }

    public static JSONObject updateDelayData() throws SQLException
    {
        long start = System.nanoTime();

        Connection conn = DBHandler.getConnection();
        //PreparedStatement psDelayData = conn.prepareStatement("SELECT a.train_id,a.train_id_current," +
        //        "a.schedule_uid,a.start_timestamp,a.current_delay,a.next_expected_update,a.off_route," +
        //        "a.finished,GROUP_CONCAT(DISTINCT s.td ORDER BY s.td SEPARATOR ',') AS tds FROM " +
        //        "activations a INNER JOIN schedule_locations l ON a.schedule_uid = l.schedule_uid AND " +
        //        "a.stp_indicator = l.stp_indicator AND a.schedule_date_from = l.date_from AND " +
        //        "a.schedule_source = l.schedule_source INNER JOIN corpus c ON l.tiploc = c.tiploc INNER " +
        //        "JOIN smart s ON c.stanox = s.stanox WHERE (a.last_update > ?) AND (a.finished = 0 OR " +
        //        "a.last_update > ?) AND a.cancelled = 0 GROUP BY a.train_id");
        //psDelayData.setLong(1, System.currentTimeMillis() - 43200000L); // 12 hours
        //psDelayData.setLong(2, System.currentTimeMillis() - 2700000L); // 45 mins
        PreparedStatement psDelayData = conn.prepareStatement("SELECT a.train_id,a.train_id_current,a.schedule_uid," +
                "a.start_timestamp,a.current_delay,a.next_expected_update,a.off_route,a.finished,a.last_update," +
                "GROUP_CONCAT(DISTINCT s.td ORDER BY s.td SEPARATOR ',') AS tds FROM activations a INNER JOIN " +
                "schedule_locations l ON a.schedule_uid=l.schedule_uid AND a.stp_indicator=l.stp_indicator AND " +
                "a.schedule_date_from=l.date_from AND a.schedule_source=l.schedule_source INNER JOIN corpus c ON " +
                "l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE (a.last_update > " +
                "(CAST(UNIX_TIMESTAMP(CURTIME(3)) AS INT) - 43200) * 1000) AND (a.finished=0 OR a.last_update > " +
                "(CAST(UNIX_TIMESTAMP(CURTIME(3)) AS INT) - 2700) * 1000) AND a.cancelled=0 GROUP BY a.train_id");
        ResultSet r = psDelayData.executeQuery();

        String[] columns = {"train_id","train_id_current","schedule_uid","start_timestamp","current_delay",
                "next_expected_update","off_route","finished","last_update","tds"};
        JSONArray resultData = new JSONArray();
        while (r.next())
        {
            JSONObject jobj = new JSONObject();

            for (int i = 0; i < columns.length; i++)
            {
                if ("tds".equals(columns[i]))
                    jobj.put(columns[i], new JSONArray(r.getString(i+1).split(",")));
                else
                    jobj.put(columns[i], r.getObject(i+1));
            }

            resultData.put(jobj);
        }
        r.close();
        psDelayData.close();

        JSONObject content = new JSONObject();
        content.put("type", "DELAYS");
        content.put("timestamp", -1);
        content.put("timestamp_data", Long.toString(System.currentTimeMillis()));
        content.put("message", resultData);

        NRODLight.printOut(String.format("[Delays] Updated in %.2fms", (System.nanoTime() - start) / 1000000d));

        return EASMWebSocket.delayData = content;
    }

    public static JSONObject getDelayData()
    {
        return delayData;
    }

    public class Pair<T, U> {
        public final T t;
        public final U u;

        public Pair(T t, U u) {
            this.t= t;
            this.u= u;
        }
    }
}
