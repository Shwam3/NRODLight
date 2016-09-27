package nrodclient;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import nrodclient.stomp.StompConnectionHandler;
import nrodclient.stomp.handlers.MVTHandler;
import nrodclient.stomp.handlers.RTPPMHandler;
import nrodclient.stomp.handlers.TDHandler;
import nrodclient.stomp.handlers.TSRHandler;
import nrodclient.stomp.handlers.VSTPHandler;
import org.java_websocket.WebSocket;
import org.json.JSONObject;

public class NRODClient
{
    public static final String VERSION = "1";

    public  static final boolean verbose = false;
    public  static boolean stop = true;

    public static final File EASM_STORAGE_DIR = new File(System.getProperty("user.home", "C:") + File.separator + ".easigmap");

    public static SimpleDateFormat sdfTime          = new SimpleDateFormat("HH:mm:ss");
    public static SimpleDateFormat sdfDate          = new SimpleDateFormat("dd/MM/yy");
    public static SimpleDateFormat sdfDateTime      = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
    public static SimpleDateFormat sdfDateTimeShort = new SimpleDateFormat("dd/MM HH:mm:ss");

    public  static PrintStream  logStream;
    private static File         logFile;
    private static String       lastLogDate = "";

    public  static String ftpBaseUrl = "";

    private static boolean  trayIconAdded = false;
    private static TrayIcon sysTrayIcon = null;
    
    public  static final int     port = 6322;
    public  static EASMWebSocket webSocket;
    public  static final int     portSSL = 6323;
    public  static EASMWebSocket webSocketSSL;
    public  static DataGui       guiData;

    public static PrintStream stdOut = System.out;
    public static PrintStream stdErr = System.err;
    
    /*static
    {
        String ver = "1";
        int prt = 6322;
        
        File versionFile = new File(EASMStorageDir, "NROD_Version.properties");
        try
        {
            //if (versionFile.exists())
            //{
            //    Properties versionProps = new Properties();
            //    versionProps.load(new FileInputStream(versionFile));
            //    ver = versionProps.getProperty("version", ver);
            //}

            //if (!NRODClient.class.getResource(NRODClient.class.getSimpleName() + ".class").toString().contains(".jar"))
            //{
            //    ver += "-DEV";
            //    prt += 2;
            //}
        }
        catch (IOException ex) { ex.printStackTrace(); }
        
        VERSION = ver;
        port = prt;
    }*/

    public static void main(String[] args) throws IOException, GeneralSecurityException
    {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) { printThrowable(e, "Look & Feel"); }

