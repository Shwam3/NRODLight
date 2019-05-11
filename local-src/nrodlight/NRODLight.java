package nrodlight;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import nrodlight.db.DBHandler;
import nrodlight.stomp.StompConnectionHandler;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.ws.EASMWebSocket;
import nrodlight.ws.EASMWebSocketImpl;
import org.java_websocket.WebSocket;
import org.json.JSONException;
import org.json.JSONObject;

public class NRODLight {
   public static final String VERSION = "3";
   public static final boolean verbose = false;
   public static final AtomicBoolean STOP = new AtomicBoolean(false);
   public static final File EASM_STORAGE_DIR;
   public static JSONObject config;
   public static final SimpleDateFormat sdfTime;
   public static final SimpleDateFormat sdfDate;
   public static final SimpleDateFormat sdfDateTime;
   private static PrintStream logStream;
   private static File logFile;
   private static String lastLogDate;
   public static EASMWebSocket webSocket;
   private static PrintStream stdOut;
   private static PrintStream stdErr;

   public static void main(String[] args) {
      try {
         UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException | ClassNotFoundException var8) {
         printThrowable(var8, "Look & Feel");
      }

      Date logDate = new Date();
      logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + sdfDate.format(logDate).replace("/", "-") + ".log");
      logFile.getParentFile().mkdirs();
      lastLogDate = sdfDate.format(logDate);

      try {
         logStream = new PrintStream(new FileOutputStream(logFile, logFile.length() > 0L), true);
         System.setOut(logStream);
         System.setErr(logStream);
      } catch (FileNotFoundException var7) {
         printErr("Could not create log file");
         printThrowable(var7, "Startup");
      }

      RateMonitor.getInstance();
      printOut("[Main] Starting... (v3)");
      reloadConfig();

      try {
         DBHandler.getConnection();
      } catch (SQLException var6) {
         printThrowable(var6, "Startup");
      }

      try {
         File TDDataDir = new File(EASM_STORAGE_DIR, "TDData");
         Arrays.stream(TDDataDir.listFiles()).filter(File::isFile).filter(File::canRead).filter((f) -> {
            return f.getName().endsWith(".td");
         }).forEach((f) -> {
            try {
               JSONObject data = new JSONObject(new String(Files.readAllBytes(f.toPath())));
               data.keys().forEachRemaining((k) -> {
                  String var10000 = (String)TDHandler.DATA_MAP.putIfAbsent(k, data.getString(k));
               });
            } catch (IOException var2) {
               printErr("[TD-Startup] Cannot read " + f.getName());
            } catch (JSONException var3) {
               printErr("[TD-Startup] Malformed JSON in " + f.getName());
            }

         });
         printOut("[Startup] Finished reading TD data");
      } catch (Exception var5) {
         printThrowable(var5, "TD-Startup");
      }

      try {
         EASMWebSocket.updateDelayData();
      } catch (SQLException var3) {
         printThrowable(var3, "Startup-Delays");
      } catch (Exception var4) {
         printThrowable(var4, "Startup-Delays");
      }

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         printOut("[Main] Stopping...");
         STOP.getAndSet(true);
         if (webSocket != null) {
            try {
               webSocket.stop(1000);
            } catch (Throwable var1) {
            }
         }

