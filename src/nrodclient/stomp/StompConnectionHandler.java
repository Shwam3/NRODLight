package nrodclient.stomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.login.LoginException;
import net.ser1.stomp.Listener;
import net.ser1.stomp.Version;
import nrodclient.NRODClient;
import nrodclient.stomp.handlers.ErrorHandler;
import nrodclient.stomp.handlers.MVTHandler;
import nrodclient.stomp.handlers.RTPPMHandler;
import nrodclient.stomp.handlers.RateMonitor;
import nrodclient.stomp.handlers.TDHandler;
import nrodclient.stomp.handlers.TSRHandler;
import nrodclient.stomp.handlers.VSTPHandler;

public class StompConnectionHandler
{
    private static StompClient client;

    private static ScheduledExecutorService executor = null;
    private static int    maxTimeoutWait = 300;
  //private static int    timeoutWait = 10;
  //private static int    wait = 0;
    public  static long   lastMessageTimeGeneral = System.currentTimeMillis();
    private static String appID = "";
    private static int    stompConnectionId = 1;

    private static boolean subscribedRTPPM = false;
    private static boolean subscribedMVT   = false;
    private static boolean subscribedVSTP  = false;
    private static boolean subscribedTSR   = false;
    private static boolean subscribedTD    = false;

    private static final Listener rateMonitor  = RateMonitor.getInstance();
    private static final Listener handlerRTPPM = RTPPMHandler.getInstance();
    private static final Listener handlerMVT   = MVTHandler.getInstance();
    private static final Listener handlerVSTP  = VSTPHandler.getInstance();
    private static final Listener handlerTSR   = TSRHandler.getInstance();
    private static final Listener handlerTD    = TDHandler.getInstance();

    public static boolean connect() throws LoginException, IOException
    {
        printStomp(Version.VERSION, false);

        subscribedRTPPM = false;
        subscribedMVT   = false;
        subscribedVSTP  = false;
        subscribedTSR   = false;
        subscribedTD    = false;

        String username;
        String password;

        File loginFile = new File(NRODClient.EASMStorageDir, "NROD_Login.properties");
        try (FileInputStream in = new FileInputStream(loginFile))
        {
            Properties loginProps = new Properties();
            loginProps.load(in);

            username = loginProps.getProperty("Username", "");
            password = loginProps.getProperty("Password", "");
        }
        catch (FileNotFoundException e)
        {
            printStomp("Unable to find login properties file (" + loginFile + ")", true);
            return false;
        }

        appID = username + "-NRODClient-v" + NRODClient.VERSION + "-";

        if ((username != null && username.equals("")) || (password != null && password.equals("")))
        {
            printStomp("Error retreiving login details (usr: " + username + ", pwd: " + password + ")", true);
            return false;
        }

        startTimeoutTimer();
        client = new StompClient("datafeeds.networkrail.co.uk", 61618, username, password, appID + stompConnectionId);

        if (client.isConnected())
        {
            printStomp("Connected to \"datafeeds.networkrail.co.uk:61618\"", false);
            printStomp("  ID:       " + appID + stompConnectionId, false);
            printStomp("  Username: " + username, false);
            printStomp("  Password: " + password, false);
        }
        else
        {
            printStomp("Could not connect to network rails servers", true);
            return false;
        }

        client.addErrorListener(new ErrorHandler());
        toggleRTPPM();
        toggleMVT();
        toggleVSTP();
        toggleTSR();
        toggleTD();

        try { Thread.sleep(100); }
        catch (InterruptedException e) {}

        NRODClient.updatePopupMenu();

        return true;
    }

    public static void disconnect()
    {
        if (client != null && isConnected() && !isClosed())
        {
            client.disconnect();

            subscribedRTPPM = false;
            subscribedMVT   = false;
            subscribedVSTP  = false;
            subscribedTSR   = false;
            subscribedTD    = false;
        }
    }

    public static boolean isConnected()
    {
        if (client == null)
            return false;

        return client.isConnected();
    }

    public static boolean isClosed()
    {
        if (client == null)
            return false;

        return client.isClosed();
    }

    public static boolean isTimedOut()
    {
        long timeout = System.currentTimeMillis() - lastMessageTimeGeneral;

        return timeout >= getTimeoutThreshold() && getTimeoutThreshold() > 0;
    }

    private static long getTimeoutThreshold()
    {
        long threshold;

        if (subscribedMVT || subscribedTD)
            threshold = 30000;
        else if (subscribedRTPPM)
            threshold = 180000;
        else
            threshold = 1800000;

        return threshold;
    }

