package nrodlight.stomp.handlers;

import nrodlight.NRODLight;

import javax.jms.JMSException;

public class ErrorHandler //implements Listener
{
    /*
    @Override
    public void message(Map<String, String> headers, String message)
    {
        if (headers != null)
            StompConnectionHandler.printStomp(headers.get("message").trim(), true);
        else
            StompConnectionHandler.printStomp("No header in error message", true);

        if (message != null && !message.isEmpty())
            StompConnectionHandler.printStomp(message.trim().replace("\n", "\n[Stomp]"), true);
    }
    */

    public static void onException(JMSException exception)
    {
        NRODLight.printThrowable(exception, "ActiveMQ");
    }
}
