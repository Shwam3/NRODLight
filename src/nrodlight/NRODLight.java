package nrodlight;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import nrodlight.stomp.StompConnectionHandler;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.ws.EASMWebSocket;
import org.json.JSONException;
import org.json.JSONObject;

public class NRODLight
{
    public static final String VERSION = "3";

    public static final boolean verbose = false;
    
    public static final File EASM_STORAGE_DIR = new File(System.getProperty("user.home", "C:") + File.separator + ".easigmap");
    public static JSONObject config = new JSONObject();

    public static final SimpleDateFormat sdfTime;
    public static final SimpleDateFormat sdfDate;
    public static final SimpleDateFormat sdfDateTime;

    public  static PrintStream logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
        
    public static final int     port = 8443;
    public static EASMWebSocket webSocket;

    public static PrintStream stdOut = System.out;
    public static PrintStream stdErr = System.err;
    
    static
    {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        
        sdfTime     = new SimpleDateFormat("HH:mm:ss");
        sdfDate     = new SimpleDateFormat("dd/MM/yy");
        sdfDateTime = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
    }
    
    public static void main(String[] args)
    {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) { printThrowable(e, "Look & Feel"); }
        
        Date logDate = new Date();
        logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = sdfDate.format(logDate);

        try
        {
            logStream = new PrintStream(new FileOutputStream(logFile, logFile.length() > 0), true);
            System.setOut(logStream);
            System.setErr(logStream);
        }
        catch (FileNotFoundException e) { printErr("Could not create log file"); printThrowable(e, "Startup"); }
        
        RateMonitor.getInstance(); // Initialises RateMonitor
        printOut("[Main] Starting... (v" + VERSION + ")");
        
        reloadConfig();
        
        try
        {
            File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
            Arrays.stream(TDDataDir.listFiles()).forEach(f ->
            {
                if (f.isFile() && f.getName().endsWith(".td"))
                {
                    String dataStr = "";
                    try(BufferedReader br = new BufferedReader(new FileReader(f)))
                    {
                        dataStr = br.readLine();
                    }
                    catch (IOException ex) { printThrowable(ex, "TD-Startup"); }
                    
                    try
                    {
                        JSONObject data = new JSONObject(dataStr);
                        data.keys().forEachRemaining(k -> TDHandler.DATA_MAP.putIfAbsent(k, data.getString(k)));
                    }
                    catch (JSONException e) { NRODLight.printErr("[TD-Startup] Malformed JSON in " + f.getName()); }
                }
            });
        }
        catch (Exception e) { NRODLight.printThrowable(e, "TD-Startup"); }
        
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            printOut("[Main] Stopping...");
            
            if (webSocket != null)
            {
                try { webSocket.stop(1000); }
                catch (Throwable t) {}
            }
            
