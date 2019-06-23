package nrodlight.ws;

import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.server.CustomSSLWebSocketServerFactory;

import javax.net.ssl.SSLContext;
import java.util.List;

public class EASMWebSocketFactory extends CustomSSLWebSocketServerFactory
{
    public EASMWebSocketFactory(SSLContext sslContext, String[] enabledProtocols, String[] enabledCiphersuites)
    {
        super(sslContext, enabledProtocols, enabledCiphersuites);
    }

    @Override
    public WebSocketImpl createWebSocket(WebSocketAdapter a, Draft d)
    {
        return new EASMWebSocketImpl(a, d);
    }

    @Override
    public WebSocketImpl createWebSocket(WebSocketAdapter a, List<Draft> d)
    {
        return new EASMWebSocketImpl(a, d);
    }
}
