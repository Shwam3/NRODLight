package nrodlight;

import nrodlight.db.DBHandler;
import nrodlight.stepping.Stepping;
import nrodlight.stomp.ConnectionManager;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.ws.EASMWebSocket;
import nrodlight.ws.EASMWebSocketImpl;
import org.java_websocket.WebSocket;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.jms.JMSException;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NRODLight
{
    public static final String VERSION = "3";

    public static final boolean verbose = false;
    public static final AtomicBoolean STOP = new AtomicBoolean(false);

    public static File EASM_STORAGE_DIR = new File(System.getProperty("user.home", System.getProperty("user.dir", "C:")), ".easigmap");
    public static JSONObject config = new JSONObject();

    public static final SimpleDateFormat sdfDate;
    public static final SimpleDateFormat sdfDateTime;

    private static PrintStream logStream;
    private static File        logFile;
    private static String      lastLogDate = "";

    public static EASMWebSocket webSocket;

    private static final PrintStream stdOut = System.out;
    private static final PrintStream stdErr = System.err;

    private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4,
            new SigmapsThreadFactory("SigmapsMainExecutor"));

    static
    {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));

        //sdfTime     = new SimpleDateFormat("HH:mm:ss");
        sdfDate     = new SimpleDateFormat("dd/MM/yy");
        sdfDateTime = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
    }

    public static void main(String[] args)
    {
        if (args.length >= 1)
        {
            File storageDir = new File(args[0]);
            if (storageDir.exists() && storageDir.isDirectory())
                EASM_STORAGE_DIR = storageDir;
        }
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "[dd/MM/yy HH:mm:ss]");

        String logDate = sdfDate.format(new Date());
        logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + logDate.replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = logDate;

        try
        {
            logStream = new PrintStream(new FileOutputStream(logFile, logFile.length() > 0), true);
            System.setOut(new DoublePrintStream(System.out, logStream));
            System.setErr(new DoublePrintStream(System.err, logStream));
        }
        catch (FileNotFoundException e) { printErr("Could not create log file"); printThrowable(e, "Startup"); }

        printOut("[Startup] Starting... (v" + VERSION + ")", true);
        if (executor instanceof ScheduledThreadPoolExecutor)
        {
            ScheduledThreadPoolExecutor exec = (ScheduledThreadPoolExecutor)executor;
            exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            exec.setRemoveOnCancelPolicy(true);
        }

        printOut("[Startup] Loading config", true);
        reloadConfig();

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
        {
            String thread = String.format("%s-%s", t.getName(), t.getId());
            printThrowable(e, thread);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (PrintWriter pw = new PrintWriter(baos, true))
            {
                pw.println("Error on thread \"" + thread + "\":");
                pw.println("<pre>");
                e.printStackTrace(pw);
                pw.println("</pre>");
            }

            emailUpdate("Sigmaps Error - " + e, baos.toString(), true);
        });
        RateMonitor.getInstance(); // Initialises RateMonitor

        printOut("[Startup] Loading stepping config", true);
        Stepping.load();

        String date = sdfDateTime.format(new Date());
        emailUpdate("Sigmaps Startup - " + date, "Sigmaps starting at " + date, false);

        try { DBHandler.getConnection(); } // Initialise database connection
        catch (SQLException ex) { printErr("[Startup] Exception connecting to database: " + ex); }

        try
        {
            final AtomicInteger count = new AtomicInteger(-1);
            final File[] TDDataFiles = new File(EASM_STORAGE_DIR, "TDData").listFiles();
            if (TDDataFiles != null)
            {
                count.incrementAndGet();
                Arrays.stream(TDDataFiles)
                        .filter(File::isFile)
                        .filter(File::canRead)
                        .filter(f -> f.getName().endsWith(".td"))
                        .sorted(
                                Comparator.<File, String>comparing(f -> f.getName().split("\\.", 2)[1]).reversed()
                                .thenComparing(f -> !f.getName().split("\\.", 2)[0].isEmpty())
                                .thenComparing(f -> f.getName().split("\\.", 2)[0])
                        )
                        .forEach(f -> readTDDataFile(f, false, count));
            }
            printOut("[Startup] Finished reading TD data, read " + count + " files", true);
        }
        catch (Exception e) { NRODLight.printThrowable(e, "Startup"); }

        executor.execute(() -> {
            try {
                EASMWebSocket.updateDelayData();
            } catch (SQLException ex) {
                printErr("[Startup] Exception updating delays: " + ex);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            printOut("[Main] Stopping...", true);

            STOP.getAndSet(true);

            executor.shutdown();

            ConnectionManager.stop();

            if (webSocket != null)
            {
                try { webSocket.stop(1000); }
                catch (Throwable ignored) {}
            }

            ((TDHandler) TDHandler.getInstance()).saveTDDataFull(true);

            RateMonitor.getInstance().commitData();

            DBHandler.closeConnection();

            printOut("[Main] Stopped", true);
        }, "NRODShutdown"));

        Thread fileWatcher = new Thread(() -> {
            printOut("[Startup] File watcher started", true);

            final Path configPath = new File(EASM_STORAGE_DIR, "config.json").toPath();
            final Path manualTDPath = new File(EASM_STORAGE_DIR, "set_data.json").toPath();
            final Path steppingFile = new File(EASM_STORAGE_DIR, "steps.json").toPath();

            try (final WatchService watchService = FileSystems.getDefault().newWatchService())
            {
                final WatchKey watchKey = EASM_STORAGE_DIR.toPath().register(watchService,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

                search:
                for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet())
                {
                    for (StackTraceElement s : entry.getValue())
                        if ("sun.nio.fs.LinuxWatchService".equals(s.getClassName()))
                        {
                            entry.getKey().setName("LinuxFileChangeWatcher");
                            break search;
                        }
                }

                while (!STOP.get())
                {
                    final WatchKey wk = watchService.take();
                    for (WatchEvent<?> event : wk.pollEvents())
                    {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
                                event.kind() == StandardWatchEventKinds.ENTRY_MODIFY &&
                                        event.context() instanceof Path && configPath.endsWith((Path) event.context()))
                        {
                            printOut("[Config] Reloading config", true);
                            reloadConfig();
                        }
                        else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
                                event.kind() == StandardWatchEventKinds.ENTRY_MODIFY &&
                                        event.context() instanceof Path && manualTDPath.endsWith((Path) event.context()))
                        {
                            try
                            {
                                JSONObject data = new JSONObject(new String(Files.readAllBytes(manualTDPath)));
                                final Map<String, String> updateMap = new HashMap<>();
                                data.keys().forEachRemaining(d -> {
                                    String value = data.optString(d, null);
                                    if (d.length() == 6 && (value == null || value.isEmpty() || "0".equals(value) || "1".equals(value) || value.length() == 4))
                                        updateMap.put(d, value);
                                });

                                printOut("[TD] Manual TD input received, " + updateMap.size() + " changes", true);
                                TDHandler.updateClientsAndSave(updateMap);

                                Files.deleteIfExists(manualTDPath);
                            }
                            catch (JSONException | IOException ex) { printThrowable(ex, "TD"); }
                        }
                        else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
                                event.kind() == StandardWatchEventKinds.ENTRY_MODIFY &&
                                        event.context() instanceof Path && steppingFile.endsWith((Path) event.context()))
                        {
                            printOut("[Stepping] Reloading stepping config", true);
                            Stepping.load();
                        }
                    }
                    wk.reset();
                }
                watchKey.reset();
            }
            catch (IOException e) { printThrowable(e, "FileChangeWatcher"); }
            catch (InterruptedException ignored) {}
        }, "FileChangeWatcher");
        fileWatcher.setDaemon(true);
        fileWatcher.start();

        ensureServerOpen();

        try {
            ConnectionManager.start();
        }
        catch (JMSException ex) {
            printThrowable(ex, "ActiveMQ");
        }

        executor.scheduleAtFixedRate(() -> {
            try {
                JSONObject message = new JSONObject();
                JSONObject content = new JSONObject();
                content.put("type", "SEND_ALL");
                content.put("messageID", "%nextid%");
                content.put("timestamp", System.currentTimeMillis());
                content.put("message", TDHandler.DATA_MAP);
                message.put("Message", content);
                String messageStr = message.toString();

                Map<String, JSONObject> splitMessages = new HashMap<>();
                Map<String, String> splitMessagesStr = new HashMap<>();
                TDHandler.DATA_MAP.forEach((k, v) -> {
                    JSONObject obj = Optional.ofNullable(splitMessages.get(k.substring(0, 2))).orElseGet(JSONObject::new);
                    splitMessages.putIfAbsent(k.substring(0, 2), obj);
                    obj.put(k, v);
                });
                content.remove("message");
                content.put("timestamp", System.currentTimeMillis());
                splitMessages.forEach((k, v) -> {
                    content.put("message", v);
                    content.put("td_area", k);
                    splitMessagesStr.put(k, message.toString());
                });

                ensureServerOpen();
                if (webSocket != null) {
                    try {
                        EASMWebSocket.updateDelayData();
                    } catch (SQLException ex) {
                        printErr("[Delays] Exception updating delays: " + ex);
                    }

                    final JSONObject delayData = EASMWebSocket.getDelayData();

                    for (WebSocket ws : webSocket.getConnections()) {
                        try {
                            if (ws != null && ws.isOpen() && ws instanceof EASMWebSocketImpl) {
                                EASMWebSocketImpl wsEASM = (EASMWebSocketImpl) ws;
                                wsEASM.sendDelayData(delayData);
                                if (!wsEASM.optMessageIDs() && wsEASM.isOpen()) {
                                    if (wsEASM.optSplitFullMessages())
                                        wsEASM.sendSplit(splitMessagesStr);
                                    else
                                        wsEASM.send(messageStr);
                                }
                            }
                        } catch (Exception e) {
                            printThrowable(e, "SendAll");
                        }
                    }
                }
            } catch (Exception e) {
                printThrowable(e, "SendAll");
            }
        }, 500, 30000, TimeUnit.MILLISECONDS);

        executor.scheduleAtFixedRate(() -> {
            try {
                ((TDHandler) TDHandler.getInstance()).saveTDDataFull(false);
            } catch (Exception ex) {
                printThrowable(ex, "[TD]");
            }
        }, 5000, 5000, TimeUnit.MILLISECONDS);
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
            printErr((name != null && !name.isEmpty() ? "[" + name + "] " : "") + t);

            for (StackTraceElement element : t.getStackTrace())
                printErr((name != null && !name.isEmpty() ? "[" + name + "] -> " : " -> ") + element.toString());
        }
    }

    public static void printOut(String message)
    {
        printOut(message, false);
    }

    public static void printOut(String message, boolean forceStdout)
    {
        if (message != null && !message.equals(""))
            if (!message.contains("\n"))
                print("[" + sdfDateTime.format(new Date()) + "] " + message, false, forceStdout);
            else
                for (String msgPart : message.split("\n"))
                    print("[" + sdfDateTime.format(new Date()) + "] " + msgPart, false, forceStdout);
    }

    public static void printErr(String message)
    {
        if (message != null && !message.equals(""))
            if (!message.contains("\n"))
                print("[" + sdfDateTime.format(new Date()) + "] !!!> " + message + " <!!!", true);
            else
                for (String msgPart : message.split("\n"))
                    print("[" + sdfDateTime.format(new Date()) + "] !!!> " + msgPart + " <!!!", true);
    }

    private static void print(String message, boolean toErr)
    {
        print(message, toErr, toErr);
    }

    private static synchronized void print(String message, boolean toErr, boolean forceStdout)
    {
        if (toErr)
            stdErr.println(message);
        else if (forceStdout)
            stdOut.println(message);

        filePrint(message);
    }

    private static synchronized void filePrint(String message)
    {
        Date logDate = new Date();
        if (!lastLogDate.equals(sdfDate.format(logDate)))
        {
            lastLogDate = sdfDate.format(logDate);

            logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + lastLogDate.replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintStream(new FileOutputStream(logFile, true));

                if (System.out instanceof DoublePrintStream)
                    ((DoublePrintStream) System.out).newFOut(logStream).close();
                if (System.err instanceof DoublePrintStream) {
                    ((DoublePrintStream) System.err).newFOut(logStream).close();
                }
            }
            catch (IOException e) { printErr("[Logging] Could not create log file"); printThrowable(e, "Logging"); }
        }

        logStream.println(message);
    }
    //</editor-fold>

    public static void ensureServerOpen()
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
                    EASMWebSocket.printWebSocket("WebSocket server runnable finished" + (ews.isClosed() ? "" : " unnexpectedly"), !ews.isClosed(), true);

                    if (ews == webSocket)
                    {
                        try
                        {
                            webSocket = null;
                            ews.stop(0);
                        }
                        catch (InterruptedException ignored) {}
                    }
                }
            }, "WebSocket").start();
        }
    }

    public static void reloadConfig()
    {
        try (BufferedReader br = new BufferedReader(new FileReader(new File(EASM_STORAGE_DIR, "config.json"))))
        {
            JSONObject oldConfig = config;
            config = new JSONObject(new JSONTokener(br));

            if (oldConfig != null && oldConfig.has("WSPort") && config.has("WSPort")
                    && oldConfig.optInt("WSPort") != config.optInt("WSPort"))
            {
                printOut("[WebSocket] Restarting WS Server on " + config.optInt("WSPort"), true);

                try { webSocket.stop(0); }
                catch (InterruptedException ignored) {}

                ensureServerOpen();
            }

            if (oldConfig != null && oldConfig.has("TDLogging") && config.has("TDLogging")
                    && oldConfig.optBoolean("TDLogging") != config.optBoolean("TDLogging"))
            {
                if (config.optBoolean("TDLogging"))
                    TDHandler.startLogging();
                else
                    TDHandler.stopLogging();
            }

            ConnectionManager.updateSubscriptions();
        }
        catch (IOException | JSONException | JMSException ex)
        {
            NRODLight.printThrowable(ex, "Config");
        }
    }

    public static void emailUpdate(final String subject, final String content, final boolean waitFor)
    {
        if (config.has("update-email") && config.getString("update-email").contains("@"))
        {
            try
            {
                String[] args = new String[] {"/usr/bin/mail", "-a", "Content-Type: text/html", "-s", subject, config.getString("update-email")};
                Process p = Runtime.getRuntime().exec(args);
                p.getOutputStream().write(content.getBytes());
                p.getOutputStream().close();

                if (waitFor)
                    p.waitFor();
            }
            catch (IOException | InterruptedException e) { printThrowable(e, "Emailer"); }
        }
    }

    public static ScheduledExecutorService getExecutor()
    {
        return executor;
    }

    private static void readTDDataFile(final File f, final boolean isNew, final AtomicInteger count) {
        List<String> lines = null;
        try {
            lines = Files.readAllLines(f.toPath());
        } catch (IOException ex) {
            printErr("[Startup] Cannot read " + f.getName() + ": " + ex);

            if (!isNew) {
                readTDDataFile(new File(f.getAbsoluteFile() + ".new"), true, count);
                return;
            }
        } finally {
            if (f.getName().endsWith(".c.td" + (isNew ? ".new" : "")) || f.getName().endsWith(".s.td" + (isNew ? ".new" : ""))) {
                printOut("[Startup] Deleting " + f.getName(), true);
                f.delete();
            }
        }

        if (lines != null) {
            if (lines.get(0).charAt(0) == '{') {
                try {
                    JSONObject data = new JSONObject(String.join("", lines));
                    data.keys().forEachRemaining((k) -> TDHandler.DATA_MAP.putIfAbsent(k, data.getString(k)));
                    printOut("[Startup] Read JSON data from " + f.getName() + ", will be converted", true);
                } catch (JSONException jex) {
                    printErr("[Startup] Malformed JSON in " + f.getName());

                    if (!isNew) {
                        readTDDataFile(new File(f.getAbsoluteFile() + ".new"), true, count);
                        return;
                    }
                }
            } else {
                Iterator<String> iter = lines.iterator();
                Pattern p = Pattern.compile("^((.{4}:[1-8])\t([01])|(.{6})\t(.{4})?)$");

                if (iter.hasNext()) {
                    String lineCountStr = iter.next();
                    int lineCount = Integer.parseInt(lineCountStr);
                    if (lineCount != lines.size() - 1) {
                        printErr("[Startup] Possible missing data from " + f.getName() + " (" + lineCountStr + "/" + (lines.size() - 1) + ")");
                    }

                    while (iter.hasNext()) {
                        String key = iter.next();
                        Matcher m = p.matcher(key);
                        if (m.matches()) {
                            if (key.charAt(4) == ':') {
                                TDHandler.DATA_MAP.putIfAbsent(m.group(2), "0".equals(m.group(3)) ? "0" : "1");
                            } else {
                                TDHandler.DATA_MAP.putIfAbsent(m.group(4), m.group(5) == null ? "" : m.group(5));
                            }
                        } else {
                            printErr("[Startup] Malformed line in " + f.getName() + ": " + key);
                        }
                    }
                } else {
                    printErr("[Startup] Possible missing data from " + f.getName() + " (null/0)");
                }
            }
            count.incrementAndGet();
        }
    }
}
