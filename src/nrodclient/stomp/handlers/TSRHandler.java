package nrodclient.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import jsonparser.JSONParser;
import net.ser1.stomp.Listener;
import nrodclient.NRODClient;
import nrodclient.stomp.StompConnectionHandler;

public class TSRHandler implements Listener
{
    private long lastMessageTime = 0;

    private static Listener instance = null;
    private TSRHandler() { lastMessageTime = System.currentTimeMillis(); }
    public static Listener getInstance()
    {
        if (instance == null)
            instance = new TSRHandler();

        return instance;
    }

    @Override
    public synchronized void message(Map<String, String> headers, String messageJSON)
    {
        File logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TSR" + File.separator + NRODClient.sdfDate.format(new Date()).replace("/", "-") + ".log");
        PrintWriter logFileWriter = null;

        try
        {
            logFile.getParentFile().mkdirs();
            logFile.createNewFile();

            logFileWriter = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "TSR"); }

        StompConnectionHandler.printStompHeaders(headers);

        Map<String, Object> message = (Map<String, Object>) ((Map<String, Object>) JSONParser.parseJSON(messageJSON).get("TSRBatchMsgV1")).get("TSRBatchMsg");
        String routeGroup = String.valueOf(message.get("routeGroup")) + " (" + String.valueOf(message.get("routeGroupCode")) + ")";
        List<Map<String, Object>> TSRs = ((List<Map<String, Object>>) message.get("tsr"));

        try
        {
            File file = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TSR" + File.separator + NRODClient.sdfDate.format(new Date(Long.parseLong(String.valueOf(message.get("timestamp"))))).replace("/", "-") + ".json");
            file.getParentFile().mkdirs();
            file.createNewFile();

            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file))))
            {
                bw.write(messageJSON + "\r\n");
            }
        }
        catch (IOException e) { NRODClient.printThrowable(e, "TSR"); }

        // Scope limit
        {
            String json = messageJSON.substring(messageJSON.indexOf("\"TSRBatchMsg\":{"), messageJSON.length()-2);
            printTSR("[" + routeGroup + "] " + json, false);
            if (logFileWriter != null)
                    logFileWriter.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(json));

            String header = String.format("[%s] Published: %s, WON Start: %s, WON End: %s, WON Name: %s, No of TSRs: %s",
                    routeGroup,
                    NRODClient.sdfDateTime.format(new Date(Long.parseLong(String.valueOf(message.get("publishDate"))))),
                    NRODClient.sdfDateTime.format(new Date(Long.parseLong(String.valueOf(message.get("WONStartDate"))))),
                    NRODClient.sdfDateTime.format(new Date(Long.parseLong(String.valueOf(message.get("WONEndDate"))))),
                    message.get("publishSource"),
                    TSRs.size()
            );
            printTSR(header, false);
            if (logFileWriter != null)
                logFileWriter.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(header));
        }

        for (Map<String, Object> tsr : TSRs)
        {
            if (!tsr.containsKey("TSRID") || !tsr.containsKey("TSRReference") || !tsr.containsKey("creationDate") ||
                    !tsr.containsKey("RouteCode")    || !tsr.containsKey("RouteOrder")    || !tsr.containsKey("FromLocation") ||
                    !tsr.containsKey("ToLocation")   || !tsr.containsKey("LineName")      || !tsr.containsKey("MileageFrom") ||
                    !tsr.containsKey("SubunitFrom")  || !tsr.containsKey("MileageTo")     || !tsr.containsKey("SubunitTo") ||
                    !tsr.containsKey("Direction")    || !tsr.containsKey("MovingMileage") || !tsr.containsKey("PassengerSpeed") ||
                    !tsr.containsKey("FreightSpeed") || !tsr.containsKey("ValidFromDate") || !tsr.containsKey("ValidToDate") ||
                    !tsr.containsKey("Reason")       || !tsr.containsKey("Requestor")     || !tsr.containsKey("Comments"))
            {
                printTSR("[" + routeGroup + "]   " + tsr.toString(), true);
                if (logFileWriter != null)
                    logFileWriter.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat("[" + routeGroup + "]   " + tsr.toString()));
            }
            else
            {
                String tsrStr = String.format("[%s]   TSR Id: %s, TSR Reference: %s, Created: %s, SA Route Code: %s (%s), "
                        + "Location (Name): %s - %s (%s), Location (Mileage): %sm%sc - %sm%sc, Direction: %s, Moving: %s, "
                        + "Speed (Pass/Freight): %s / %s, Dates: %s - %s, Reason: %s, Requestor: %s, Comments: %s",
                        routeGroup,
                        tsr.get("TSRID"),
                        tsr.get("TSRReference"),
                        NRODClient.sdfDateTime.format(new Date(Long.parseLong(String.valueOf(tsr.get("creationDate"))))),
                        tsr.get("RouteCode"), tsr.get("RouteOrder"),
                        tsr.get("FromLocation"), tsr.get("ToLocation"), tsr.get("LineName"),
                        tsr.get("MileageFrom"), tsr.get("SubunitFrom"), tsr.get("MileageTo"), tsr.get("SubunitTo"),
                        tsr.get("Direction"),
                        tsr.get("MovingMileage"),
                        tsr.get("PassengerSpeed"), tsr.get("FreightSpeed"),
                        NRODClient.sdfDateTime.format(new Date(Long.parseLong(String.valueOf(tsr.get("ValidFromDate"))))),
                        NRODClient.sdfDateTime.format(new Date(Long.parseLong(String.valueOf(tsr.get("ValidToDate"))))),
                        tsr.get("Reason") == null ? "not given" : tsr.get("Reason"),
                        tsr.get("Requestor") == null ? "unknown" : tsr.get("Requestor"),
                        tsr.get("Comments") == null ? "" : tsr.get("Comments")
                );
                printTSR(tsrStr, false);
                if (logFileWriter != null)
                    logFileWriter.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(tsrStr));
            }
        }

        if (logFileWriter != null)
            logFileWriter.close();


        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 1209600000; }

    private static void printTSR(String message, boolean toErr)
    {
        if (NRODClient.verbose)
        {
            if (toErr)
                NRODClient.printErr("[TSR] " + message);
            else
                NRODClient.printOut("[TSR] " + message);
        }
    }
}