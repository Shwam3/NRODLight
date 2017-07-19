package nrodclient;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import nrodclient.stomp.StompConnectionHandler;
import nrodclient.stomp.handlers.TDHandler;
import nrodclient.ws.EASMWebSocket;
import org.java_websocket.WebSocket;
import org.json.JSONException;
import org.json.JSONObject;

public class NRODClient
{
    public static final String VERSION = "3";

    public static final boolean verbose = false;
    
    public static final File EASM_STORAGE_DIR = new File(System.getProperty("user.home", "C:") + File.separator + ".easigmap");
    public static JSONObject config = new JSONObject();

    public static SimpleDateFormat sdfTime          = new SimpleDateFormat("HH:mm:ss");
    public static SimpleDateFormat sdfDate          = new SimpleDateFormat("dd/MM/yy");
    public static SimpleDateFormat sdfDateTime      = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
    public static SimpleDateFormat sdfDateTimeShort = new SimpleDateFormat("dd/MM HH:mm:ss");

    public  static PrintStream  logStream;
    private static File         logFile;
    private static String       lastLogDate = "";
    
    private static TrayIcon sysTrayIcon = null;
    
    public static final int     port = 6423;
    public static EASMWebSocket webSocket;

    public static PrintStream stdOut = System.out;
    public static PrintStream stdErr = System.err;
    
    public static void main(String[] args) throws IOException, GeneralSecurityException
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
        
        reloadConfig();
        
        try
        {
            File TDDataDir = new File(NRODClient.EASM_STORAGE_DIR, "TDData");
            for (File perAreaDir : TDDataDir.listFiles())
            {
                String area = perAreaDir.getName();
                if (area.length() != 2 || !perAreaDir.isDirectory())
                    continue;

                for (File TDData : perAreaDir.listFiles())
                {
                    String dataID = TDData.getName().replace("-", ":");

                    if (TDData.isDirectory() || !dataID.endsWith(".td") || dataID.length() != 7)
                        continue;
                    
                    String data = "";
                    try (BufferedReader br = new BufferedReader(new FileReader(TDData)))
                    {
                        data = br.readLine();
                    }
                    catch (IOException ex) { NRODClient.printThrowable(ex, "TD-Startup"); }

                    TDHandler.DATA_MAP.put(area + dataID.substring(0, 4), data == null ? "" : data);
                }
            }
        }
        catch (Exception e) { NRODClient.printThrowable(e, "TD-Startup"); }
        
        ensureServerOpen();
        
        if (StompConnectionHandler.wrappedConnect())
            StompConnectionHandler.printStomp("Initialised and working", false);
        else
            StompConnectionHandler.printStomp("Unble to start", true);

