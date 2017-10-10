package nrodlight.ws;

import java.util.List;
import javax.net.ssl.SSLContext;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.server.CustomSSLWebSocketServerFactory;

public class EASMWebSocketFactory extends CustomSSLWebSocketServerFactory
{
    public EASMWebSocketFactory(SSLContext sslContext, String[] enabledProtocols, String[] enabledCiphersuites)
    {
        super(sslContext, enabledProtocols, enabledCiphersuites);
    }

    @Override
    public WebSocketImpl createWebSocket( WebSocketAdapter a, Draft d )
    {
        return new EASMWebSocketImpl( a, d );
    }

    @Override
    public WebSocketImpl createWebSocket( WebSocketAdapter a, List<Draft> d )
    {
        return new EASMWebSocketImpl( a, d );
    }
}
