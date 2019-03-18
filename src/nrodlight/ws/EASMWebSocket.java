package nrodlight.ws;

import nrodlight.NRODLight;
import nrodlight.RateMonitor;
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
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class EASMWebSocket extends WebSocketServer
{
    private static final AtomicBoolean serverClosed = new AtomicBoolean(false);
    private static String delayData = null;

    public EASMWebSocket()
    {
        super(new InetSocketAddress(NRODLight.config.optInt("WSPort",8443)));
        super.setReuseAddr(true);

        SSLContext sslContext = getSSLContextFromLetsEncrypt();
        if (sslContext != null)
        {
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
        }
        else
            printWebSocket("Unable to create SSL Context", true);
    }

    private SSLContext getSSLContextFromLetsEncrypt()
    {
        SSLContext context;
        File certDir = new File(NRODLight.EASM_STORAGE_DIR, "certs");
        try
        {
            context = SSLContext.getInstance("TLS");

            //byte[] certBytes = parseDERFromPEM(Files.readAllBytes(new File(certDir , "cert.pem").toPath()), "-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----");
            byte[] keyBytes = parseDERFromPEM(Files.readAllBytes(new File(certDir , "privkey.pem").toPath()), "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");

            Certificate cert;
            try (FileInputStream fis = new FileInputStream(new File(certDir, "cert.pem")))
            {
                cert = CertificateFactory.getInstance("X.509").generateCertificate(fis);
            }

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
        return context;
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
        String ip = null;
        if (conn != null)
        {
            try { ip = conn instanceof EASMWebSocketImpl ? ((EASMWebSocketImpl)conn).getIP() : conn.getRemoteSocketAddress().getAddress().getHostAddress(); }
            catch (Exception e) {}

            printWebSocket("Error (" + (ip != null ? ip : "Unknown") + "):", true);
            conn.close(CloseFrame.NORMAL, ex.getMessage());
        }
        NRODLight.printThrowable(ex, "WebSocket" + (conn != null ? "-" + (ip != null ? ip : "Unknown") : ""));
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

    public static void setDelayData(String delayData)
    {
        EASMWebSocket.delayData = delayData;
    }

    public static String getDelayData()
    {
        return delayData;
    }
}
