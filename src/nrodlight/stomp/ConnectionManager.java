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

import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.Topic;

public class ConnectionManager
{
    private static ActiveMQConnection connection;

    public static void start() throws JMSException
    {
        if (connection != null)
            throw new JMSException("Connection already open");

        NRODLight.reloadConfig();
        String username = NRODLight.config.optString("NROD_Username", "");
        String password = NRODLight.config.optString("NROD_Password", "");
        String NRODHost = NRODLight.config.optString("NROD_Host", "datafeeds.networkrail.co.uk");
        int NRODPort    = NRODLight.config.optInt("NROD_Port", 61619);
        String clientId = username + "-NRODLight-" + NRODLight.config.optString("NROD_Instance_ID", "uid") + "-v" + NRODLight.VERSION;

        String TRUST_TOPIC = NRODLight.config.optString("TRUST_TOPIC", "TRAIN_MVT_ALL_TOC");
        String VSTP_TOPIC = NRODLight.config.optString("VSTP_TOPIC", "VSTP_ALL");
        String TD_TOPIC = NRODLight.config.optString("TD_TOPIC", "TD_ALL_SIG_AREA");

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
        connection.start();
        Session session = connection.createSession(false, ActiveMQSession.INDIVIDUAL_ACKNOWLEDGE);
        final byte TOPIC = ActiveMQDestination.TOPIC_TYPE;
        MessageConsumer consumerTD = session.createDurableSubscriber(
                (Topic) ActiveMQDestination.createDestination("topic://" + TD_TOPIC, TOPIC),
                clientId + "-TD");
        consumerTD.setMessageListener(TDHandler.getInstance());
        MessageConsumer consumerTRUST = session.createDurableSubscriber(
                (Topic) ActiveMQDestination.createDestination("topic://" + TRUST_TOPIC, TOPIC),
                clientId + "-TRUST");
        consumerTRUST.setMessageListener(TRUSTHandler.getInstance());
        MessageConsumer consumerVSTP = session.createDurableSubscriber(
                (Topic) ActiveMQDestination.createDestination("topic://" + VSTP_TOPIC, TOPIC),
                clientId + "-VSTP");
        consumerVSTP.setMessageListener(VSTPHandler.getInstance());
    }

    public static void stop()
    {
        try
        {
            if (connection != null)
                connection.stop();
        }
        catch (JMSException ex) { NRODLight.printThrowable(ex, "ActiveMQ"); }
    }
}
