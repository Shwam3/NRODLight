package nrodlight.stomp.handlers;

import nrodlight.NRODLight;

import javax.jms.JMSException;

public class ErrorHandler //implements Listener
{
    public static void onException(JMSException exception)
    {
        NRODLight.printThrowable(exception, "ActiveMQ");
    }
}
