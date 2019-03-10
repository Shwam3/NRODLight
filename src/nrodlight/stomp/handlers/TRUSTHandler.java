package nrodlight.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Map;
import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.stomp.NRODListener;
import nrodlight.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONObject;

public class TRUSTHandler implements NRODListener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private        long        lastMessageTime = 0;

    private static NRODListener instance = null;
    private TRUSTHandler()
    {
        String logDate = NRODLight.sdfDate.format(new Date());
        lastLogDate = logDate;
        logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TRUST" + File.separator + logDate.replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODLight.printThrowable(e, "TRUST"); }

        lastMessageTime = System.currentTimeMillis();
    }

    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new TRUSTHandler();

        return instance;
    }

    @Override
    public void message(Map<String, String> headers, String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        JSONArray messageList = new JSONArray(message);

        for (Object msgObj : messageList)
        {
            JSONObject map = (JSONObject) msgObj;
            JSONObject header = map.getJSONObject("header");
            JSONObject body   = map.getJSONObject("body");

            /*switch (header.getString("msg_type"))
            {
                case "0001": // Activation
                    printTRUST(String.format("Train %s / %s (%s) activated %s at %s (%s schedule from %s, Starts at %s, sch id: %s, TOPS address: %s)",
                            body.getString("train_id"),
                            body.getString("train_uid").replace(" ", "O"),
                            body.getString("train_id").substring(2, 6),
                            body.getString("train_call_type").replace("AUTOMATIC", "automatically").replace("MANUAL", "manually"),
                            NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("creation_timestamp")))),
                            body.getString("schedule_type").replace("P", "Planned").replace("O", "Overlayed").replace("N", "Short Term").replace("C", "Cancelled"),
                            body.getString("schedule_source").replace("C", "CIF/ITPS").replace("V", "VSTP/TOPS"),
                            body.getString("origin_dep_timestamp"),
                            body.getString("schedule_wtt_id"),
                            body.optString("train_file_address", "N/A")
                        ), false);
                    break;

                case "0002": // Cancellation
                    printTRUST(String.format("Train %s / %s (%s) cancelled %s at %s due to %s (toc: %s / %s, dep: %s @ %s, from: %s, at %s, tops file: %s)",
                            body.getString("train_id"),
                            body.getString("train_service_code"),
                            body.getString("train_id").substring(2, 6),
                            body.getString("canx_type").replace("ON CALL", "upon activation").replace("OUT OF PLAN", "spontaneously").toLowerCase(),
                            NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("canx_timestamp")))),
                            body.getString("canx_reason_code"),
                            body.getString("toc_id"),
                            body.getString("division_code"),
                            body.getString("orig_loc_stanox"),
                            body.getString("orig_loc_timestamp").isEmpty() ? "n/a" : NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("orig_loc_timestamp")))),
                            body.getString("loc_stanox"),
                            NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("dep_timestamp")))),
                            body.optString("train_file_address","N/A")
                        ), false);
                    break;

                case "0003": // Movement
                    printTRUST(String.format("Train %s / %s (%s) %s %s %s%s at %s %s(plan %s, GBTT %s, plat: %s, line: %s, toc: %s / %s)",
                            body.getString("train_id"),
                            body.getString("train_service_code"),
                            body.getString("train_id").substring(2, 6),
                            body.getString("event_type").replace("ARRIVAL", "arrived at").replace("DEPARTURE", "departed from"),
                            body.getString("loc_stanox"),
                            body.getString("timetable_variation").equals("0") ? "" : body.getString("timetable_variation") + " mins ",
                            body.getString("variation_status").toLowerCase(),
                            body.getString("actual_timestamp").isEmpty() ? "N/A" : NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("actual_timestamp")))),
                            body.getString("train_terminated").replace("true", "and terminated ").replace("false", ""),
                            body.getString("planned_timestamp").isEmpty() ? "N/A" : NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("planned_timestamp")))),
                            body.getString("gbtt_timestamp").isEmpty() ? "N/A" : NRODLight.sdfTime.format(new Date(Long.parseLong(body.getString("gbtt_timestamp")))),
                            body.getString("platform").trim().isEmpty() ? "  " : body.getString("platform").trim(),
                            body.getString("line_ind").trim().isEmpty() ? "" : body.getString("line_ind").trim(),
                            body.getString("toc_id"),
                            body.getString("division_code")
                            ), false);
                    break;

                case "0005": // Reinstatement (De-Cancellation)
                    printTRUST("Reinstatement: " + body.toString(), false);
                    break;

                case "0006": // Change Origin
                    printTRUST("Origin Change: " + body.toString(), false);
                    break;

                case "0007": // Change Identity
                    printTRUST("Identity Change: " + body.toString(), false);
                    break;

                case "0004": // UID Train
                case "0008": // Change Location
                default:     // Other
                    printTRUST("Erronous message received (" + header.getString("msg_type") + ")", true);
                    break;
            }*/

            printTRUST(map.toString(), false);
        }

        RateMonitor.getInstance().onTRUSTMessage(messageList.length());
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 30000; }

    private static synchronized void printTRUST(String message, boolean toErr)
    {
        printTRUST(message, toErr, System.currentTimeMillis());
    }

    private static void printTRUST(String message, boolean toErr, long timestamp)
    {
        if (NRODLight.verbose)
        {
            if (toErr)
                NRODLight.printErr("[TRUST] " + message);
            else
                NRODLight.printOut("[TRUST] " + message);
        }

        String newDate = NRODLight.sdfDate.format(new Date());
        if (!lastLogDate.equals(newDate))
        {
            logStream.close();

            lastLogDate = newDate;

            logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TRUST" + File.separator + newDate.replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODLight.printThrowable(e, "TRUST"); }
        }

        logStream.println("[" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + (toErr ? "!!!> " : "") + message + (toErr ? " <!!!" : ""));
    }
}