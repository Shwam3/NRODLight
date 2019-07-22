package nrodlight.stomp;

import net.ser1.stomp.Listener;
import net.ser1.stomp.Version;
import nrodlight.NRODLight;
import nrodlight.stomp.handlers.ErrorHandler;
import nrodlight.stomp.handlers.TDHandler;
import nrodlight.stomp.handlers.TRUSTHandler;
import nrodlight.stomp.handlers.VSTPHandler;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StompConnectionHandler
{
    private static StompClient client;

    private static ScheduledExecutorService executor = null;
    private static final int maxTimeoutWait = 300;
    private static int    timeoutWait = 10;
    private static int    wait = 0;
    public  static long   lastMessageTimeGeneral = System.currentTimeMillis();
    private static String appID = "";

    private static boolean subscribedTRUST = false;
    private static boolean subscribedVSTP  = false;
    private static boolean subscribedTD    = false;

    private static final Listener handlerTRUST = TRUSTHandler.getInstance();
    private static final Listener handlerVSTP  = VSTPHandler.getInstance();
    private static final Listener handlerTD    = TDHandler.getInstance();

    public static boolean connect() throws LoginException, IOException
    {
        printStomp(Version.VERSION, false);

        subscribedTRUST = false;
        subscribedVSTP  = false;
        subscribedTD    = false;

        NRODLight.reloadConfig();
        String username = NRODLight.config.optString("NROD_Username", "");
        String password = NRODLight.config.optString("NROD_Password", "");

        appID = username + "-NRODLight-" + NRODLight.config.optString("NROD_Instance_ID", "uid") + "-v" + NRODLight.VERSION;

        if ("".equals(username) || "".equals(password))
        {
            printStomp("Error retreiving login details (usr: " + username + ", pwd: " + password + ")", true);
            return false;
        }

        startTimeoutTimer();
        client = new StompClient("datafeeds.networkrail.co.uk", 61618, username, password, appID);

        if (client.isConnected())
        {
            printStomp("Connected to \"datafeeds.networkrail.co.uk:61618\"", false);
            printStomp("  ID:       " + appID, false);
            printStomp("  Username: " + username, false);
            printStomp("  Password: " + password, false);
        }
        else
        {
            printStomp("Could not connect to network rail's servers", true);
            return false;
        }

        client.addErrorListener(new ErrorHandler());
        toggleTRUST();
        toggleVSTP();
        toggleTD();

        try { Thread.sleep(100); }
        catch (InterruptedException ignored) {}

        return true;
    }

    public static void disconnect()
    {
        if (client != null && isConnected() && !isClosed())
        {
            client.disconnect();

            try { client.awaitClose(); }
            catch (InterruptedException ignored) {}
        }

        subscribedTRUST = false;
        subscribedVSTP  = false;
        subscribedTD    = false;

        printStomp("Disconnected", false);
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

        if (subscribedTRUST || subscribedTD)
            threshold = 30000;
        else
            threshold = 30000;

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
        catch (IOException e)          { printStomp("IO Exception:", true); NRODLight.printThrowable(e, "Stomp"); }
        catch (Exception e)            { printStomp("Exception:", true); NRODLight.printThrowable(e, "Stomp"); }

        return false;
    }

    private static void startTimeoutTimer()
    {
        if (executor != null)
        {
            executor.shutdown();

            try { executor.awaitTermination(2, TimeUnit.SECONDS); }
            catch(InterruptedException ignored) {}
        }

        executor = Executors.newScheduledThreadPool(1);

        // General timeout
        executor.scheduleWithFixedDelay(() ->
        {
            if (wait >= timeoutWait)
            {
                wait = 0;

                long time = System.currentTimeMillis() - lastMessageTimeGeneral;

                printStomp(String.format("General Timeout: %02d:%02d:%02d (Threshold: %ss)", (time / (1000 * 60 * 60)) % 24, (time / (1000 * 60)) % 60, (time / 1000) % 60, (getTimeoutThreshold() / 1000)), isTimedOut() || !isConnected() || isClosed());

                if (isTimedOut() || !isConnected())
                {
                    timeoutWait = Math.min(maxTimeoutWait, timeoutWait + 10);

                    printStomp((isTimedOut() ? "Timed Out" : "") + (isTimedOut() && isClosed() ? ", " : "") + (isClosed() ? "Closed" : "") + ((isTimedOut() || isClosed()) && !isConnected() ? " & " : "") + (!isConnected() ? "Disconnected" : "") + " (" + timeoutWait + "s)", true);

                    try
                    {
                        if (client != null)
                            disconnect();

                        connect();
                    }
                    catch (LoginException e) { printStomp("Login Exception: " + e.getLocalizedMessage().split("\n")[0], true);}
                    catch (IOException e)    { printStomp("IO Exception reconnecting", true); NRODLight.printThrowable(e, "Stomp"); }
                    catch (Exception e)      { printStomp("Exception reconnecting", true);  NRODLight.printThrowable(e, "Stomp"); }
                }
                else
                {
                    timeoutWait = 10;

                    long timeTRUST = TRUSTHandler.getInstance().getTimeout();
                    long timeVSTP  = VSTPHandler.getInstance().getTimeout();
                    long timeTD    = TDHandler.getInstance().getTimeout();
                    boolean timedOutTRUST = timeTRUST >= TRUSTHandler.getInstance().getTimeoutThreshold();
                    boolean timedOutVSTP  = timeVSTP  >= VSTPHandler.getInstance().getTimeoutThreshold();
                    boolean timedOutTD    = timeTD    >= TDHandler.getInstance().getTimeoutThreshold();

                    printStomp(String.format("  TRUST Timeout: %02d:%02d:%02d (Threshold: %ss)",
                                (timeTRUST / (1000 * 60 * 60)) % 24,
                                (timeTRUST / (1000 * 60)) % 60,
                                (timeTRUST / 1000) % 60,
                                (TRUSTHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutTRUST);
                    printStomp(String.format("  VSTP Timeout:  %02d:%02d:%02d (Threshold: %ss)",
                                (timeVSTP / (1000 * 60 * 60)) % 24,
                                (timeVSTP / (1000 * 60)) % 60,
                                (timeVSTP / 1000) % 60,
                                (VSTPHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutVSTP);
                    printStomp(String.format("  TD Timeout:    %02d:%02d:%02d (Threshold: %ss)",
                                (timeTD / (1000 * 60 * 60)) % 24,
                                (timeTD / (1000 * 60)) % 60,
                                (timeTD / 1000) % 60,
                                (TDHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutTD);

                    if (timedOutTRUST || timedOutVSTP || timedOutTD)
                    {
                        if (timeTRUST >= TRUSTHandler.getInstance().getTimeoutThreshold()*1.5 ||
                            timeVSTP  >= VSTPHandler.getInstance().getTimeoutThreshold()*1.5 ||
                            timeTD    >= TDHandler.getInstance().getTimeoutThreshold()*1.5)
                        {
                            if (client != null)
                                disconnect();

                            wrappedConnect();
                        }
                    }
                    else
                    {
                        if (timedOutTRUST)
                        {
                            toggleTRUST();

                            try { Thread.sleep(50); }
                            catch(InterruptedException ignored) {}

                            toggleTRUST();
                        }
                        if (timedOutVSTP)
                        {
                            toggleVSTP();

                            try { Thread.sleep(50); }
                            catch(InterruptedException ignored) {}

                            toggleVSTP();
                        }
                        if (timedOutTD)
                        {
                            toggleTD();

                            try { Thread.sleep(50); }
                            catch(InterruptedException ignored) {}

                            toggleTD();
                        }
                    }

                    if (!timedOutTRUST && !timedOutTD)
                        printStomp("No problems", false);
                }
            }
            else
                wait += 10;
        }, 10, 10, TimeUnit.SECONDS);
    }

    public static void printStomp(String message, boolean toErr)
    {
        if (toErr)
            NRODLight.printErr("[Stomp] " + message);
        else
            NRODLight.printOut("[Stomp] " + message);
    }

    public static void ack(String ackId)
    {
        if (client != null)
            client.ack(ackId);
    }

    private static void toggleTRUST()
    {
        if (subscribedTRUST)
        {
            client.unsubscribe("TRUST");
            printStomp("Unsubscribed from \"/topic/TRAIN_MVT_ALL_TOC\" (ID: \"" + appID + "-TRUST\")", false);
            subscribedTRUST = false;
        }
        else
        {
            client.subscribe("/topic/TRAIN_MVT_ALL_TOC", "TRUST", handlerTRUST);
            subscribedTRUST = true;
        }
    }
    private static void toggleVSTP()
    {
        if (subscribedVSTP)
        {
            client.unsubscribe("VSTP");
            printStomp("Unsubscribed from \"/topic/VSTP_ALL\" (ID: \"" + appID + "-VSTP\")", false);
            subscribedVSTP = false;
        }
        else
        {
            client.subscribe("/topic/VSTP_ALL", "VSTP", handlerVSTP);
            subscribedVSTP = true;
        }
    }
    private static void toggleTD()
    {
        if (subscribedTD)
        {
            client.unsubscribe("TD");
            printStomp("Unsubscribed from \"/topic/TD_ALL_SIG_AREA\" (ID: \"" + appID + "-TD\")", false);
            subscribedTD = false;
        }
        else
        {
            client.subscribe("/topic/TD_ALL_SIG_AREA", "TD", handlerTD);
            subscribedTD = true;
        }
    }

    public static void printStompHeaders(Map<String, String> headers)
    {
        printStomp(
            String.format("Message received (%s, id: %s, ack: %s, subscription: %s%s)",
                String.valueOf(headers.get("destination")).replace("\\c", ":"),
                String.valueOf(headers.get("message-id")).replace("\\c", ":"),
                String.valueOf(headers.get("ack")).replace("\\c", ":"),
                String.valueOf(headers.get("subscription")).replace("\\c", ":"),
                headers.size() > 7 ? ", + " + (headers.size()-4) + " more" : ""
            ), false);
    }
}
