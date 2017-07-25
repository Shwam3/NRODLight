package nrodlight.ws;

import java.net.Socket;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;

public class EASMWebSocketFactory extends DefaultSSLWebSocketServerFactory
{
    public EASMWebSocketFactory(SSLContext sslContext)
    {
        super(sslContext);
    }

    @Override
    public WebSocketImpl createWebSocket( WebSocketAdapter a, Draft d, Socket c )
    {
        return new EASMWebSocketImpl( a, d );
    }

    @Override
    public WebSocketImpl createWebSocket( WebSocketAdapter a, List<Draft> d, Socket s )
    {
        return new EASMWebSocketImpl( a, d );
    }
}
