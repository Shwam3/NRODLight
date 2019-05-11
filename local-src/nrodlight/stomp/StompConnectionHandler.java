package nrodlight.stomp;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.security.auth.login.LoginException;
import net.ser1.stomp.Listener;
import nrodlight.NRODLight;
import nrodlight.stomp.handlers.ErrorHandler;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.stomp.handlers.TRUSTHandler;
import nrodlight.stomp.handlers.VSTPHandler;

public class StompConnectionHandler {
   private static StompClient client;
   private static ScheduledExecutorService executor = null;
   private static int maxTimeoutWait = 300;
   private static int timeoutWait = 10;
   private static int wait = 0;
   public static long lastMessageTimeGeneral = System.currentTimeMillis();
   private static String appID = "";
   private static boolean subscribedTRUST = false;
   private static boolean subscribedVSTP = false;
   private static boolean subscribedTD = false;
   private static final Listener handlerTRUST = TRUSTHandler.getInstance();
   private static final Listener handlerVSTP = VSTPHandler.getInstance();
   private static final Listener handlerTD = TDHandler.getInstance();

   public static boolean connect() throws LoginException, IOException {
      printStomp("v0.4.1 (75) (c) 2005 Sean Russell", false);
      subscribedTRUST = false;
      subscribedVSTP = false;
      subscribedTD = false;
      NRODLight.reloadConfig();
      String username = NRODLight.config.optString("NROD_Username", "");
      String password = NRODLight.config.optString("NROD_Password", "");
      appID = username + "-NRODLight-" + NRODLight.config.optString("NROD_Instance_ID", "uid") + "-v" + "3";
      if (!"".equals(username) && !"".equals(password)) {
         startTimeoutTimer();
         client = new StompClient("datafeeds.networkrail.co.uk", 61618, username, password, appID);
         if (client.isConnected()) {
            printStomp("Connected to \"datafeeds.networkrail.co.uk:61618\"", false);
            printStomp("  ID:       " + appID, false);
            printStomp("  Username: " + username, false);
            printStomp("  Password: " + password, false);
            client.addErrorListener(new ErrorHandler());
            toggleTRUST();
            toggleVSTP();
            toggleTD();

            try {
               Thread.sleep(100L);
            } catch (InterruptedException var3) {
            }

            return true;
         } else {
            printStomp("Could not connect to network rail's servers", true);
            return false;
         }
      } else {
         printStomp("Error retreiving login details (usr: " + username + ", pwd: " + password + ")", true);
         return false;
      }
   }

   public static void disconnect() {
      if (client != null && isConnected() && !isClosed()) {
         client.disconnect();
      }

      subscribedTRUST = false;
      subscribedVSTP = false;
      subscribedTD = false;
      printStomp("Disconnected", false);
   }

   public static boolean isConnected() {
      return client == null ? false : client.isConnected();
   }

   public static boolean isClosed() {
      return client == null ? false : client.isClosed();
   }

   public static boolean isTimedOut() {
      long timeout = System.currentTimeMillis() - lastMessageTimeGeneral;
      return timeout >= getTimeoutThreshold() && getTimeoutThreshold() > 0L;
   }

   private static long getTimeoutThreshold() {
      long threshold;
      if (!subscribedTRUST && !subscribedTD) {
         threshold = 30000L;
      } else {
         threshold = 30000L;
      }

      return threshold;
   }

   public static boolean wrappedConnect() {
      try {
         return connect();
      } catch (LoginException var1) {
         printStomp("Login Exception: " + var1.getLocalizedMessage().split("\n")[0], true);
      } catch (UnknownHostException var2) {
         printStomp("Unable to resolve host (datafeeds.networkrail.co.uk)", true);
      } catch (IOException var3) {
         printStomp("IO Exception:", true);
         NRODLight.printThrowable(var3, "Stomp");
      } catch (Exception var4) {
         printStomp("Exception:", true);
         NRODLight.printThrowable(var4, "Stomp");
      }

      return false;
   }

