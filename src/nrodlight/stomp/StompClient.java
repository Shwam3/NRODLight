package nrodlight.stomp;

import net.ser1.stomp.Command;
import net.ser1.stomp.Listener;
import net.ser1.stomp.MessageReceiver;
import net.ser1.stomp.Receiver;
import net.ser1.stomp.Stomp;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class StompClient extends Stomp implements MessageReceiver
{
    private Thread       listener;
    private OutputStream output;
    private InputStream  input;
    private Socket       socket;

    private String clientID;

    public StompClient(String server, int port, String login, String pass, String clientId) throws IOException, LoginException
    {
        socket = new Socket(server, port);
        input  = socket.getInputStream();
        output = socket.getOutputStream();

        listener = new Receiver(this, input);
        listener.start();

        // Connect to the server
        Map<String, String> header = new HashMap<>();
        header.put("login", login);
        header.put("passcode", pass);
        header.put("client-id", clientId);
        header.put("heart-beat", "30000,10000");
        header.put("accept-version", "1.2");

        transmit(Command.CONNECT, header, null);
        this.clientID = clientId;

        try
        {
            int connectAttempts = 0;
            String error = null;

            while (connectAttempts <= 20 && (!isConnected() && ((error = nextError()) == null)))
            {
                Thread.sleep(100);
                connectAttempts++;
            }

            if (error != null)
                throw new LoginException(error);
        }
        catch (InterruptedException ignored) {}
    }

    @Override
    public void disconnect(Map<String, String> header)
    {
        if (!isConnected())
            return;

        transmit(Command.DISCONNECT, header, null);
        listener.interrupt();

        try { input.close(); }
        catch (IOException ignored) {}

        try { output.close(); }
        catch (IOException ignored) {}

        try { socket.close(); }
        catch (IOException ignored) {}

        connected = false;
    }

    public void awaitClose() throws InterruptedException
    {
        if (!connected && Thread.currentThread() != listener)
            listener.join();
    }

    public void ack(String id/*, String subscriptionID*/)
    {
        try
        {
            StringBuilder message = new StringBuilder("ACK\n");

            //message.append("subscription:").append(clientID).append("-").append(subscriptionID).append("\n"); // 1.0,1.1
            //message.append("message-id:").append(id.replace(":", "\\c")).append("\n");                        // 1.0,1.1
            message.append("id:").append(id.replace("\\c", ":")).append("\n");                                  // 1.2

            message.append("\n");
            message.append("\000");

            output.write(message.toString().getBytes(Command.ENCODING));
        }
        catch (IOException e)
        {
            receive(Command.ERROR, null, e.getMessage());
        }
    }

    public void subscribe(String topicName, String topicID, Listener listener)
    {
        Map<String, String> headers = new HashMap<>();

        headers.put("ack", "client-individual");
        headers.put("id",  clientID + "-" + topicID);
        headers.put("activemq.subscriptionName", clientID + "-" + topicID);

        super.subscribe(topicName, listener, headers);

        StompConnectionHandler.printStomp("Subscribed to \"" + topicName + "\" (ID: \"" + clientID + "-" + topicID + "\")", false);
    }

    @Override
    public void unsubscribe(String name)
    {
        Map<String, String> headers = new HashMap<>(1);
        headers.put("id", clientID + "-" + name);

        unsubscribe(name, headers);
    }

    @Override
    protected void transmit(Command command, Map<String, String> header, String body)
    {
        try
        {
            StringBuilder message = new StringBuilder(command.toString());
            message.append("\n");

            if (header != null)
                header.keySet().forEach((key) -> message.append(key).append(":").append(header.get(key)).append("\n"));

            message.append("\n");

            if (body != null)
                message.append(body);

            message.append("\000");

            output.write(message.toString().getBytes(Command.ENCODING));
        }
        catch (IOException e)
        {
            receive(Command.ERROR, null, e.getMessage());
        }
    }

    @Override
    public boolean isClosed()
    {
        return socket.isClosed();
    }
}