    public static boolean wrappedConnect()
    {
        try
        {
            return connect();
        }
        catch (LoginException e)       { printStomp("Login Exception: " + e.getLocalizedMessage().split("\n")[0], true); }
        catch (UnknownHostException e) { printStomp("Unable to resolve host (datafeeds.networkrail.co.uk)", true); }
        catch (IOException e)          { printStomp("IO Exception:", true); NRODClient.printThrowable(e, "Stomp"); }
        catch (Exception e)            { printStomp("Exception:", true); NRODClient.printThrowable(e, "Stomp"); }

        return false;
    }

    private static void startTimeoutTimer()
    {
        if (executor != null)
        {
            executor.shutdown();

            try { executor.awaitTermination(2, TimeUnit.SECONDS); }
            catch(InterruptedException e) {}
        }

        executor = Executors.newScheduledThreadPool(1);
        AtomicInteger waitGeneral = new AtomicInteger(0);
      /*AtomicInteger waitMVT     = new AtomicInteger(0);
        AtomicInteger waitRTPPM   = new AtomicInteger(0);
        AtomicInteger waitVSTP    = new AtomicInteger(0);
        AtomicInteger waitTSR     = new AtomicInteger(0);*/
        AtomicInteger timeoutWaitGeneral = new AtomicInteger(0);
      /*AtomicInteger timeoutWaitMVT     = new AtomicInteger(0);
        AtomicInteger timeoutWaitRTPPM   = new AtomicInteger(0);
        AtomicInteger timeoutWaitVSTP    = new AtomicInteger(0);
        AtomicInteger timeoutWaitTSR     = new AtomicInteger(0);*/

        // General timeout
        executor.scheduleWithFixedDelay(() ->
        {
            if (waitGeneral.get() >= timeoutWaitGeneral.get())
            {
                waitGeneral.set(0);

                long time = System.currentTimeMillis() - lastMessageTimeGeneral;

                printStomp(String.format("General Timeout: %02d:%02d:%02d (Threshold: %ss)", (time / (1000 * 60 * 60)) % 24, (time / (1000 * 60)) % 60, (time / 1000) % 60, (getTimeoutThreshold() / 1000)), isTimedOut() || !isConnected() || isClosed());

                if (isTimedOut() || !isConnected())
                {
                    timeoutWaitGeneral.set(Math.min(maxTimeoutWait, timeoutWaitGeneral.get() + 10));

                    printStomp((isTimedOut() ? "Timed Out" : "") + (isTimedOut() && isClosed() ? ", " : "") + (isClosed() ? "Closed" : "") + ((isTimedOut() || isClosed()) && !isConnected() ? " & " : "") + (!isConnected() ? "Disconnected" : "") + " (" + timeoutWaitGeneral.get() + "s)", true);

                    try
                    {
                        if (client != null)
                            client.disconnect();

                        connect();
                    }
                    catch (LoginException e) { printStomp("Login Exception: " + e.getLocalizedMessage().split("\n")[0], true);}
                    catch (IOException e)    { printStomp("IOException reconnecting", true); NRODClient.printThrowable(e, "Stomp"); }
                    catch (Exception e)      { printStomp("Exception reconnecting:", true);  NRODClient.printThrowable(e, "Stomp"); }
                }
                else
                {
                    timeoutWaitGeneral.set(10);

                    long timeMVT   = MVTHandler.getInstance().getTimeout();
                    long timeRTPPM = RTPPMHandler.getInstance().getTimeout();
                    long timeVSTP  = VSTPHandler.getInstance().getTimeout();
                    long timeTSR   = TSRHandler.getInstance().getTimeout();
                    long timeTD    = TDHandler.getInstance().getTimeout();
                    boolean timedOutMVT   = timeMVT   >= MVTHandler.getInstance().getTimeoutThreshold();
                    boolean timedOutRTPPM = timeRTPPM >= RTPPMHandler.getInstance().getTimeoutThreshold();
                    boolean timedOutVSTP  = timeVSTP  >= VSTPHandler.getInstance().getTimeoutThreshold();
                    boolean timedOutTSR   = timeTSR   >= TSRHandler.getInstance().getTimeoutThreshold();
                    boolean timedOutTD    = timeTD    >= TDHandler.getInstance().getTimeoutThreshold();

                    printStomp(String.format("  MVT Timeout:   %02d:%02d:%02d (Threshold: %ss)",
                                (timeMVT / (1000 * 60 * 60)) % 24,
                                (timeMVT / (1000 * 60)) % 60,
                                (timeMVT / 1000) % 60,
                                (MVTHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutMVT);
                    printStomp(String.format("  RTPPM Timeout: %02d:%02d:%02d (Threshold: %ss)",
                                (timeRTPPM / (1000 * 60 * 60)) % 24,
                                (timeRTPPM / (1000 * 60)) % 60,
                                (timeRTPPM / 1000) % 60,
                                (RTPPMHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutRTPPM);
                    printStomp(String.format("  VSTP Timeout:  %02d:%02d:%02d (Threshold: %ss)",
                                (timeVSTP / (1000 * 60 * 60)) % 24,
                                (timeVSTP / (1000 * 60)) % 60,
                                (timeVSTP / 1000) % 60,
                                (VSTPHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutVSTP);
                    printStomp(String.format("  TSR Timeout:   %02d:%02d:%02d (Threshold: %ss)",
                                (timeTSR / (1000 * 60 * 60)) % 24,
                                (timeTSR / (1000 * 60)) % 60,
                                (timeTSR / 1000) % 60,
                                (TSRHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutTSR);
                    printStomp(String.format("  TD Timeout:    %02d:%02d:%02d (Threshold: %ss)",
                                (timeTD / (1000 * 60 * 60)) % 24,
                                (timeTD / (1000 * 60)) % 60,
                                (timeTD / 1000) % 60,
                                (TDHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutTD);

                    if (timedOutMVT)
                    {
                        toggleMVT();

                        try { Thread.sleep(50); }
                        catch(InterruptedException e) {}

                        toggleMVT();
                    }
                    if (timedOutRTPPM)
                    {
                        toggleRTPPM();

                        try { Thread.sleep(50); }
                        catch(InterruptedException e) {}

                        toggleRTPPM();
                    }
                    if (timedOutVSTP)
                    {
                        toggleVSTP();

                        try { Thread.sleep(50); }
                        catch(InterruptedException e) {}

                        toggleVSTP();
                    }
                    if (timedOutTSR)
                    {
                        toggleTSR();

                        try { Thread.sleep(50); }
                        catch(InterruptedException e) {}

                        toggleTSR();
                    }
                    if (timedOutTD)
                    {
                        toggleTD();

                        try { Thread.sleep(50); }
                        catch(InterruptedException e) {}

                        toggleTD();
                    }
                    if (!timedOutMVT && !timedOutRTPPM && !timedOutVSTP && !timedOutTSR)
                        printStomp("No problems", false);
                }
            }
            else
                waitGeneral.addAndGet(10);
        }, 10, 10, TimeUnit.SECONDS);

        // MVT
        /*executor.scheduleWithFixedDelay(() ->
        {
            if (waitMVT.get() >= timeoutWaitMVT.get())
            {
                Listener mvt = MVTHandler.getInstance();
                waitMVT.set(0);

                long time = mvt.getTimeout();

                printStomp(String.format("  MVT Timeout: %02d:%02d:%02d (Threshold: %ss)", (time / (1000 * 60 * 60)) % 24, (time / (1000 * 60)) % 60, (time / 1000) % 60, (mvt.getTimeoutThreshold() / 1000)), mvt.getTimeout() > mvt.getTimeoutThreshold());

                if (isTimedOut() || !isConnected())
                {
                    timeoutWaitMVT.set(Math.min(maxTimeoutWait, timeoutWaitMVT.get() + 10));

                    printStomp((mvt.getTimeout() > mvt.getTimeoutThreshold() ? "Timed Out" : "") + "(" + timeoutWaitMVT.get() + "s)", true);

                    try
                    {
                        if (timeoutWaitMVT.get() >= maxTimeoutWait)
                        {
                            if (client != null)
                                client.disconnect();

                            connect();
                        }
                        else
                        {
                            StompConnectionHandler.toggleMVT();

                            try { Thread.sleep(50); }
                            catch(InterruptedException e) {}

                            StompConnectionHandler.toggleMVT();
                        }
                    }
                    catch (LoginException e) { printStomp("Login Exception: " + e.getLocalizedMessage().split("\n")[0], true);}
                    catch (IOException e)    { printStomp("IOException reconnecting", true); NRODClient.printThrowable(e, "Stomp"); }
                    catch (Exception e)      { printStomp("Exception resubscribing:", true);  NRODClient.printThrowable(e, "Stomp"); }
                }
                else
                {
                    timeoutWaitMVT.set(10);
                    printStomp("No problems", false);
                }
            }
            else
                waitMVT.addAndGet(10);
        }, 10, 10, TimeUnit.SECONDS);*/
    }

    public static void setMaxTimeoutWait(int maxTimeoutWait)
    {
        StompConnectionHandler.maxTimeoutWait = Math.max(60, maxTimeoutWait);
    }

    public static void printStomp(String message, boolean toErr)
    {
        if (toErr)
            NRODClient.printErr("[Stomp] " + message);
        else
            NRODClient.printOut("[Stomp] " + message);
    }

    public static String getConnectionName() { return appID + stompConnectionId; }
    public static int incrementConnectionId() { return ++stompConnectionId; }

    public static void ack(String ackId)
    {
        if (client != null)
            client.ack(ackId);
    }

    public static void toggleRTPPM()
    {
        if (subscribedRTPPM)
        {
            client.unsubscribe("RTPPM");
            StompConnectionHandler.printStomp("Unsubscribed from \"/topic/RTPPM_ALL\" (ID: \"" + appID + stompConnectionId + "-RTPPM\")", false);
            subscribedRTPPM = false;
        }
        else
        {
            client.subscribe("/topic/RTPPM_ALL", "RTPPM", handlerRTPPM);
            client.addListener("/topic/RTPPM_ALL", rateMonitor);
            subscribedRTPPM = true;
        }
        NRODClient.updatePopupMenu();
    }
    public static void toggleMVT()
    {
        if (subscribedMVT)
        {
            client.unsubscribe("MVT");
            StompConnectionHandler.printStomp("Unsubscribed from \"/topic/TRAIN_MVT_ALL_TOC\" (ID: \"" + appID + stompConnectionId + "-MVT\")", false);
            subscribedMVT = false;
        }
        else
        {
            client.subscribe("/topic/TRAIN_MVT_ALL_TOC", "MVT", handlerMVT);
            client.addListener("/topic/TRAIN_MVT_ALL_TOC", rateMonitor);
            subscribedMVT = true;
        }
        NRODClient.updatePopupMenu();
    }
    public static void toggleVSTP()
    {
        if (subscribedVSTP)
        {
            client.unsubscribe("VSTP");
            StompConnectionHandler.printStomp("Unsubscribed from \"/topic/VSTP_ALL\" (ID: \"" + appID + stompConnectionId + "-VSTP\")", false);
            subscribedVSTP = false;
        }
        else
        {
            client.subscribe("/topic/VSTP_ALL", "VSTP", handlerVSTP);
            client.addListener("/topic/VSTP_ALL", rateMonitor);
            subscribedVSTP = true;
        }
        NRODClient.updatePopupMenu();
    }
    public static void toggleTSR()
    {
        if (subscribedTSR)
        {
            client.unsubscribe("TSR");
            StompConnectionHandler.printStomp("Unsubscribed from \"/topic/TSR_ALL_ROUTE\" (ID: \"" + appID + stompConnectionId + "-TSR\")", false);
            subscribedTSR = false;
        }
        else
        {
            client.subscribe("/topic/TSR_ALL_ROUTE", "TSR", handlerTSR);
            client.addListener("/topic/TSR_ALL_ROUTE", rateMonitor);
            subscribedTSR = true;
        }
        NRODClient.updatePopupMenu();
    }
    public static void toggleTD()
    {
        if (subscribedTD)
        {
            client.unsubscribe("TD");
            StompConnectionHandler.printStomp("Unsubscribed from \"/topic/TD_ANG_SIG_AREA\" (ID: \"" + appID + stompConnectionId + "-TD\")", false);
            subscribedTSR = false;
        }
        else
        {
            client.subscribe("/topic/TD_ANG_SIG_AREA", "TD", handlerTD);
            client.addListener("/topic/TD_ANG_SIG_AREA", rateMonitor);
            subscribedTSR = true;
        }
        NRODClient.updatePopupMenu();
    }

    public static boolean isSubscribedRTPPM() { return subscribedRTPPM; }
    public static boolean isSubscribedMVT() { return subscribedMVT; }
    public static boolean isSubscribedVSTP() { return subscribedVSTP; }
    public static boolean isSubscribedTSR() { return subscribedTSR; }
    public static boolean isSubscribedTD() { return subscribedTD; }

    public static void printStompHeaders(Map<String, String> headers)
    {
        printStomp(
                String.format("Message received (topic: %s, time: %s, expires: %s, id: %s, ack: %s, subscription: %s, persistent: %s%s)",
                        String.valueOf(headers.get("destination")).replace("\\c", ":"),
                        NRODClient.sdfTime.format(new Date(Long.parseLong(headers.get("timestamp")))),
                        NRODClient.sdfTime.format(new Date(Long.parseLong(headers.get("expires")))),
                        String.valueOf(headers.get("message-id")).replace("\\c", ":"),
                        String.valueOf(headers.get("ack")).replace("\\c", ":"),
                        String.valueOf(headers.get("subscription")).replace("\\c", ":"),
                        String.valueOf(headers.get("persistent")).replace("\\c", ":"),
                        headers.size() > 7 ? ", + " + (headers.size()-7) + " more" : ""
                ), false);
    }
}