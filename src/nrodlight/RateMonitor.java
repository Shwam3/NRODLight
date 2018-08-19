package nrodlight;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RateMonitor
{
    private final Map<String, AtomicInteger> rateMap = new HashMap<>();
    private final List<Double> stompDelays = new ArrayList<>();
    private final List<Double> descrDelays = new ArrayList<>();
    private final AtomicInteger wsPeakConns = new AtomicInteger();
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private final String[]     topics = {"StompMessages", "StompDelay", "TDDelay", "TDMessages", "WSConnections"};

    private static RateMonitor instance = null;
    private RateMonitor()
    {
        Date logDate = new Date();
        logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "RateMonitor" + File.separator + NRODLight.sdfDate.format(logDate).replace("/", "-") + ".csv");
        boolean fileExisted = logFile.exists();
        logFile.getParentFile().mkdirs();
        lastLogDate = NRODLight.sdfDate.format(logDate);

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODLight.printThrowable(e, "RateMonitor"); }

        if (!fileExisted)
        {
            logStream.print("time,");
            for (int i = 0; i < topics.length; i++)
                logStream.print(topics[i] + (i >= topics.length - 1 ? "" : ","));
            logStream.println();
        }

        for (String topic : topics)
            rateMap.put(topic, new AtomicInteger(0));

        long currTim = System.currentTimeMillis();
        Calendar wait = Calendar.getInstance();
        wait.setTimeInMillis(currTim);
        wait.set(Calendar.MILLISECOND, 0);
        wait.set(Calendar.SECOND, 0);
        wait.add(Calendar.MINUTE, 1);

        ScheduledFuture<?> sf = Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() ->
        {
            try
            {
                Date logDateNew = new Date();
                String logDateNewStr = NRODLight.sdfDate.format(logDateNew);
                if (!NRODLight.sdfDate.format(logDateNew).equals(lastLogDate))
                {
                    logStream.close();

                    logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "RateMonitor" + File.separator + logDateNewStr.replace("/", "-") + ".csv");
                    logFile.getParentFile().mkdirs();
                    lastLogDate = logDateNewStr;

                    try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
                    catch (IOException e) { NRODLight.printThrowable(e, "RateMonitor"); }

                    logStream.print("time,");
                    for (int i = 0; i < topics.length; i++)
                        logStream.print(topics[i] + (i >= topics.length - 1 ? "" : ","));
                    logStream.println();
                }

                logStream.print(NRODLight.sdfTime.format(logDateNew) + ",");
                for (int i = 0; i < topics.length; i++)
                {
                    String topic = topics[i];
                    if ("StompDelay".equals(topic))
                    {
                        List<Double> delays = new ArrayList<>(stompDelays);
                        stompDelays.clear();
                        double avg = delays.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        logStream.print(String.format("%.3f%s", avg, (i >= topics.length-1 ? "" : ",")));
                    }
                    else if ("TDDelay".equals(topic))
                    {
                        List<Double> delays = new ArrayList<>(descrDelays);
                        descrDelays.clear();
                        double avg = delays.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        logStream.print(String.format("%.3f%s", avg, (i >= topics.length-1 ? "" : ",")));
                    }
                    else if ("WSConnections".equals(topic))
                    {
                        int count = NRODLight.webSocket != null ? NRODLight.webSocket.getConnections().size() : 0;
                        logStream.print(wsPeakConns.getAndSet(count) + (i >= topics.length-1 ? "" : ","));
                    }
                    else
                    {
                        long count = rateMap.get(topic).getAndSet(0);
                        logStream.print(count + (i >= topics.length-1 ? "" : ","));
                    }
                }
                logStream.println();
            }
            catch(Exception e) { NRODLight.printThrowable(e, "RateMonitor"); }
        }, wait.getTimeInMillis() - currTim, 1000 * 60, TimeUnit.MILLISECONDS);
    }

    public static RateMonitor getInstance()
    {
        if (instance == null)
            instance = new RateMonitor();

        return instance;
    }
    
    public void onTDMessage(Double delayStomp, Double delayTD, int msgCount)
    {
        stompDelays.add(delayStomp);
        descrDelays.add(delayStomp);
        rateMap.get("StompMessages").incrementAndGet();
        rateMap.get("TDMessages").addAndGet(msgCount);
    }

    public void onWSOpen()
    {
        wsPeakConns.incrementAndGet();
    }
}