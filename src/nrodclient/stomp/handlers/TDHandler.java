package nrodclient.stomp.handlers;

import java.util.Map;
import net.ser1.stomp.Listener;
import nrodclient.stomp.StompConnectionHandler;

public class TDHandler implements Listener
{
    private long lastMessageTime = 0;

    private static Listener instance = null;
    private TDHandler() { lastMessageTime = System.currentTimeMillis(); }
    public static Listener getInstance()
    {
        if (instance == null)
            instance = new TDHandler();

        return instance;
    }

    @Override
    public void message(Map<String, String> headers, String body)
    {
        StompConnectionHandler.printStompHeaders(headers);
        
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 30000; }
}