package nrodlight.stomp;

import nrodlight.NRODLight;
import nrodlight.stomp.handlers.ErrorHandler;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.stomp.handlers.TRUSTHandler;
import nrodlight.stomp.handlers.VSTPHandler;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSession;
import org.apache.activemq.command.ActiveMQDestination;

import javax.jms.*;

public class ConnectionManager
{
    private static ActiveMQConnection connection;
    private static Session session;
    private static TopicSubscriber consumerTD;
    private static TopicSubscriber consumerTRUST;
    private static TopicSubscriber consumerVSTP;
    private static String clientId = "";

    public static void start() throws JMSException
    {
        if (connection != null)
            throw new JMSException("Connection already open");

        printActiveMQ("Connecting to broker", false, false);
        NRODLight.reloadConfig();
        String username = NRODLight.config.optString("NROD_Username", "");
        String password = NRODLight.config.optString("NROD_Password", "");
        String NRODHost = NRODLight.config.optString("NROD_Host", "datafeeds.networkrail.co.uk");
        int NRODPort    = NRODLight.config.optInt("NROD_Port", 61619);
        clientId = username + "-NRODLight-" + NRODLight.config.optString("NROD_Instance_ID", "uid") + "-v" + NRODLight.VERSION;

        ActiveMQConnectionFactory amqcf = new ActiveMQConnectionFactory("failover:(tcp://" + NRODHost + ":" + NRODPort + ")" +
                "?maxReconnectAttempts=-1" +
                "&initialReconnectDelay=500" +
                "&maxReconnectDelay=5000" +
                "&warnAfterReconnectAttempts=1" +
                "&nested.wireFormat.maxInactivityDuration=5000" +
                "&startupMaxReconnectAttempts=-1");
        amqcf.setUserName(username);
        amqcf.setPassword(password);
        amqcf.setClientID(clientId);
        connection = (ActiveMQConnection) amqcf.createConnection();
        connection.setExceptionListener(ErrorHandler::onException);
        NRODLight.getExecutor().execute(() -> {
            try {
                connection.start();
                session = connection.createSession(false, ActiveMQSession.INDIVIDUAL_ACKNOWLEDGE);

                consumerTD = null;
                consumerTRUST = null;
                consumerVSTP = null;

                updateSubscriptions();
            } catch (JMSException ex) {
                NRODLight.printThrowable(ex, "ActiveMQ");
            }
        });
    }

    public static void updateSubscriptions() throws JMSException
    {
        if (connection == null || session == null)
            return;

        final String TD_TOPIC = NRODLight.config.optString("TD_TOPIC", "TD_ALL_SIG_AREA");
        final String TRUST_TOPIC = NRODLight.config.optString("TRUST_TOPIC", "TRAIN_MVT_ALL_TOC");
        final String VSTP_TOPIC = NRODLight.config.optString("VSTP_TOPIC", "VSTP_ALL");

        final boolean activeTD = NRODLight.config.optBoolean("TD_ACTIVE", true);
        final boolean activeTRUST = NRODLight.config.optBoolean("TRUST_ACTIVE", true);
        final boolean activeVSTP = NRODLight.config.optBoolean("VSTP_ACTIVE", true);

        if (activeTD && consumerTD == null)
        {
            printActiveMQ("Subscribing to " + TD_TOPIC, false, false);
            consumerTD = session.createDurableSubscriber(
                    (Topic) ActiveMQDestination.createDestination("topic://" + TD_TOPIC, ActiveMQDestination.TOPIC_TYPE),
                    clientId + "-TD");
            consumerTD.setMessageListener(TDHandler.getInstance());
        }
        else if (!activeTD && consumerTD != null)
        {
            printActiveMQ("Unsubscribing from " + TD_TOPIC, false, false);
            consumerTD.close();
            consumerTD = null;
        }

        if (activeTRUST && consumerTRUST == null)
        {
            printActiveMQ("Subscribing to " + TRUST_TOPIC, false, false);
            consumerTRUST = session.createDurableSubscriber(
                    (Topic) ActiveMQDestination.createDestination("topic://" + TRUST_TOPIC, ActiveMQDestination.TOPIC_TYPE),
                    clientId + "-TRUST");
            consumerTRUST.setMessageListener(TRUSTHandler.getInstance());
        }
        else if (!activeTRUST && consumerTRUST != null)
        {
            printActiveMQ("Unsubscribing from " + TRUST_TOPIC, false, false);
            consumerTRUST.close();
            consumerTRUST = null;
        }

        if (activeVSTP && consumerVSTP == null)
        {
            printActiveMQ("Subscribing to " + VSTP_TOPIC, false, false);
            consumerVSTP = session.createDurableSubscriber(
                    (Topic) ActiveMQDestination.createDestination("topic://" + VSTP_TOPIC, ActiveMQDestination.TOPIC_TYPE),
                    clientId + "-VSTP");
            consumerVSTP.setMessageListener(VSTPHandler.getInstance());
        }
        else if (!activeVSTP && consumerVSTP != null)
        {
            printActiveMQ("Unsubscribing from " + VSTP_TOPIC, false, false);
            consumerVSTP.close();
            consumerVSTP = null;
        }
    }

    public static void stop()
    {
        try
        {
            final ActiveMQConnection conn = connection;
            connection = null;
            session = null;
            consumerTD = null;
            consumerTRUST = null;
            consumerVSTP = null;

            if (conn != null)
                conn.stop();
        }
        catch (JMSException ex) { NRODLight.printThrowable(ex, "ActiveMQ"); }
    }

    public static void printActiveMQ(String message, boolean toErr, boolean forceStdout)
    {
        if (toErr)
            NRODLight.printErr("[ActiveMQ] " + message);
        else
            NRODLight.printOut("[ActiveMQ] " + message, forceStdout);
    }
}
