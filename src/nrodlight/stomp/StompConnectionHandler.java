package nrodlight.stomp;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.security.auth.login.LoginException;
import net.ser1.stomp.Listener;
import net.ser1.stomp.Version;
import nrodlight.NRODLight;
import nrodlight.stomp.handlers.ErrorHandler;
import nrodlight.stomp.handlers.TDHandler;

public class StompConnectionHandler
{
    private static StompClient client;

    private static ScheduledExecutorService executor = null;
    private static int    maxTimeoutWait = 300;
    private static int    timeoutWait = 10;
    private static int    wait = 0;
    public  static long   lastMessageTimeGeneral = System.currentTimeMillis();
    private static String appID = "";

    private static boolean subscribedTD    = false;

    private static final Listener handlerTD = TDHandler.getInstance();

    public static boolean connect() throws LoginException, IOException
    {
        printStomp(Version.VERSION, false);

        subscribedTD = false;

        NRODLight.reloadConfig();
        String username = NRODLight.config.optString("NROD_Username", "");
        String password = NRODLight.config.optString("NROD_Password", "");

        appID = username + "-NRODLight-" + NRODLight.config.optString("NROD_Instance_ID", "uid") + "-v" + NRODLight.VERSION;

        if ((username != null && username.equals("")) || (password != null && password.equals("")))
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
        toggleTD();

        try { Thread.sleep(100); }
        catch (InterruptedException e) {}

        return true;
    }

    public static void disconnect()
    {
        if (client != null && isConnected() && !isClosed())
            client.disconnect();

        subscribedTD = false;
        
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
        return 30000;
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
            catch(InterruptedException e) {}
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

                    long timeTD = TDHandler.getInstance().getTimeout();
                    boolean timedOutTD = timeTD >= TDHandler.getInstance().getTimeoutThreshold();

                    printStomp(String.format("  TD Timeout: %02d:%02d:%02d (Threshold: %ss)",
                                (timeTD / (1000 * 60 * 60)) % 24,
                                (timeTD / (1000 * 60)) % 60,
                                (timeTD / 1000) % 60,
                                (TDHandler.getInstance().getTimeoutThreshold() / 1000)),
                            timedOutTD);

                    if (timedOutTD)
                    {
                        if (timeTD >= TDHandler.getInstance().getTimeoutThreshold()*1.5)
                        {
                            if (client != null)
                                disconnect();

                            wrappedConnect();
                        }
                    }
                    else
                        printStomp("No problems", false);
                }
            }
            else
                wait += 10;
        }, 10, 10, TimeUnit.SECONDS);
    }

    public static void setMaxTimeoutWait(int maxTimeoutWait)
    {
        StompConnectionHandler.maxTimeoutWait = Math.max(600, maxTimeoutWait);
    }

    public static void printStomp(String message, boolean toErr)
    {
        if (toErr)
            NRODLight.printErr("[Stomp] " + message);
        else
            NRODLight.printOut("[Stomp] " + message);
    }

    public static String getConnectionName() { return appID; }

    public static void ack(String ackId)
    {
        if (client != null)
            client.ack(ackId);
    }

    public static void toggleTD()
    {
        if (subscribedTD)
        {
            client.unsubscribe("TD");
            StompConnectionHandler.printStomp("Unsubscribed from \"/topic/TD_ALL_SIG_AREA\" (ID: \"" + appID + "-TD\")", false);
            subscribedTD = false;
        }
        else
        {
            client.subscribe("/topic/TD_ALL_SIG_AREA", "TD", handlerTD);
            //client.addListener("/topic/TD_ALL_SIG_AREA", rateMonitor);
            subscribedTD = true;
        }
    }

    public static boolean isSubscribedTD() { return subscribedTD; }

    public static void printStompHeaders(Map<String, String> headers)
    {
        printStomp(String.format("Message received (topic: %s, time: %s, delay: %s, expires: %s, id: %s, ack: %s, subscription: %s, persistent: %s%s)",
                String.valueOf(headers.get("destination")).replace("\\c", ":"),
                NRODLight.sdfTime.format(new Date(Long.parseLong(headers.get("timestamp")))),
                (System.currentTimeMillis() - Long.parseLong(headers.get("timestamp")))/1000f + "s",
                NRODLight.sdfTime.format(new Date(Long.parseLong(headers.get("expires")))),
                String.valueOf(headers.get("message-id")).replace("\\c", ":"),
                String.valueOf(headers.get("ack")).replace("\\c", ":"),
                String.valueOf(headers.get("subscription")).replace("\\c", ":"),
                String.valueOf(headers.get("persistent")).replace("\\c", ":"),
                headers.size() > 7 ? ", + " + (headers.size()-7) + " more" : ""
            ), false);
    }
}