package nrodlight.ws;

import org.java_websocket.SSLSocketChannel2;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

public class EASMWebSocketFactory extends DefaultSSLWebSocketServerFactory
{
    private final SSLParameters sslParameters;

    public EASMWebSocketFactory(SSLContext sslContext, SSLParameters sslParameters)
    {
        super(sslContext, Executors.newSingleThreadExecutor());

        this.sslParameters = Objects.requireNonNull(sslParameters);
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

    @Override
    public ByteChannel wrapChannel(SocketChannel channel, SelectionKey key) throws IOException
    {
        SSLEngine e = sslcontext.createSSLEngine();
        e.setUseClientMode(false);
        e.setSSLParameters(sslParameters);
        return new SSLSocketChannel2(channel, e, exec, key);
    }
}
