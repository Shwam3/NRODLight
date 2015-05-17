package nrodclient.stomp.handlers;

import java.util.Map;
import net.ser1.stomp.Listener;
import nrodclient.NRODClient;
import nrodclient.stomp.StompConnectionHandler;

public class VSTPHandler implements Listener
{
    private long lastMessageTime = 0;

    private static Listener instance = null;
    private VSTPHandler() { lastMessageTime = System.currentTimeMillis(); }
    public static Listener getInstance()
    {
        if (instance == null)
            instance = new VSTPHandler();

        return instance;
    }

    @Override
    public void message(Map<String, String> headers, String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        printVSTP(message, false);

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 3600000; }

    private static void printVSTP(String message, boolean toErr)
    {
        if (toErr)
            NRODClient.printErr("[VSTP] " + message);
        else
            NRODClient.printOut("[VSTP] " + message);
    }
}