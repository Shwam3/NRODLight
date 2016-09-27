package nrodclient.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Map;
import nrodclient.NRODClient;
import nrodclient.stomp.NRODListener;
import nrodclient.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONObject;

public class TSRHandler implements NRODListener
{
    private long lastMessageTime = 0;

    private static NRODListener instance = null;
    private TSRHandler() { lastMessageTime = System.currentTimeMillis(); }
    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new TSRHandler();

        return instance;
    }

    @Override
    public synchronized void message(Map<String, String> headers, String messageJSON)
    {
        File logFile = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "TSR" + File.separator + NRODClient.sdfDate.format(new Date()).replace("/", "-") + ".log");
        PrintWriter logFileWriter = null;

        try
        {
            if (!logFile.exists())
            {
                logFile.getParentFile().mkdirs();
                logFile.createNewFile();
            }

            logFileWriter = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "TSR"); }

        StompConnectionHandler.printStompHeaders(headers);

        JSONObject message = new JSONObject(messageJSON).getJSONObject("TSRBatchMsgV1").getJSONObject("TSRBatchMsg");
        String routeGroup = message.getString("routeGroup") + " (" + message.getString("routeGroupCode") + ")";
        JSONArray TSRs = message.getJSONArray("tsr");

        try
        {
            File file = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "TSR" + File.separator + NRODClient.sdfDate.format(new Date(/*Long.parseLong(String.valueOf(message.get("timestamp")))*/)).replace("/", "-") + ".json");
            file.getParentFile().mkdirs();
            file.createNewFile();

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true)))
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
                    NRODClient.sdfDateTime.format(new Date(Long.parseLong(message.getString("publishDate")))),
                    NRODClient.sdfDateTime.format(new Date(Long.parseLong(message.getString("WONStartDate")))),
                    NRODClient.sdfDateTime.format(new Date(Long.parseLong(message.getString("WONEndDate")))),
                    message.getString("publishSource"),
                    TSRs.length()
            );
            printTSR(header, false);
            if (logFileWriter != null)
                logFileWriter.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(header));
        }

        for (Object tsrObj : TSRs)
        {
            JSONObject tsr = (JSONObject) tsrObj;
            
            if (!tsr.has("TSRID") || !tsr.has("TSRReference") || !tsr.has("creationDate") ||
                    !tsr.has("RouteCode")    || !tsr.has("RouteOrder")    || !tsr.has("FromLocation") ||
                    !tsr.has("ToLocation")   || !tsr.has("LineName")      || !tsr.has("MileageFrom") ||
                    !tsr.has("SubunitFrom")  || !tsr.has("MileageTo")     || !tsr.has("SubunitTo") ||
                    !tsr.has("Direction")    || !tsr.has("MovingMileage") || !tsr.has("PassengerSpeed") ||
                    !tsr.has("FreightSpeed") || !tsr.has("ValidFromDate") || !tsr.has("ValidToDate") ||
                    !tsr.has("Reason")       || !tsr.has("Requestor")     || !tsr.has("Comments"))
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
                        tsr.getString("TSRID"),
                        tsr.getString("TSRReference"),
                        NRODClient.sdfDateTime.format(new Date(Long.parseLong(tsr.getString("creationDate")))),
                        tsr.getString("RouteCode"), tsr.getString("RouteOrder"),
                        tsr.getString("FromLocation"), tsr.getString("ToLocation"), tsr.getString("LineName"),
                        tsr.getString("MileageFrom"), tsr.getString("SubunitFrom"), tsr.getString("MileageTo"), tsr.getString("SubunitTo"),
                        tsr.getString("Direction"),
                        tsr.getString("MovingMileage"),
                        tsr.getString("PassengerSpeed"), tsr.getString("FreightSpeed"),
                        NRODClient.sdfDateTime.format(new Date(Long.parseLong(tsr.getString("ValidFromDate")))),
                        NRODClient.sdfDateTime.format(new Date(Long.parseLong(tsr.getString("ValidToDate")))),
                        tsr.optString("Reason", "not given"),
                        tsr.optString("Requestor", "unknown"),
                        tsr.optString("Comments", "")
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