        Date logDate = new Date();
        logFile = new File(EASM_STORAGE_DIR, "Logs" + File.separator + "NRODClient" + File.separator + sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = sdfDate.format(logDate);

        try
        {
            logStream = new PrintStream(new FileOutputStream(logFile, logFile.length() > 0), true);
            System.setOut(logStream);
            System.setErr(logStream);
        }
        catch (FileNotFoundException e) { printErr("Could not create log file"); printThrowable(e, "Startup"); }

        try
        {
            File ftpLoginFile = new File(EASM_STORAGE_DIR, "Website_FTP_Login.properties");
            if (ftpLoginFile.exists())
            {
                Properties ftpLogin = new Properties();
                ftpLogin.load(new FileInputStream(ftpLoginFile));

                ftpBaseUrl = "ftp://" + ftpLogin.getProperty("Username", "") + ":" + ftpLogin.getProperty("Password", "") + "@" + ftpLogin.getOrDefault("URL", "");
            }
        }
        catch (FileNotFoundException e) {}
        catch (IOException e) { printThrowable(e, "FTP Login"); }
        
        try { EventQueue.invokeAndWait(() -> guiData = new DataGui()); }
        catch (InvocationTargetException | InterruptedException e) { printThrowable(e, "Startup"); }
        
        if (StompConnectionHandler.wrappedConnect())
            StompConnectionHandler.printStomp("Initialised and working", false);
        else
            StompConnectionHandler.printStomp("Unble to start", true);
        
        webSocket = new EASMWebSocket(port, false);
        webSocket.start();
        webSocketSSL = new EASMWebSocket(portSSL, true);
        webSocketSSL.start();

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
        /*Timer managementTimer = new Timer("managementTimer", true);
        managementTimer.scheduleAtFixedRate(new TimerTask()
        {
            @Override
            public void run()
            {
                try
                {
                    JSONObject managementContainer = new JSONObject();
                    JSONObject management = new JSONObject();
                    JSONArray connections = new JSONArray();
                    NRODClient.webSocket.connections().stream().filter(c -> c != null).forEachOrdered(c -> connections.put(c.getRemoteSocketAddress().getAddress().getHostAddress() + ":" + c.getRemoteSocketAddress().getPort()));
                    management.put("connections", connections);
                    
                    managementContainer.put("management", "");
                    managementContainer.put("timestamp", System.currentTimeMillis());
                }
                catch (NullPointerException e) {}
                catch (Exception e) { printErr("[Timer] Exception: " + e.toString()); }
            }
        }, 30000, 30000);*/
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
                    content.put("timestamp", Long.toString(System.currentTimeMillis()));
                    content.put("message", new HashMap<>(TDHandler.DataMap));
                    message.put("Message", content);
                    String messageStr = message.toString();

                    webSocket.connections().stream()
                        .filter(c -> c != null)
                        .filter(c -> c.isOpen())
                        .forEach(c -> c.send(messageStr));
                    webSocketSSL.connections().stream()
                        .filter(c -> c != null)
                        .filter(c -> c.isOpen())
                        .forEach(c -> c.send(messageStr));
                    printOut("[WebSocket] Updated all clients");
                }
                catch (Exception e) { printThrowable(e, "SendAll"); }
            }
        }, 500, 1000*60);

        updatePopupMenu();
    }

    //<editor-fold defaultstate="collapsed" desc="Print methods">
    public static void printThrowable(Throwable t, String name)
    {
        printErr((name != null && !name.isEmpty() ? "[" + name + "] " : "") + t.toString());

        for (StackTraceElement element : t.getStackTrace())
            printErr((name != null && !name.isEmpty() ? "[" + name + "] -> " : "-> ") + element.toString());

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
                MenuItem itemData          = new MenuItem("View Data...");
                MenuItem itemInputData     = new MenuItem("Input Data...");
                MenuItem itemRTPPMUpload   = new MenuItem("Upload RTPPM file");
                MenuItem itemReconnect     = new MenuItem("Stomp Reconnect");

                Menu menuSubscriptions                  = new Menu("Subscriptions");
                CheckboxMenuItem itemSubscriptionsRTPPM = new CheckboxMenuItem("RTPPM", StompConnectionHandler.isSubscribedRTPPM());
                CheckboxMenuItem itemSubscriptionsMVT   = new CheckboxMenuItem("MVT",   StompConnectionHandler.isSubscribedMVT());
                CheckboxMenuItem itemSubscriptionsVSTP  = new CheckboxMenuItem("VSTP",  StompConnectionHandler.isSubscribedVSTP());
                CheckboxMenuItem itemSubscriptionsTSR   = new CheckboxMenuItem("TSR",   StompConnectionHandler.isSubscribedTSR());

                itemStatus.addActionListener((ActionEvent e) ->
                {
                    NRODClient.updatePopupMenu();
                    
                    Collection<WebSocket> conns = Collections.unmodifiableCollection(NRODClient.webSocket.connections());
                    Collection<WebSocket> connsSSL = Collections.unmodifiableCollection(NRODClient.webSocketSSL.connections());
                    StringBuilder statusMsg = new StringBuilder();
                    statusMsg.append("WebSocket:");
                    statusMsg.append("\n  Connections: ").append(conns.size());
                    statusMsg.append("\n    Insecure: ").append(conns.size());
                    conns.stream().filter(c -> c != null).forEachOrdered(c -> statusMsg.append("\n      ").append(c.getRemoteSocketAddress().getAddress().getHostAddress()).append(":").append(c.getRemoteSocketAddress().getPort()));
                    statusMsg.append("\n    Secure: ").append(conns.size());
                    connsSSL.stream().filter(c -> c != null).forEachOrdered(c -> statusMsg.append("\n      ").append(c.getRemoteSocketAddress().getAddress().getHostAddress()).append(":").append(c.getRemoteSocketAddress().getPort()));
                    statusMsg.append("\nStomp:");
                    statusMsg.append("\n  Connection: ").append(StompConnectionHandler.isConnected() ? "Connected" : "Disconnected").append(StompConnectionHandler.isTimedOut() ? " (timed out)" : "");
                    statusMsg.append("\n  Timeout: ").append((System.currentTimeMillis() - StompConnectionHandler.lastMessageTimeGeneral) / 1000f).append("s");
                    statusMsg.append("\n  Subscriptions:");
                    statusMsg.append("\n    TD: ").append(String.format("%s - %.2fs", StompConnectionHandler.isSubscribedTD() ? "Yes" : "No", TDHandler.getInstance().getTimeout() / 1000f));
                    statusMsg.append("\n    MVT: ").append(String.format("%s - %.2fs", StompConnectionHandler.isSubscribedMVT() ? "Yes" : "No", MVTHandler.getInstance().getTimeout() / 1000f));
                    statusMsg.append("\n    RTPPM: ").append(String.format("%s - %.2fs", StompConnectionHandler.isSubscribedRTPPM() ? "Yes" : "No", RTPPMHandler.getInstance().getTimeout() / 1000f));
                    statusMsg.append("\n    VSTP: ").append(String.format("%s - %.2fs", StompConnectionHandler.isSubscribedVSTP() ? "Yes" : "No", VSTPHandler.getInstance().getTimeout() / 1000f));
                    statusMsg.append("\n    TSR: ").append(String.format("%s - %.2fs", StompConnectionHandler.isSubscribedTSR() ? "Yes" : "No", TSRHandler.getInstance().getTimeout() / 1000f));
                    statusMsg.append("\nLogfile: \"").append(NRODClient.logFile.getName()).append("\"");
                    statusMsg.append("\nStarted: ").append(NRODClient.sdfDateTime.format(ManagementFactory.getRuntimeMXBean().getStartTime()));
                    
                    JOptionPane.showMessageDialog(null, statusMsg.toString(), "NRODClient - Status", JOptionPane.INFORMATION_MESSAGE);
                });
                itemData.addActionListener(e -> NRODClient.guiData.setVisible(true));
                itemInputData.addActionListener(e ->
                {
                    String input = JOptionPane.showInputDialog(null, "Please input the data in the format: 'key:value;key:value':", "Input Data", JOptionPane.QUESTION_MESSAGE);
                    if (input != null)
                    {
                        Map<String, String> map = new HashMap();
                        String[] pairs = input.split(";");
                        
                        for (String pair : pairs)
                            if (pair.length() >= 7)
                                if (pair.charAt(6) == ':')
                                    map.put(pair.substring(0, 6), pair.substring(7));
                            
                        JSONObject container = new JSONObject();
                        JSONObject message = new JSONObject();
                        message.put("type", "SEND_UPDATE");
                        message.put("timestamp", System.currentTimeMillis());
                        message.put("message", map);
                        container.put("Message", message);
                        TDHandler.DataMap.putAll(map);

                        String messageStr = container.toString();
                        NRODClient.webSocket.connections().stream()
                                .filter(c -> c != null)
                                .filter(c -> c.isOpen())
                                .forEach(c -> c.send(messageStr));
                    }
                });
                itemRTPPMUpload.addActionListener((ActionEvent e) ->
                {
                    RTPPMHandler.uploadHTML();
                });
                itemReconnect.addActionListener((ActionEvent e) ->
                {
                    if (JOptionPane.showConfirmDialog(null, "Are you sure you wish to reconnect?", "Confirmation", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    {
                        StompConnectionHandler.disconnect();
                        StompConnectionHandler.wrappedConnect();
                    }
                });
                itemSubscriptionsRTPPM.addItemListener((ItemEvent e) -> { StompConnectionHandler.toggleRTPPM(); });
                itemSubscriptionsMVT  .addItemListener((ItemEvent e) -> { StompConnectionHandler.toggleMVT(); });
                itemSubscriptionsVSTP .addItemListener((ItemEvent e) -> { StompConnectionHandler.toggleVSTP(); });
                itemSubscriptionsTSR  .addItemListener((ItemEvent e) -> { StompConnectionHandler.toggleTSR(); });
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

                menuSubscriptions.add(itemSubscriptionsRTPPM);
                menuSubscriptions.add(itemSubscriptionsMVT);
                menuSubscriptions.add(itemSubscriptionsVSTP);
                menuSubscriptions.add(itemSubscriptionsTSR);

                menu.add(itemStatus);
                menu.add(itemData);
                menu.add(itemInputData);
                menu.add(itemRTPPMUpload);
                menu.add(itemReconnect);
                menu.add(menuSubscriptions);
                menu.addSeparator();
                menu.add(itemOpenLog);
                menu.add(itemOpenLogFolder);
                menu.addSeparator();
                menu.add(itemExit);

                if (sysTrayIcon == null)
                {
                    sysTrayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(NRODClient.class.getResource("/nrodclient/resources/TrayIcon.png")), "NROD Client", menu);
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

                if (!trayIconAdded)
                    SystemTray.getSystemTray().add(sysTrayIcon);

                trayIconAdded = true;
            }
            catch (AWTException e) { printThrowable(e, "SystemTrayIcon"); }
        }
    }
}