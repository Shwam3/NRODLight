package nrodclient.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import jsonparser.JSONParser;
import net.ser1.stomp.Listener;
import nrodclient.NRODClient;
import nrodclient.stomp.StompConnectionHandler;

public class MVTHandler implements Listener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private long lastMessageTime = 0;

    private static Listener instance = null;
    private MVTHandler()
    {
        Date logDate = new Date(System.currentTimeMillis());
        logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "Movement" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = NRODClient.sdfDate.format(logDate);

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODClient.printThrowable(e, "Movement"); }

        lastMessageTime = System.currentTimeMillis();
    }

    public static Listener getInstance()
    {
        if (instance == null)
            instance = new MVTHandler();

        return instance;
    }

    @Override
    public void message(Map<String, String> headers, String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        List<Map> messageList = (List<Map>) JSONParser.parseJSON("{\"MVTMessage\":" + message + "}").get("MVTMessage");

        for (Map<String, Map<String, Object>> map : messageList)
        {
            Map<String, Object> header = map.get("header");
            Map<String, Object> body   = map.get("body");

            switch (String.valueOf(header.get("msg_type")))
            {
                case "0001": // Activation
                    printMovement(String.format("Train %s / %s (%s) activated %s at %s (%s schedule from %s, Starts at %s, sch id: %s, TOPS address: %s)",
                            String.valueOf(body.get("train_id")),
                            String.valueOf(body.get("train_uid")).replace(" ", "O"),
                            String.valueOf(body.get("train_id")).substring(2, 6),
                            String.valueOf(body.get("train_call_type")).replace("AUTOMATIC", "automatically").replace("MANUAL", "manually"),
                            NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("creation_timestamp"))))),
                            String.valueOf(body.get("schedule_type")).replace("P", "Planned").replace("O", "Overlayed").replace("N", "Short Term").replace("C", "Cancelled"),
                            String.valueOf(body.get("schedule_source")).replace("C", "CIF/ITPS").replace("V", "VSTP/TOPS"),
                            String.valueOf(body.get("origin_dep_timestamp")),
                            String.valueOf(body.get("schedule_wtt_id")),
                            String.valueOf(body.get("train_file_address"))
                        ), false);
                    break;

                case "0002": // Cancellation
                    printMovement(String.format("Train %s / %s (%s) cancelled %s at %s with reason code %s (toc: %s / %s, dep: %s @ %s, from: %s, at %s, tops file: %s)",
                            String.valueOf(body.get("train_id")),
                            String.valueOf(body.get("train_service_code")),
                            String.valueOf(body.get("train_id")).substring(2, 6),
                            String.valueOf(body.get("canx_type")).replace("ON CALL", "upon activation").replace("OUT OF PLAN", "spontaneously").toLowerCase(),
                            NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("canx_timestamp"))))),
                            String.valueOf(body.get("canx_reason_code")),
                            String.valueOf(body.get("toc_id")),
                            String.valueOf(body.get("division_code")),
                            String.valueOf(body.get("orig_loc_stanox")),
                            String.valueOf(body.get("orig_loc_timestamp")).isEmpty() ? "n/a" : NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("orig_loc_timestamp"))))),
                            String.valueOf(body.get("loc_stanox")),
                            NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("dep_timestamp"))))),
                            String.valueOf(body.get("train_file_address"))
                        ), false);
                    break;

                case "0003": // Movement
                    printMovement(String.format("Train %s / %s (%s) %s %s %s%s at %s %s(plan %s, GBTT %s, plat: %s, line: %s, toc: %s / %s)",
                            String.valueOf(body.get("train_id")),
                            String.valueOf(body.get("train_service_code")),
                            String.valueOf(body.get("train_id")).substring(2, 6),
                            String.valueOf(body.get("event_type")).replace("ARRIVAL", "arrived at").replace("DEPARTURE", "departed from"),
                            String.valueOf(body.get("loc_stanox")),
                            String.valueOf(body.get("timetable_variation")).equals("0") ? "" : String.valueOf(body.get("timetable_variation")) + " mins ",
                            String.valueOf(body.get("variation_status")).toLowerCase(),
                            String.valueOf(body.get("actual_timestamp")).isEmpty() ? "N/A" : NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("actual_timestamp"))))),
                            String.valueOf(body.get("train_terminated")).replace("true", "and terminated ").replace("false", ""),
                            String.valueOf(body.get("planned_timestamp")).isEmpty() ? "N/A" : NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("planned_timestamp"))))),
                            String.valueOf(body.get("gbtt_timestamp")).isEmpty() ? "N/A" : NRODClient.sdfTime.format(new Date(Long.parseLong(String.valueOf(body.get("gbtt_timestamp"))))),
                            String.valueOf(body.get("platform")).trim().isEmpty() ? "  " : String.valueOf(body.get("platform")).trim(),
                            String.valueOf(body.get("line_ind")).trim().isEmpty() ? "" : String.valueOf(body.get("line_ind")).trim(),
                            String.valueOf(body.get("toc_id")),
                            String.valueOf(body.get("division_code"))
                            ), false);
                    break;

                case "0005": // Reinstatement (De-Cancellation)
                    printMovement("Reinstatement: " + body.toString(), false);
                    break;

                case "0006": // Change Origin
                    printMovement("Origin Change: " + body.toString(), false);
                    break;

                case "0007": // Change Identity
                    printMovement("Identity Change: " + body.toString(), false);
                    break;

                case "0004": // UID Train
                case "0008": // Change Loaction
                default:     // Other (e.g. null)
                    printMovement("Erronous message received (" + String.valueOf(header.get("msg_type")) + ")", true);
                    break;
            }

        }

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 30000; }

    private static synchronized void printMovement(String message, boolean toErr)
    {
        if (NRODClient.verbose)
        {
            if (toErr)
                NRODClient.printErr("[Movement] ".concat(message));
            else
                NRODClient.printOut("[Movement] ".concat(message));
        }

        if (!lastLogDate.equals(NRODClient.sdfDate.format(new Date())))
        {
            logStream.close();

            Date logDate = new Date();
            lastLogDate = NRODClient.sdfDate.format(logDate);

            logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "Movement" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "Movement"); }
        }

        logStream.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(toErr ? "!!!> " : "").concat(message).concat(toErr ? " <!!!" : ""));
    }
}