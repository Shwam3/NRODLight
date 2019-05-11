package nrodlight.stomp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.login.LoginException;
import net.ser1.stomp.Command;
import net.ser1.stomp.Listener;
import net.ser1.stomp.MessageReceiver;
import net.ser1.stomp.Receiver;
import net.ser1.stomp.Stomp;

public class StompClient extends Stomp implements MessageReceiver {
   private Thread listener;
   private OutputStream output;
   private InputStream input;
   private Socket socket;
   private String clientID;

   public StompClient(String server, int port, String login, String pass, String clientId) throws IOException, LoginException {
      this.socket = new Socket(server, port);
      this.input = this.socket.getInputStream();
      this.output = this.socket.getOutputStream();
      this.listener = new Receiver(this, this.input);
      this.listener.start();
      Map header = new HashMap();
      header.put("login", login);
      header.put("passcode", pass);
      header.put("client-id", clientId);
      header.put("heart-beat", "30000,10000");
      header.put("accept-version", "1.2");
      this.transmit(Command.CONNECT, header, (String)null);
      this.clientID = clientId;

      try {
         int connectAttempts = 0;

         String error;
         for(error = null; connectAttempts <= 20 && !this.isConnected() && (error = this.nextError()) == null; ++connectAttempts) {
            Thread.sleep(100L);
         }

         if (error != null) {
            throw new LoginException(error);
         }
      } catch (InterruptedException var9) {
      }

   }

   public void disconnect(Map header) {
      if (this.isConnected()) {
         this.transmit(Command.DISCONNECT, header, (String)null);
         this.listener.interrupt();

         try {
            this.input.close();
         } catch (IOException var5) {
         }

         try {
            this.output.close();
         } catch (IOException var4) {
         }

         try {
            this.socket.close();
         } catch (IOException var3) {
         }

         this.connected = false;
      }
   }

   public void ack(String id) {
      try {
         StringBuilder message = new StringBuilder("ACK\n");
         message.append("id:").append(id.replace("\\c", ":")).append("\n");
         message.append("\n");
         message.append("\u0000");
         this.output.write(message.toString().getBytes("UTF-8"));
      } catch (IOException var3) {
         this.receive(Command.ERROR, (Map)null, var3.getMessage());
      }

   }

   public void subscribe(String topicName, String topicID, Listener listener) {
      Map headers = new HashMap();
      headers.put("ack", "client-individual");
      headers.put("id", this.clientID + "-" + topicID);
      headers.put("activemq.subscriptionName", this.clientID + "-" + topicID);
      super.subscribe(topicName, listener, headers);
      StompConnectionHandler.printStomp("Subscribed to \"" + topicName + "\" (ID: \"" + this.clientID + "-" + topicID + "\")", false);
   }

   public void unsubscribe(String name) {
      Map headers = new HashMap(1);
      headers.put("id", this.clientID + "-" + name);
      this.unsubscribe(name, headers);
   }

   protected void transmit(Command command, Map header, String body) {
      try {
         StringBuilder message = new StringBuilder(command.toString());
         message.append("\n");
         if (header != null) {
            header.keySet().stream().forEach((key) -> {
               message.append(key).append(":").append((String)header.get(key)).append("\n");
            });
         }

         message.append("\n");
         if (body != null) {
            message.append(body);
         }

         message.append("\u0000");
         this.output.write(message.toString().getBytes("UTF-8"));
      } catch (IOException var5) {
         this.receive(Command.ERROR, (Map)null, var5.getMessage());
      }

   }

   public boolean isClosed() {
      return this.socket.isClosed();
   }
}