   private static void startTimeoutTimer() {
      if (executor != null) {
         executor.shutdown();

         try {
            executor.awaitTermination(2L, TimeUnit.SECONDS);
         } catch (InterruptedException var1) {
         }
      }

      executor = Executors.newScheduledThreadPool(1);
      executor.scheduleWithFixedDelay(() -> {
         if (wait >= timeoutWait) {
            wait = 0;
            long time = System.currentTimeMillis() - lastMessageTimeGeneral;
            printStomp(String.format("General Timeout: %02d:%02d:%02d (Threshold: %ss)", time / 3600000L % 24L, time / 60000L % 60L, time / 1000L % 60L, getTimeoutThreshold() / 1000L), isTimedOut() || !isConnected() || isClosed());
            if (!isTimedOut() && isConnected()) {
               timeoutWait = 10;
               long timeTRUST = TRUSTHandler.getInstance().getTimeout();
               long timeVSTP = VSTPHandler.getInstance().getTimeout();
               long timeTD = TDHandler.getInstance().getTimeout();
               boolean timedOutTRUST = timeTRUST >= TRUSTHandler.getInstance().getTimeoutThreshold();
               boolean timedOutVSTP = timeVSTP >= VSTPHandler.getInstance().getTimeoutThreshold();
               boolean timedOutTD = timeTD >= TDHandler.getInstance().getTimeoutThreshold();
               printStomp(String.format("  TRUST Timeout: %02d:%02d:%02d (Threshold: %ss)", timeTRUST / 3600000L % 24L, timeTRUST / 60000L % 60L, timeTRUST / 1000L % 60L, TRUSTHandler.getInstance().getTimeoutThreshold() / 1000L), timedOutTRUST);
               printStomp(String.format("  VSTP Timeout:  %02d:%02d:%02d (Threshold: %ss)", timeVSTP / 3600000L % 24L, timeVSTP / 60000L % 60L, timeVSTP / 1000L % 60L, VSTPHandler.getInstance().getTimeoutThreshold() / 1000L), timedOutVSTP);
               printStomp(String.format("  TD Timeout:    %02d:%02d:%02d (Threshold: %ss)", timeTD / 3600000L % 24L, timeTD / 60000L % 60L, timeTD / 1000L % 60L, TDHandler.getInstance().getTimeoutThreshold() / 1000L), timedOutTD);
               if (!timedOutTRUST && !timedOutVSTP && !timedOutTD) {
                  if (timedOutTRUST) {
                     toggleTRUST();

                     try {
                        Thread.sleep(50L);
                     } catch (InterruptedException var14) {
                     }

                     toggleTRUST();
                  }

                  if (timedOutVSTP) {
                     toggleVSTP();

                     try {
                        Thread.sleep(50L);
                     } catch (InterruptedException var13) {
                     }

                     toggleVSTP();
                  }

                  if (timedOutTD) {
                     toggleTD();

                     try {
                        Thread.sleep(50L);
                     } catch (InterruptedException var12) {
                     }

                     toggleTD();
                  }
               } else if ((double)timeTRUST >= (double)TRUSTHandler.getInstance().getTimeoutThreshold() * 1.5D || (double)timeVSTP >= (double)VSTPHandler.getInstance().getTimeoutThreshold() * 1.5D || (double)timeTD >= (double)TDHandler.getInstance().getTimeoutThreshold() * 1.5D) {
                  if (client != null) {
                     disconnect();
                  }

                  wrappedConnect();
               }

               if (!timedOutTRUST && !timedOutTD) {
                  printStomp("No problems", false);
               }
            } else {
               timeoutWait = Math.min(maxTimeoutWait, timeoutWait + 10);
               printStomp((isTimedOut() ? "Timed Out" : "") + (isTimedOut() && isClosed() ? ", " : "") + (isClosed() ? "Closed" : "") + ((isTimedOut() || isClosed()) && !isConnected() ? " & " : "") + (!isConnected() ? "Disconnected" : "") + " (" + timeoutWait + "s)", true);

               try {
                  if (client != null) {
                     disconnect();
                  }

                  connect();
               } catch (LoginException var15) {
                  printStomp("Login Exception: " + var15.getLocalizedMessage().split("\n")[0], true);
               } catch (IOException var16) {
                  printStomp("IO Exception reconnecting", true);
                  NRODLight.printThrowable(var16, "Stomp");
               } catch (Exception var17) {
                  printStomp("Exception reconnecting", true);
                  NRODLight.printThrowable(var17, "Stomp");
               }
            }
         } else {
            wait += 10;
         }

      }, 10L, 10L, TimeUnit.SECONDS);
   }

   public static void setMaxTimeoutWait(int maxTimeoutWait) {
      StompConnectionHandler.maxTimeoutWait = Math.max(600, maxTimeoutWait);
   }

   public static void printStomp(String message, boolean toErr) {
      if (toErr) {
         NRODLight.printErr("[Stomp] " + message);
      } else {
         NRODLight.printOut("[Stomp] " + message);
      }

   }

   public static String getConnectionName() {
      return appID;
   }

   public static void ack(String ackId) {
      if (client != null) {
         client.ack(ackId);
      }

   }

   public static void toggleTRUST() {
      if (subscribedTRUST) {
         client.unsubscribe("TRUST");
         printStomp("Unsubscribed from \"/topic/TRAIN_MVT_ALL_TOC\" (ID: \"" + appID + "-TRUST\")", false);
         subscribedTRUST = false;
      } else {
         client.subscribe("/topic/TRAIN_MVT_ALL_TOC", "TRUST", handlerTRUST);
         subscribedTRUST = true;
      }

   }

   public static void toggleVSTP() {
      if (subscribedVSTP) {
         client.unsubscribe("VSTP");
         printStomp("Unsubscribed from \"/topic/VSTP_ALL\" (ID: \"" + appID + "-VSTP\")", false);
         subscribedVSTP = false;
      } else {
         client.subscribe("/topic/VSTP_ALL", "VSTP", handlerVSTP);
         subscribedVSTP = true;
      }

   }

   public static void toggleTD() {
      if (subscribedTD) {
         client.unsubscribe("TD");
         printStomp("Unsubscribed from \"/topic/TD_ALL_SIG_AREA\" (ID: \"" + appID + "-TD\")", false);
         subscribedTD = false;
      } else {
         client.subscribe("/topic/TD_ALL_SIG_AREA", "TD", handlerTD);
         subscribedTD = true;
      }

   }

   public static boolean isSubscribedTRUST() {
      return subscribedTRUST;
   }

   public static boolean isSubscribedVSTP() {
      return subscribedVSTP;
   }

   public static boolean isSubscribedTD() {
      return subscribedTD;
   }

   public static void printStompHeaders(Map headers) {
      printStomp(String.format("Message received (%s, id: %s, ack: %s, subscription: %s%s)", String.valueOf(headers.get("destination")).replace("\\c", ":"), String.valueOf(headers.get("message-id")).replace("\\c", ":"), String.valueOf(headers.get("ack")).replace("\\c", ":"), String.valueOf(headers.get("subscription")).replace("\\c", ":"), headers.size() > 7 ? ", + " + (headers.size() - 4) + " more" : ""), false);
   }
}
