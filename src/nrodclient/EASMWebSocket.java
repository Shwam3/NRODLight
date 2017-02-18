package nrodclient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import nrodclient.stomp.handlers.TDHandler;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

public class EASMWebSocket extends WebSocketServer
{
    public EASMWebSocket(int port, boolean useSSL)
    {
        super(new InetSocketAddress(port));
        
        if (useSSL)
        {
            try
            {                
                KeyStore ks = KeyStore.getInstance("jks");
                ks.load(new FileInputStream(new File(NRODClient.EASM_STORAGE_DIR, "certs" + File.separator + "keystore.jks")), "KkwnhSGpu428uhf".toCharArray());

                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, "KkwnhSGpu428uhf".toCharArray());
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
                tmf.init(ks);

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

                setWebSocketFactory(new DefaultSSLWebSocketServerFactory(sslContext));
            }
            catch (UnrecoverableKeyException | KeyManagementException | KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e)
            {
                NRODClient.printThrowable(e, "SSLWebSocket");
            }
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake)
    {
        printWebSocket("Open connection to " + conn.getRemoteSocketAddress().getAddress().getHostAddress() + ":" + conn.getRemoteSocketAddress().getPort(), false);

        JSONObject message = new JSONObject();
        JSONObject content = new JSONObject();
        content.put("type", "SEND_ALL");
        content.put("timestamp", Long.toString(System.currentTimeMillis()));
        content.put("message", TDHandler.DATA_MAP);
        message.put("Message", content);
        String messageStr = message.toString();

        conn.send(messageStr);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote)
    {
        printWebSocket(
            String.format("Close connection to %s (%s%s)",
                conn.getRemoteSocketAddress().getAddress().getHostAddress(),
                code,
                reason != null && !reason.isEmpty() ? "/" + reason : ""
            ),
        false);
    }

    @Override
    public void onMessage(WebSocket conn, String message)
    {
        printWebSocket("Message (" + conn.getRemoteSocketAddress().getAddress().getHostAddress() + "):\n  " + message, false);
    }

    @Override
    public void onError(WebSocket conn, Exception ex)
    {
        if (conn != null)
        {
            printWebSocket("Error (" + conn.getRemoteSocketAddress().getAddress().getHostAddress() + "):", true);
            conn.close(CloseFrame.ABNORMAL_CLOSE, ex.getMessage());
        }
        NRODClient.printThrowable(ex, "WebSocket" + (conn != null ? "-" + conn.getRemoteSocketAddress().getAddress().getHostAddress() : ""));
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
            NRODClient.printErr("[WebSocket] " + message);
        else
            NRODClient.printOut("[WebSocket] " + message);
    }
}