         StompConnectionHandler.disconnect();
         DBHandler.closeConnection();
      }, "NRODShutdown"));
      (new Thread(() -> {
         printOut("[Startup] File watcher started");
         Path configPath = (new File(EASM_STORAGE_DIR, "config.json")).toPath();

         try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Throwable var2 = null;

            try {
               WatchKey watchKey = EASM_STORAGE_DIR.toPath().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

               while(!STOP.get()) {
                  WatchKey wk = watchService.take();
                  Iterator var5 = wk.pollEvents().iterator();

                  while(var5.hasNext()) {
                     WatchEvent event = (WatchEvent)var5.next();
                     if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY && configPath.equals(event.context())) {
                        reloadConfig();
                     }
                  }

                  wk.reset();
               }

               watchKey.reset();
            } catch (Throwable var16) {
               var2 = var16;
               throw var16;
            } finally {
               if (watchService != null) {
                  if (var2 != null) {
                     try {
                        watchService.close();
                     } catch (Throwable var15) {
                        var2.addSuppressed(var15);
                     }
                  } else {
                     watchService.close();
                  }
               }

            }
         } catch (IOException var18) {
            printThrowable(var18, "FileChangeWatcher");
         } catch (InterruptedException var19) {
         }

      }, "FileChangeWatcher")).start();
      ensureServerOpen();
      if (StompConnectionHandler.wrappedConnect()) {
         StompConnectionHandler.printStomp("Initialised and working", false);
      } else {
         StompConnectionHandler.printStomp("Unble to start", true);
      }

      Timer FullUpdateMessenger = new Timer("FullUpdateMessenger");
      FullUpdateMessenger.schedule(new TimerTask() {
         public void run() {
            try {
               JSONObject message = new JSONObject();
               JSONObject content = new JSONObject();
               content.put("type", "SEND_ALL");
               content.put("timestamp", System.currentTimeMillis());
               content.put("message", TDHandler.DATA_MAP);
               message.put("Message", content);
               String messageStr = message.toString();
               Map splitMessages = new HashMap();
               Map splitMessagesStr = new HashMap();
               TDHandler.DATA_MAP.forEach((k, v) -> {
                  JSONObject obj = (JSONObject)splitMessages.get(k.substring(0, 2));
                  if (obj == null) {
                     obj = new JSONObject();
                     splitMessages.put(k.substring(0, 2), obj);
                  }

                  obj.put(k, v);
               });
               content.remove("message");
               content.put("timestamp", System.currentTimeMillis());
               splitMessages.forEach((k, v) -> {
                  content.put("message", v);
                  content.put("td_area", k);
                  splitMessagesStr.put(k, message.toString());
               });
               NRODLight.ensureServerOpen();
               Stream var10000;
               if (NRODLight.webSocket != null) {
                  var10000 = NRODLight.webSocket.getConnections().stream().filter(Objects::nonNull).filter(WebSocket::isOpen).filter((c) -> {
                     return c instanceof EASMWebSocketImpl;
                  });
                  EASMWebSocketImpl.class.getClass();
                  var10000.map(EASMWebSocketImpl.class::cast).forEach((c) -> {
                     if (c.optSplitFullMessages()) {
                        c.sendSplit(splitMessagesStr);
                     } else {
                        c.send(messageStr);
                     }

                  });
                  EASMWebSocket.printWebSocket("Updated all clients", false);
               }

               try {
                  JSONObject delayData = EASMWebSocket.updateDelayData();
                  if (NRODLight.webSocket != null) {
                     var10000 = NRODLight.webSocket.getConnections().stream().filter(Objects::nonNull).filter(WebSocket::isOpen).filter((c) -> {
                        return c instanceof EASMWebSocketImpl;
                     });
                     EASMWebSocketImpl.class.getClass();
                     var10000.map(EASMWebSocketImpl.class::cast).filter(EASMWebSocketImpl::optDelayColouration).forEach((c) -> {
                        c.sendDelayData(delayData);
                     });
                  }
               } catch (SQLException var7) {
                  NRODLight.printThrowable(var7, "SendAll-Delays");
               }
            } catch (Exception var8) {
               NRODLight.printThrowable(var8, "SendAll");
            }

         }
      }, 500L, 30000L);
   }

   public static void printThrowable(Throwable t, String name) {
      name = name == null ? "" : name;
      StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
      name = name + (name.isEmpty() ? "" : " ");
      name = name + (caller.getFileName() != null && caller.getLineNumber() >= 0 ? "(" + caller.getFileName() + ":" + caller.getLineNumber() + ")" : (caller.getFileName() != null ? "(" + caller.getFileName() + ")" : "(Unknown Source)"));
      printErr("[" + name + "] " + t.toString());
      StackTraceElement[] var3 = t.getStackTrace();
      int var4 = var3.length;

      int var5;
      for(var5 = 0; var5 < var4; ++var5) {
         StackTraceElement element = var3[var5];
         printErr("[" + name + "] -> " + element.toString());
      }

      Throwable[] var7 = t.getSuppressed();
      var4 = var7.length;

      for(var5 = 0; var5 < var4; ++var5) {
         Throwable sup = var7[var5];
         printThrowable0(sup, name);
      }

      printThrowable0(t.getCause(), name);
   }

   private static void printThrowable0(Throwable t, String name) {
      if (t != null) {
         printErr((name != null && !name.isEmpty() ? "[" + name + "] " : "") + t.toString());
         StackTraceElement[] var2 = t.getStackTrace();
         int var3 = var2.length;

         for(int var4 = 0; var4 < var3; ++var4) {
            StackTraceElement element = var2[var4];
            printErr((name != null && !name.isEmpty() ? "[" + name + "] -> " : " -> ") + element.toString());
         }
      }

   }

   public static void printOut(String message) {
      if (message != null && !message.equals("")) {
         if (!message.contains("\n")) {
            print("[" + sdfDateTime.format(new Date()) + "] " + message, false);
         } else {
            String[] var1 = message.split("\n");
            int var2 = var1.length;

            for(int var3 = 0; var3 < var2; ++var3) {
               String msgPart = var1[var3];
               print("[" + sdfDateTime.format(new Date()) + "] " + msgPart, false);
            }
         }
      }

   }

   public static void printErr(String message) {
      if (message != null && !message.equals("")) {
         if (!message.contains("\n")) {
            print("[" + sdfDateTime.format(new Date()) + "] !!!> " + message + " <!!!", false);
         } else {
            String[] var1 = message.split("\n");
            int var2 = var1.length;

            for(int var3 = 0; var3 < var2; ++var3) {
               String msgPart = var1[var3];
               print("[" + sdfDateTime.format(new Date()) + "] !!!> " + msgPart + " <!!!", true);
            }
         }
      }

   }

   private static synchronized void print(String message, boolean toErr) {
      if (toErr) {
         stdErr.println(message);
      } else {
         stdOut.println(message);
      }

      filePrint(message);
   }

   private static synchronized void filePrint(String message) {
      Date logDate = new Date();
      if (!lastLogDate.equals(sdfDate.format(logDate))) {
         logStream.flush();
         logStream.close();
         lastLogDate = sdfDate.format(logDate);
         logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + lastLogDate.replace("/", "-") + ".log");
         logFile.getParentFile().mkdirs();

         try {
            logFile.createNewFile();
            logStream = new PrintStream(new FileOutputStream(logFile, true));
            System.setOut(logStream);
            System.setErr(logStream);
         } catch (IOException var3) {
            printErr("Could not create log file");
            printThrowable(var3, "Logging");
         }
      }

      logStream.println(message);
   }

   private static void ensureServerOpen() {
      if (webSocket == null || webSocket.isClosed()) {
         (new Thread(() -> {
            EASMWebSocket ews = new EASMWebSocket();

            try {
               if (webSocket != null) {
                  webSocket.stop();
               }

               webSocket = ews;
               webSocket.run();
            } catch (Exception var10) {
               printThrowable(var10, "WebSocket");
            } finally {
               EASMWebSocket.printWebSocket("WebSocket server runnable finished" + (ews.isClosed() ? "" : " unnexpectedly"), !ews.isClosed());
               if (ews == webSocket) {
                  try {
                     webSocket = null;
                     ews.stop(0);
                  } catch (InterruptedException var9) {
                  }
               }

            }

         }, "WebSocket")).start();
      }

   }

   public static void reloadConfig() {
      try {
         String configContents = new String(Files.readAllBytes((new File(EASM_STORAGE_DIR, "config.json")).toPath()));
         JSONObject newConfig = new JSONObject(configContents);
         if (config != null && config.has("WSPort") && newConfig.has("WSPort") && config.optInt("WSPort") != newConfig.optInt("WSPort")) {
            try {
               webSocket.stop(0);
            } catch (InterruptedException var3) {
            }

            ensureServerOpen();
         }

         config = newConfig;
      } catch (JSONException | IOException var4) {
         printThrowable(var4, "ConfigLoad");
      }

   }

   static {
      EASM_STORAGE_DIR = new File(System.getProperty("user.home", "C:") + File.separator + ".easigmap");
      config = new JSONObject();
      lastLogDate = "";
      stdOut = System.out;
      stdErr = System.err;
      TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
      sdfTime = new SimpleDateFormat("HH:mm:ss");
      sdfDate = new SimpleDateFormat("dd/MM/yy");
      sdfDateTime = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
   }
}
