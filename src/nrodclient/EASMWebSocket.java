package nrodclient;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import nrodclient.stomp.handlers.TDHandler;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

public class EASMWebSocket extends WebSocketServer
{
    public EASMWebSocket(int port) { super(new InetSocketAddress(port)); }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake)
    {
        printWebSocket("Open connection to " + conn.getRemoteSocketAddress().getAddress().getHostAddress() + ":" + conn.getRemoteSocketAddress().getPort(), false);

        JSONObject message = new JSONObject();
        JSONObject content = new JSONObject();
        content.put("type", "SEND_ALL");
        content.put("timestamp", Long.toString(System.currentTimeMillis()));
        content.put("message", new HashMap<>(TDHandler.DataMap));
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
        return Collections.unmodifiableCollection(super.connections());
    }

    public static void printWebSocket(String message, boolean toErr)
    {
        if (toErr)
            NRODClient.printErr("[WebSocket] " + message);
        else
            NRODClient.printOut("[WebSocket] " + message);
    }
}