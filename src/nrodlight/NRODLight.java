package nrodlight;

import nrodlight.db.DBHandler;
import nrodlight.stomp.StompConnectionHandler;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.ws.EASMWebSocket;
import nrodlight.ws.EASMWebSocketImpl;
import org.java_websocket.WebSocket;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class NRODLight
{
    public static final String VERSION = "3";

    public static final boolean verbose = false;
    public static final AtomicBoolean STOP = new AtomicBoolean(false);

    public static final File EASM_STORAGE_DIR = new File(System.getProperty("user.home", "C:") + File.separator + ".easigmap");
    public static JSONObject config = new JSONObject();

    public static final SimpleDateFormat sdfTime;
    public static final SimpleDateFormat sdfDate;
    public static final SimpleDateFormat sdfDateTime;

    private static PrintStream logStream;
    private static File        logFile;
    private static String      lastLogDate = "";

    public static EASMWebSocket webSocket;

    private static PrintStream stdOut = System.out;
    private static PrintStream stdErr = System.err;

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
        try { DBHandler.getConnection(); }
        catch (SQLException ex) { printThrowable(ex, "Startup"); }

        try
        {
            File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
            Arrays.stream(TDDataDir.listFiles())
                .filter(File::isFile)
                .filter(File::canRead)
                .filter(f -> f.getName().endsWith(".td"))
                .forEach(f ->
                {
                    try
                    {
                        JSONObject data = new JSONObject(new String(Files.readAllBytes(f.toPath())));
                        data.keys().forEachRemaining(k -> TDHandler.DATA_MAP.putIfAbsent(k, data.getString(k)));
                    }
                    catch (IOException e) { NRODLight.printErr("[TD-Startup] Cannot read " + f.getName()); }
                    catch (JSONException e) { NRODLight.printErr("[TD-Startup] Malformed JSON in " + f.getName()); }
                });
            printOut("[Startup] Finished reading TD data");
        }
        catch (Exception e) { NRODLight.printThrowable(e, "TD-Startup"); }

        try
        {
            Connection conn = DBHandler.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT train_id,train_id_current,a.schedule_uid,start_timestamp,"
                + "current_delay,next_expected_update,off_route,tds FROM activations a INNER JOIN schedules s ON a.schedule_uid = s.schedule_uid AND "
                + "a.stp_indicator = s.stp_indicator AND a.schedule_date_from = s.date_from WHERE last_update > ? AND "
                + "(finished = 0 OR last_update > ?) AND cancelled = 0 AND tds != ''");
            ps.setLong(1, System.currentTimeMillis() - 43200000L); // 12 hours
            ps.setLong(2, System.currentTimeMillis() - 1800000L); // 30 mins
            ResultSet r = ps.executeQuery();

            String[] columns = {"train_id","train_id_current","schedule_uid","start_timestamp","current_delay","next_expected_update","off_route","tds"};
            List<JSONObject> resultData = new ArrayList<>();
            while (r.next())
            {
                JSONObject jobj = new JSONObject();

                for (int i = 0; i < columns.length; i++)
                    jobj.put(columns[i], r.getObject(i+1));

                resultData.add(jobj);
            }
            r.close();
            ps.close();

            JSONObject delayData = new JSONObject();
            for (JSONObject obj : resultData)
            {
                for (String td : obj.getString("tds").split(","))
                {
                    obj.remove("tds");
                    if (!delayData.has(td))
                        delayData.put(td, new JSONArray());
                    delayData.getJSONArray(td).put(obj);
                }
            }
            JSONObject message = new JSONObject();
            JSONObject content = new JSONObject();
            content.put("type", "DELAYS");
            content.put("timestamp", "%time%");
            content.put("message", delayData);
            message.put("Message", content);

            EASMWebSocket.setDelayData(message.toString());
            printOut("[Startup] Got delay data");
        }
        catch (SQLException sqlex) { printThrowable(sqlex, "Startup-Delays"); }
        catch (Exception e) { printThrowable(e, "Startup-Delays"); }

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            printOut("[Main] Stopping...");
            STOP.getAndSet(true);

            if (webSocket != null)
            {
                try { webSocket.stop(1000); }
                catch (Throwable t) {}
            }

            StompConnectionHandler.disconnect();

            DBHandler.closeConnection();
        }, "NRODShutdown"));

        new Thread(() ->
        {
            printOut("[Startup] Config change watcher started");

            final Path configPath = new File(EASM_STORAGE_DIR, "config.json").toPath();

            try (final WatchService watchService = FileSystems.getDefault().newWatchService())
            {
                final WatchKey watchKey = EASM_STORAGE_DIR.toPath().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                while (!STOP.get())
                {
                    final WatchKey wk = watchService.take();
                    for (WatchEvent<?> event : wk.pollEvents())
                    {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY && configPath.equals(event.context()))
                        {
                            reloadConfig();
                            break;
                        }
                    }
                    wk.reset();
                }
                watchKey.reset();
            }
            catch (IOException e) { printThrowable(e, "ConfigChangeWatcher"); }
            catch (InterruptedException ignored) {}
        }, "ConfigChangeWatcher").start();

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

                    Map<String, JSONObject> splitMessages = new HashMap<>();
                    Map<String, String> splitMessagesStr = new HashMap<>();
                    TDHandler.DATA_MAP.forEach((k,v) ->
                    {
                        JSONObject obj = splitMessages.get(k.substring(0, 2));
                        if (obj == null)
                        {
                            obj = new JSONObject();
                            splitMessages.put(k.substring(0, 2), obj);
                        }
                        obj.put(k, v);
                    });
                    content.remove("message");
                    content.put("timestamp", System.currentTimeMillis());
                    splitMessages.forEach((k,v) ->
                    {
                        content.put("message", v);
                        content.put("td_area", k);
                        splitMessagesStr.put(k, message.toString());
                    });

                    ensureServerOpen();
                    if (webSocket != null)
                    {
                        webSocket.getConnections().stream()
                            .filter(Objects::nonNull)
                            .filter(WebSocket::isOpen)
                            .filter(c -> c instanceof EASMWebSocketImpl)
                            .map(EASMWebSocketImpl.class::cast)
                            .forEach(c ->
                            {
                                if (c.optSplitFullMessages())
                                    c.sendSplit(splitMessagesStr);
                                else
                                    c.send(messageStr);
                            });
                        EASMWebSocket.printWebSocket("Updated all clients", false);
                    }

                    try
                    {
                        Connection conn = DBHandler.getConnection();
                        PreparedStatement ps = conn.prepareStatement("SELECT train_id,train_id_current,a.schedule_uid,start_timestamp,current_delay,"
                            + "next_expected_update,off_route,tds FROM activations a INNER JOIN schedules s ON a.schedule_uid = s.schedule_uid AND "
                            + "a.stp_indicator = s.stp_indicator AND a.schedule_date_from = s.date_from WHERE last_update > ? AND "
                            + "(finished = 0 OR last_update > ?) AND cancelled = 0 AND tds != ''");
                        ps.setLong(1, System.currentTimeMillis() - 43200000L); // 12 hours
                        ps.setLong(2, System.currentTimeMillis() - 1800000L); // 30 mins
                        ResultSet r = ps.executeQuery();

                        String[] columns = {"train_id","train_id_current","schedule_uid","start_timestamp","current_delay","next_expected_update","off_route","tds"};
                        List<JSONObject> resultData = new ArrayList<>();
                        while (r.next())
                        {
                            JSONObject jobj = new JSONObject();

                            for (int i = 0; i < columns.length; i++)
                                jobj.put(columns[i], r.getObject(i+1));

                            resultData.add(jobj);
                        }
                        r.close();
                        ps.close();

                        JSONObject delayData = new JSONObject();
                        for (JSONObject obj : resultData)
                        {
                            for (String td : obj.getString("tds").split(","))
                            {
                                obj.remove("tds");
                                if (!delayData.has(td))
                                    delayData.put(td, new JSONArray());
                                delayData.getJSONArray(td).put(obj);
                            }
                        }
                        content.put("type", "DELAYS");
                        content.put("timestamp", "%time%");
                        content.put("message", delayData);
                        message.put("Message", content);

                        EASMWebSocket.setDelayData(message.toString());

                        if (webSocket != null)
                        {
                            content.put("timestamp", System.currentTimeMillis());
                            final String delayDataStr = message.toString();
                            webSocket.getConnections().stream()
                                    .filter(Objects::nonNull)
                                    .filter(WebSocket::isOpen)
                                    .filter(c -> c instanceof EASMWebSocketImpl)
                                    .map(EASMWebSocketImpl.class::cast)
                                    .filter(EASMWebSocketImpl::optDelayColouration)
                                    .forEach(c -> c.send(delayDataStr));
                        }

                    }
                    catch (SQLException sqlex) { printThrowable(sqlex, "SendAll-SQL"); }
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
                EASMWebSocket ews = new EASMWebSocket();
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
                        catch (InterruptedException e) {}
                    }
                }
            }, "WebSocket").start();
        }
    }

    public static void reloadConfig()
    {
        try
        {
            String configContents = new String(Files.readAllBytes(new File(EASM_STORAGE_DIR, "config.json").toPath()));

            JSONObject newConfig = new JSONObject(configContents);

            if (config != null && config.has("WSPort") && newConfig.has("WSPort")
                    && config.optInt("WSPort") != newConfig.optInt("WSPort"))
            {
                try { webSocket.stop(0); }
                catch (InterruptedException ignored) {}

                ensureServerOpen();
            }
            config = newConfig;
        }
        catch (IOException | JSONException e)
        {
            NRODLight.printThrowable(e, "ConfigLoad");
        }
    }
}
