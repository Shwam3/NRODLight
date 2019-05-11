package nrodlight.stomp.handlers;

import java.util.Map;
import net.ser1.stomp.Listener;
import nrodlight.stomp.StompConnectionHandler;

public class ErrorHandler implements Listener {
   public void message(Map headers, String message) {
      if (headers != null) {
         StompConnectionHandler.printStomp(((String)headers.get("message")).trim(), true);
      } else {
         StompConnectionHandler.printStomp("No header in error message", true);
      }

      if (message != null && !message.isEmpty()) {
         StompConnectionHandler.printStomp(message.trim().replace("\n", "\n[Stomp]"), true);
      }

   }
}