            StompConnectionHandler.disconnect();
        }, "NRODShutdown"));
        
        ensureServerOpen();
        
        if (StompConnectionHandler.wrappedConnect())
            StompConnectionHandler.printStomp("Initialised and working", false);
        else
            StompConnectionHandler.printStomp("Unble to start", true);

        Timer FullUpdateMessenger = new Timer("FullUpdateMessenger");
        FullUpdateMessenger.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                try
                {
                    JSONObject message = new JSONObject();
                    JSONObject content = new JSONObject();
                    content.put("type", "SEND_ALL");
                    content.put("timestamp", System.currentTimeMillis());
                    content.put("message", TDHandler.DATA_MAP);
                    message.put("Message", content);
                    String messageStr = message.toString();

                    ensureServerOpen();
                    if (webSocket != null)
                    {
                        webSocket.getConnections().stream()
                            .filter(c -> c != null)
                            .filter(c -> c.isOpen())
                            .forEach(c -> c.send(messageStr));
                        EASMWebSocket.printWebSocket("Updated all clients", false);
                    }
                }
                catch (Exception e) { printThrowable(e, "SendAll"); }
            }
        }, 500, 60000);
    }

    //<editor-fold defaultstate="collapsed" desc="Print methods">
    public static void printThrowable(Throwable t, String name)
    {
        name = name == null ? "" : name;
        
        StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        name += (name.isEmpty() ? "" : " ");
        name += caller.getFileName() != null && caller.getLineNumber() >= 0 ?
          "(" + caller.getFileName() + ":" + caller.getLineNumber() + ")" :
          (caller.getFileName() != null ?  "("+caller.getFileName()+")" : "(Unknown Source)");
            
        printErr("[" + name + "] " + t.toString());

        for (StackTraceElement element : t.getStackTrace())
            printErr("[" + name + "] -> " + element.toString());

        for (Throwable sup : t.getSuppressed())
            printThrowable0(sup, name);

        printThrowable0(t.getCause(), name);
    }

    private static void printThrowable0(Throwable t, String name)
    {
        if (t != null)
        {
            printErr((name != null && !name.isEmpty() ? "[" + name + "] " : "") + t.toString());

            for (StackTraceElement element : t.getStackTrace())
                printErr((name != null && !name.isEmpty() ? "[" + name + "] -> " : " -> ") + element.toString());
        }
    }

    public static void printOut(String message)
    {
        if (message != null && !message.equals(""))
            if (!message.contains("\n"))
                print("[" + sdfDateTime.format(new Date()) + "] " + message, false);
            else
                for (String msgPart : message.split("\n"))
                    print("[" + sdfDateTime.format(new Date()) + "] " + msgPart, false);
    }

    public static void printErr(String message)
    {
        if (message != null && !message.equals(""))
            if (!message.contains("\n"))
                print("[" + sdfDateTime.format(new Date()) + "] !!!> " + message + " <!!!", false);
            else
                for (String msgPart : message.split("\n"))
                    print("[" + sdfDateTime.format(new Date()) + "] !!!> " + msgPart + " <!!!", true);
    }

    private static synchronized void print(String message, boolean toErr)
    {
        if (toErr)
            stdErr.println(message);
        else
            stdOut.println(message);

        filePrint(message);
    }

    private static synchronized void filePrint(String message)
    {
        Date logDate = new Date();
        if (!lastLogDate.equals(sdfDate.format(logDate)))
        {
            logStream.flush();
            logStream.close();

            lastLogDate = sdfDate.format(logDate);

            logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + lastLogDate.replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintStream(new FileOutputStream(logFile, true));

                System.setOut(logStream);
                System.setErr(logStream);
            }
            catch (IOException e) { printErr("Could not create log file"); printThrowable(e, "Logging"); }
        }

        logStream.println(message);
    }
    //</editor-fold>

    private static void ensureServerOpen()
    {
        if (webSocket == null || webSocket.isClosed())
        {
            new Thread(() -> {
                EASMWebSocket ews = new EASMWebSocket(port, true);
                try
                {
                    if (webSocket != null) webSocket.stop();
                    webSocket = ews;
                    webSocket.run();
                }
                catch (Exception e)
                {
                    printThrowable(e, "WebSocket");
                }
                finally
                {
                    EASMWebSocket.printWebSocket("WebSocket server runnable finished" + (ews.isClosed() ? "" : " unnexpectedly"), !ews.isClosed());
                    
                    if (ews == webSocket)
                    {
                        try
                        {
                            webSocket = null;
                            ews.stop(0);
                        }
                        catch (Exception e) {}
                    }
                }
            }).start();
        }
    }
    
    public static void reloadConfig()
    {
        try (BufferedReader br = new BufferedReader(new FileReader(new File(EASM_STORAGE_DIR, "config.json"))))
        {
            StringBuilder sb = new StringBuilder();
            
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            
            JSONObject obj = new JSONObject(sb.toString());
            config = obj;
        } catch (IOException | JSONException e) { NRODLight.printThrowable(e, "Config"); }
    }
}
