package nrodclient;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Desktop;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import nrodclient.stomp.StompConnectionHandler;
import nrodclient.stomp.handlers.MVTHandler;
import nrodclient.stomp.handlers.RTPPMHandler;
import nrodclient.stomp.handlers.TSRHandler;
import nrodclient.stomp.handlers.VSTPHandler;

public class NRODClient
{
    public static final String VERSION = "1";

    public  static final boolean verbose = false;
    public  static boolean stop = true;

    public static final File EASMStorageDir = new File(System.getProperty("user.home", "C:") + File.separator + ".easigmap");

    public static SimpleDateFormat sdfTime     = new SimpleDateFormat("HH:mm:ss");
    public static SimpleDateFormat sdfDate     = new SimpleDateFormat("dd/MM/YY");
    public static SimpleDateFormat sdfDateTime = new SimpleDateFormat("dd/MM/YY HH:mm:ss");

  //public  static final Object logfileLock = new Object();
    public  static PrintStream  logStream;
    private static File         logFile;
    private static String       lastLogDate = "";

    public  static String ftpBaseUrl = "";

    private static boolean  trayIconAdded = false;
    private static TrayIcon sysTrayIcon = null;

    public static void main(String[] args)
    {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) { printThrowable(e, "Look & Feel"); }

        Date logDate = new Date(System.currentTimeMillis());
        logFile = new File(EASMStorageDir, "Logs" + File.separator + "NRODClient" + File.separator + sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = sdfDate.format(logDate);

        try { logStream = new PrintStream(new FileOutputStream(logFile, logFile.length() > 0)); }
        catch (FileNotFoundException e) { printErr("Could not create log file"); printThrowable(e, "Startup"); }

        try
        {
            File ftpLoginFile = new File(EASMStorageDir, "Website_FTP_Login.properties");
            if (ftpLoginFile.exists())
            {
                Properties ftpLogin = new Properties();
                ftpLogin.load(new FileInputStream(ftpLoginFile));

                ftpBaseUrl  = "ftp://" + ftpLogin.getProperty("Username", "") + ":" + ftpLogin.getProperty("Password", "") + "@ftp.easignalmap.altervista.org/";
            }
        }
        catch (FileNotFoundException e) {}
        catch (IOException e) { printThrowable(e, "FTP Login"); }

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
            System.err.println(message);
        else
            System.out.println(message);

        filePrint(message);
    }

    private static synchronized void filePrint(String message)
    {
        if (!lastLogDate.equals(sdfDate.format(new Date())))
        {
            logStream.close();

            Date logDate = new Date(System.currentTimeMillis());
            lastLogDate = sdfDate.format(logDate);

            logFile = new File(EASMStorageDir, "Logs" + File.separator + "NRODClient" + File.separator + sdfDate.format(logDate).replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintStream(new FileOutputStream(logFile, logFile.length() > 0));
            }
            catch (IOException e) { printErr("Could not create next log file"); printThrowable(e, "Startup"); }
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

                    JOptionPane.showMessageDialog(null,
                            "Stomp:\n"
                          + "  Connection: " + (StompConnectionHandler.isConnected() ? "Connected" : "Disconnected") + (StompConnectionHandler.isTimedOut() ? " (timed out)" : "")
                          + "  Timeout: " + ((System.currentTimeMillis() - StompConnectionHandler.lastMessageTimeGeneral) / 1000f) + "s\n"
                          + "  Subscriptions:\n"
                          + "    MVT: "   + String.format("%s - %.2fs (%ss)%n", StompConnectionHandler.isSubscribedMVT()   ? "Yes" : "No", MVTHandler  .getInstance().getTimeout() / 1000f, MVTHandler  .getInstance().getTimeout() / 1000f)
                          + "    RTPPM: " + String.format("%s - %.2fs (%ss)%n", StompConnectionHandler.isSubscribedRTPPM() ? "Yes" : "No", RTPPMHandler.getInstance().getTimeout() / 1000f, RTPPMHandler.getInstance().getTimeout() / 1000f)
                          + "    VSTP: "  + String.format("%s - %.2fs (%ss)%n", StompConnectionHandler.isSubscribedVSTP()  ? "Yes" : "No", VSTPHandler .getInstance().getTimeout() / 1000f, VSTPHandler .getInstance().getTimeout() / 1000f)
                          + "    TSR: "   + String.format("%s - %.2fs (%ss)%n", StompConnectionHandler.isSubscribedTSR()   ? "Yes" : "No", TSRHandler  .getInstance().getTimeout() / 1000f, TSRHandler  .getInstance().getTimeout() / 1000f)
                          + "Logfile: \"" + NRODClient.logFile.getName() + "\"\n"
                          + "Started: " + NRODClient.sdfDateTime.format(ManagementFactory.getRuntimeMXBean().getStartTime()),
                            "NRODClient - Status", JOptionPane.INFORMATION_MESSAGE);
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