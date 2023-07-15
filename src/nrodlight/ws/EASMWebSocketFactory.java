package nrodlight.ws;

import nrodlight.NRODLight;
import nrodlight.SigmapsThreadFactory;
import org.java_websocket.SSLSocketChannel2;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketServerFactory;
import org.java_websocket.drafts.Draft;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EASMWebSocketFactory implements WebSocketServerFactory {
    private SSLContext sslContext;
    private ExecutorService executor;
    private SSLParameters sslParameters;

    public EASMWebSocketFactory() {
        this(null, null);
    }

    public EASMWebSocketFactory(SSLContext sslContext, SSLParameters sslParameters)
    {
        this.sslContext = sslContext;
        this.sslParameters = sslParameters;
        this.executor = Executors.newSingleThreadExecutor(new SigmapsThreadFactory("SocketFactoryExecutor"));
    }

    public void updateSSLDetails(SSLContext sslContext, SSLParameters sslParameters)
    {
        this.sslContext = sslContext;
        this.sslParameters = sslParameters;
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
        if (sslContext != null && sslParameters != null) {
            SSLEngine e = sslContext.createSSLEngine();
            e.setUseClientMode(false);
            e.setSSLParameters(sslParameters);
            return new SSLSocketChannel2(channel, e, executor, key);
        } else {
            if (NRODLight.config.optBoolean("strict_wss", true))
                throw new IOException("SSL Context not set with strict_wss mode set");

            return channel;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
