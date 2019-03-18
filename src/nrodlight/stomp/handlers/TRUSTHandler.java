package nrodlight.stomp.handlers;

import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.db.DBHandler;
import nrodlight.stomp.NRODListener;
import nrodlight.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

public class TRUSTHandler implements NRODListener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private        long        lastMessageTime = 0;
    private static final SimpleDateFormat cifTime = new SimpleDateFormat("HHmmss");

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

        try
        {
            Connection conn = DBHandler.getConnection();
            PreparedStatement ps0001 = conn.prepareStatement("INSERT INTO activations (train_id,train_id_current,start_timestamp,schedule_uid,schedule_date_from," +
                    "schedule_date_to,stp_indicator,schedule_source,wtt_id,creation_timestamp,next_expected_update,last_update) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE next_expected_update=?, start_timestamp=?, last_update=?");
            PreparedStatement ps0002_0005 = conn.prepareStatement("UPDATE activations SET cancelled=?, last_update=? WHERE train_id=?");
            PreparedStatement ps0003_update = conn.prepareStatement("UPDATE activations SET current_delay=?, last_update=?, " +
                    "next_expected_update=?, finished=?, off_route=0 WHERE train_id=?");
            //PreparedStatement ps0003_get_dep = conn.prepareStatement("SELECT scheduled_arrival,scheduled_departure,schedules_pass FROM schedule_locations l " +
            //        "INNER JOIN activations a ON l.schedule_uid=a.schedule_uid AND a.schedule_date_from=l.date_from AND " +
            //        "l.stp_indicator=a.stp_indicator LEFT JOIN corpus c ON l.tiploc=c.tiploc WHERE a.train_id=? AND " +
            //        "stanox=? AND scheduled_arrival=? OR scheduled_pass=?)");
            PreparedStatement ps0003_offroute = conn.prepareStatement("UPDATE activations SET off_route=1, last_update=?, finished=? WHERE train_id=?");
            PreparedStatement ps0006 = conn.prepareStatement("UPDATE activations SET next_expected_update=?, last_update=? WHERE train_id=?");
            PreparedStatement ps0007 = conn.prepareStatement("UPDATE activations SET train_id_current=?, last_update=? WHERE train_id=?");
            PreparedStatement ps0008 = conn.prepareStatement("UPDATE activations SET last_update=? WHERE train_id=?");

            for (Object msgObj : messageList)
            {
                JSONObject map = (JSONObject)msgObj;
                JSONObject header = map.getJSONObject("header");
                JSONObject body = map.getJSONObject("body");
                try
                {
                    switch (header.getString("msg_type"))
                    {
                        case "0001": // Activation
                            ps0001.setString(1, body.getString("train_id"));
                            ps0001.setString(2, body.getString("train_id"));
                            long origin_dep_timestamp = fixTimestamp(Long.parseLong(body.getString("origin_dep_timestamp")));
                            ps0001.setLong(3, origin_dep_timestamp);
                            ps0001.setString(4, body.getString("train_uid").replace(" ", "O"));
                            ps0001.setString(5, body.getString("schedule_start_date").substring(2).replace("-", ""));
                            ps0001.setString(6, body.getString("schedule_end_date").substring(2).replace("-", ""));
                            if ("O".equals(body.getString("schedule_type")))
                                ps0001.setString(7, "P");
                            else if ("P".equals(body.getString("schedule_type")))
                                ps0001.setString(7, "O");
                            else
                                ps0001.setString(7, body.getString("schedule_type"));
                            ps0001.setString(8, body.getString("schedule_source"));
                            ps0001.setString(9, body.getString("schedule_wtt_id"));
                            long creation_timestamp = Long.parseLong(body.getString("creation_timestamp"));
                            ps0001.setLong(10, creation_timestamp);
                            ps0001.setLong(11, origin_dep_timestamp);
                            ps0001.setLong(12, creation_timestamp);

                            ps0001.setLong(13, origin_dep_timestamp);
                            ps0001.setLong(14, origin_dep_timestamp);
                            ps0001.setLong(15, creation_timestamp);

                            ps0001.execute();
                            break;

                        case "0002": // Cancellation
                            ps0002_0005.setBoolean(1, true);
                            ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("canx_timestamp"))));
                            ps0002_0005.setString(3, body.getString("train_id"));

                            ps0002_0005.execute();
                            break;

                        case "0003": // Movement
                            if ("false".equals(body.getString("correction_ind")))
                            {
                                if ("true".equals(body.getString("offroute_ind")) || "OFF ROUTE".equals(body.getString("timetable_variation")))
                                {
                                    ps0003_offroute.setLong(1, System.currentTimeMillis());
                                    ps0003_offroute.setBoolean(2, "true".equals(body.getString("train_terminated")));
                                    ps0003_offroute.setString(3, body.getString("train_id"));
                                    ps0003_offroute.execute();
                                }
                                else if ("DEPARTURE".equals(body.get("event_type")) || "ARRIVAL".equals(body.get("event_type")))
                                {
                                    ps0003_update.setInt(1, Integer.parseInt(body.getString("timetable_variation")) *
                                            ("EARLY".equals(body.getString("variation_status")) ? - 1 : 1));
                                    ps0003_update.setLong(2, System.currentTimeMillis());
                                    ps0003_update.setLong(3, fixTimestamp(Long.parseLong(body.getString("actual_timestamp"))) +
                                            ("".equals(body.getString("next_report_run_time")) ? 0L : Long.parseLong(body.getString("next_report_run_time"))*60000L));
                                    ps0003_update.setBoolean(4, "true".equals(body.getString("train_terminated")));
                                    ps0003_update.setString(5, body.getString("train_id"));
                                    ps0003_update.execute();
                                }
                            }
                            break;

                        case "0005": // Reinstatement
                            ps0002_0005.setBoolean(1, false);
                            ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("reinstatement_timestamp"))));
                            ps0002_0005.setString(3, body.getString("train_id"));

                            ps0002_0005.execute();
                            break;

                        case "0006": // Change Origin
                            ps0006.setLong(1, Long.parseLong(body.getString("coo_timestamp")));
                            ps0006.setLong(2, fixTimestamp(Long.parseLong(body.getString("dep_timestamp"))));
                            ps0006.setString(3, body.getString("train_id"));

                            ps0006.execute();
                            break;

                        case "0007": // Change Identity
                            ps0007.setString(1, body.getString("revised_train_id"));
                            ps0007.setLong(2, Long.parseLong(body.getString("event_timestamp")));
                            ps0007.setString(3, body.getString("train_id"));

                            ps0007.execute();
                            break;

                        case "0008": // Change Location
                            ps0008.setLong(1, Long.parseLong(body.getString("event_timestamp")));
                            ps0008.setString(2, body.getString("train_id"));

                            ps0008.execute();
                            break;
                    }
                }
                catch (SQLException ex) { NRODLight.printThrowable(ex, "TRUST"); }

                printTRUST(map.toString(), false);
            }
        }
        catch (SQLException e) { NRODLight.printThrowable(e, "TRUST"); }

        RateMonitor.getInstance().onTRUSTMessage(messageList.length());
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    private static long fixTimestamp(long timestamp)
    {
        return timestamp - (TimeZone.getDefault().inDaylightTime(new Date(timestamp)) ? 3600000L : 0L);
    }

    private static String cifTime(long timestamp)
    {
        String s = cifTime.format(new Date(timestamp));
        return s.substring(0, 4) + (s.endsWith("30") ? "H" : "");
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