package nrodclient.stomp.handlers;

import java.util.Map;
import net.ser1.stomp.Listener;
import nrodclient.stomp.StompConnectionHandler;
import static nrodclient.stomp.StompConnectionHandler.printStomp;

public class ErrorHandler implements Listener
{
    @Override
    public void message(Map<String, String> headers, String message)
    {
        if (headers.get("message").contains("Broker: outboundDataAMQ - Client: ") && headers.get("message").contains(" already connected from "))
        {
            StompConnectionHandler.incrementConnectionId();
            printStomp("Incrementing connection ID: \"" + StompConnectionHandler.getConnectionName() + "\"", false);
        }

        printStomp(headers.get("message").trim(), true);

        if (message != null && !message.isEmpty())
            printStomp(message.trim().replace("\n", "\n[Stomp]"), true);
    }

}