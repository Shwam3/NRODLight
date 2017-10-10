package nrodlight.ws;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.xml.bind.DatatypeConverter;
import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.stomp.handlers.TDHandler;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

public class EASMWebSocket extends WebSocketServer
{
    public EASMWebSocket(int port, boolean useSSL)
    {
        super(new InetSocketAddress(port));
        
        if (useSSL)
        {
            SSLContext sslContext = getSSLContextFromLetsEncrypt();
            if (sslContext != null)
            {
                SSLEngine engine = sslContext.createSSLEngine();
                List<String> ciphers = new ArrayList<String>(Arrays.asList(engine.getEnabledCipherSuites()));
                ciphers.remove("SSL_RSA_WITH_3DES_EDE_CBC_SHA");
                ciphers.remove("TLS_RSA_WITH_3DES_EDE_CBC_SHA");
                ciphers.remove("SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA");
                ciphers.remove("TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA");
                ciphers.remove("TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA");
                ciphers.remove("TLS_DHE_RSA_WITH_AES_128_CBC_SHA");
                ciphers.remove("TLS_DHE_RSA_WITH_AES_128_CBC_SHA256");
                ciphers.remove("TLS_DHE_RSA_WITH_AES_128_GCM_SHA256");
                ciphers.remove("TLS_DHE_RSA_WITH_AES_256_CBC_SHA");
                ciphers.remove("TLS_DHE_RSA_WITH_AES_256_CBC_SHA256");
                ciphers.remove("TLS_DHE_RSA_WITH_AES_256_GCM_SHA384");
                ciphers.remove("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
                
                setWebSocketFactory(new EASMWebSocketFactory(sslContext, engine.getEnabledProtocols(), ciphers.toArray(new String[ciphers.size()])));
            }
            else
                printWebSocket("Unable to create SSL Context", true);
        }
    }
    
    private SSLContext getSSLContextFromLetsEncrypt()
    {
        SSLContext context;
        File certDir = new File(NRODLight.EASM_STORAGE_DIR, "certs");
        try
        {
            context = SSLContext.getInstance("TLS");

            byte[] certBytes = parseDERFromPEM(Files.readAllBytes(new File(certDir , "cert.pem").toPath()), "-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----");
            byte[] keyBytes = parseDERFromPEM(Files.readAllBytes(new File(certDir , "privkey.pem").toPath()), "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");

            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
            
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateKey key = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            
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

    protected static byte[] parseDERFromPEM(byte[] pem, String beginDelimiter, String endDelimiter)
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
            content.put("type", "SEND_ALL");
            content.put("timestamp", System.currentTimeMillis());
            content.put("message", TDHandler.DATA_MAP);
            message.put("Message", content);
            String messageStr = message.toString();

            easmconn.send(messageStr);
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
        try { ip = conn instanceof EASMWebSocketImpl ? ((EASMWebSocketImpl)conn).getIP() : conn.getRemoteSocketAddress().getAddress().getHostAddress(); }
        catch (Exception e) {}
        
        if (conn != null)
        {
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
    public Collection<WebSocket> connections()
    {
        return super.connections().stream().filter(c -> c != null && c.isOpen()).collect(Collectors.toList());
    }
    
    public boolean isClosed()
    {
        return isclosed.get();
    }

    public static void printWebSocket(String message, boolean toErr)
    {
        if (toErr)
            NRODLight.printErr("[WebSocket] " + message);
        else
            NRODLight.printOut("[WebSocket] " + message);
    }
}