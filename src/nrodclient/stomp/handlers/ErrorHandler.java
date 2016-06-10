package nrodclient.stomp.handlers;

import java.util.Map;
import net.ser1.stomp.Listener;
import static nrodclient.stomp.StompConnectionHandler.printStomp;

public class ErrorHandler implements Listener
{
    @Override
    public void message(Map<String, String> headers, String message)
    {
        if (headers != null)
            printStomp(headers.get("message").trim(), true);
        else
            printStomp("No header in error message", true);

        if (message != null && !message.isEmpty())
            printStomp(message.trim().replace("\n", "\n[Stomp]"), true);
    }

}