        Timer sleepTimer = new Timer("sleepTimer", true);
        sleepTimer.scheduleAtFixedRate(new TimerTask()
        {
            @Override
            public void run()
            {
                try
                {
                    Point mouseLoc = MouseInfo.getPointerInfo().getLocation();
                    new Robot().mouseMove(mouseLoc.x, mouseLoc.y);
                }
                catch (NullPointerException e) {}
                catch (Exception e) { printErr("[Timer] Exception: " + e.toString()); }
            }
        }, 30000, 30000);
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
                    webSocket.connections().stream()
                        .filter(c -> c != null)
                        .filter(c -> c.isOpen())
                        .forEach(c -> c.send(messageStr));
                    EASMWebSocket.printWebSocket("Updated all clients", false);
                }
                catch (Exception e) { printThrowable(e, "SendAll"); }
            }
        }, 500, 60000);
        
        updatePopupMenu();
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

            logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODClient" + File.separator + lastLogDate.replace("/", "-") + ".log");
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

    public static synchronized void updatePopupMenu()
    {
        if (SystemTray.isSupported())
        {
            try
            {
                PopupMenu menu             = new PopupMenu();
                MenuItem itemExit          = new MenuItem("Exit");
                MenuItem itemOpenLog       = new MenuItem("Open Log File");
                MenuItem itemOpenLogFolder = new MenuItem("Open Log File Folder");
                MenuItem itemStatus        = new MenuItem("Status...");
                MenuItem itemInputData     = new MenuItem("Input Data...");
                MenuItem itemReconnect     = new MenuItem("Stomp Reconnect");

                itemStatus.addActionListener((ActionEvent e) ->
                {
                    NRODClient.updatePopupMenu();
                    
                    Collection<WebSocket> connsSSL = Collections.unmodifiableCollection(NRODClient.webSocket.connections());
                    StringBuilder statusMsg = new StringBuilder();
                    statusMsg.append("WebSocket:");
                    statusMsg.append("\n  Connections: ").append(connsSSL.size());
                    statusMsg.append("\n    Secure: ").append(connsSSL.size());
                    connsSSL.stream().filter(c -> c != null).forEachOrdered(c -> statusMsg.append("\n      ").append(c.getRemoteSocketAddress().getAddress().getHostAddress()).append(":").append(c.getRemoteSocketAddress().getPort()));
                    statusMsg.append("\nStomp:");
                    statusMsg.append("\n  Connection: ").append(StompConnectionHandler.isConnected() ? "Connected" : "Disconnected").append(StompConnectionHandler.isTimedOut() ? " (timed out)" : "");
                    statusMsg.append("\n  Timeout: ").append((System.currentTimeMillis() - StompConnectionHandler.lastMessageTimeGeneral) / 1000f).append("s");
                    statusMsg.append("\n  Subscriptions:");
                    statusMsg.append("\n    TD: ").append(String.format("%s - %.2fs", StompConnectionHandler.isSubscribedTD() ? "Yes" : "No", TDHandler.getInstance().getTimeout() / 1000f));
                    statusMsg.append("\nLogfile: \"").append(NRODClient.logFile.getName()).append("\"");
                    statusMsg.append("\nStarted: ").append(NRODClient.sdfDateTime.format(ManagementFactory.getRuntimeMXBean().getStartTime()));
                    
                    JOptionPane.showMessageDialog(null, statusMsg.toString(), "NRODClient - Status", JOptionPane.INFORMATION_MESSAGE);
                });
                itemInputData.addActionListener(e ->
                {
                    String input = JOptionPane.showInputDialog(null, "Please input the data in the format: 'key:value;key:value':", "Input Data", JOptionPane.QUESTION_MESSAGE);
                    if (input != null)
                    {
                        Map<String, String> updateMap = new HashMap();
                        String[] pairs = input.split(";");
                        
                        for (String pair : pairs)
                            if (pair.length() >= 7)
                                if (pair.charAt(6) == ':')
                                    updateMap.put(pair.substring(0, 6), pair.substring(7));
                            
                        JSONObject container = new JSONObject();
                        JSONObject message = new JSONObject();
                        message.put("type", "SEND_UPDATE");
                        message.put("timestamp", System.currentTimeMillis());
                        message.put("message", updateMap);
                        container.put("Message", message);
                        TDHandler.DATA_MAP.putAll(updateMap);                        
                        TDHandler.saveTDData(updateMap);

                        String messageStr = container.toString();
                        NRODClient.webSocket.connections().stream()
                                .filter(c -> c != null)
                                .filter(c -> c.isOpen())
                                .forEach(c -> c.send(messageStr));
                    }
                });
                itemReconnect.addActionListener((ActionEvent e) ->
                {
                    if (JOptionPane.showConfirmDialog(null, "Are you sure you wish to reconnect?", "Confirmation", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    {
                        StompConnectionHandler.disconnect();
                        StompConnectionHandler.wrappedConnect();
                    }
                });
                itemOpenLog.addActionListener((ActionEvent evt) ->
                {
                    try { Desktop.getDesktop().open(NRODClient.logFile); }
                    catch (IOException e) {}
                });
                itemOpenLogFolder.addActionListener((ActionEvent evt) ->
                {
                    try { Runtime.getRuntime().exec("explorer.exe /select," + NRODClient.logFile); }
                    catch (IOException e) {}
                });
                itemExit.addActionListener((ActionEvent e) ->
                {
                    if (JOptionPane.showConfirmDialog(null, "Are you sure you wish to exit?", "Confirmation", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    {
                        NRODClient.printOut("[Main] Stopping");
                        System.exit(0);
                    }
                });

                menu.add(itemStatus);
                menu.add(itemInputData);
                menu.add(itemReconnect);
                menu.addSeparator();
                menu.add(itemOpenLog);
                menu.add(itemOpenLogFolder);
                menu.addSeparator();
                menu.add(itemExit);

                if (sysTrayIcon == null)
                {
                    sysTrayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(NRODClient.class.getResource("/nrodclient/resources/TrayIcon.png")), "NROD Light", menu);
                    sysTrayIcon.setImageAutoSize(true);
                    sysTrayIcon.addMouseListener(new MouseAdapter()
                    {
                        @Override
                        public void mouseClicked(MouseEvent e)
                        {
                            updatePopupMenu();
                        }
                    });
                }

                sysTrayIcon.setPopupMenu(menu);

                if (!Arrays.asList(SystemTray.getSystemTray().getTrayIcons()).contains(sysTrayIcon))
                    SystemTray.getSystemTray().add(sysTrayIcon);
            }
            catch (AWTException e) { printThrowable(e, "SystemTrayIcon"); }
        }
    }
    
    private static void ensureServerOpen()
    {
        if (webSocket == null || webSocket.isClosed())
        {
            new Thread(() -> {
                EASMWebSocket ews = new EASMWebSocket(port, true);
                try
                {
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
        } catch (IOException | JSONException e) { NRODClient.printThrowable(e, "Config"); }
        
        List<String> filter = new ArrayList<>();
        config.getJSONArray("TD_Area_Filter").forEach(e -> filter.add((String) e));
        filter.sort(null);
        TDHandler.setAreaFilter(filter);
    }
}
