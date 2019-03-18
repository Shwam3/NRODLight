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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class VSTPHandler implements NRODListener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private        long        lastMessageTime = 0;

    private static NRODListener instance = null;
    private VSTPHandler()
    {
        lastLogDate = NRODLight.sdfDate.format(new Date());
        logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "VSTP" + File.separator + lastLogDate.replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODLight.printThrowable(e, "VSTP"); }

        lastMessageTime = System.currentTimeMillis();
    }
    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new VSTPHandler();

        return instance;
    }

    @Override
    public void message(Map<String, String> headers, String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        long start = System.nanoTime();
        JSONObject msg = new JSONObject(message).getJSONObject("VSTPCIFMsgV1");
        printVSTP(message, !msg.has("timestamp"), msg.optLong("timestamp", 0L));

        try
        {
            Connection conn = DBHandler.getConnection();

            JSONObject schedule = msg.getJSONObject("schedule");
            if (schedule.has("CIF_train_uid")) {
                schedule.put("CIF_train_uid", schedule.getString("CIF_train_uid").replace(" ", "O"));
            }

            if ("Delete".equals(schedule.getString("transaction_type")) || "Update".equals(schedule.getString("transaction_type")))
            {
                PreparedStatement psBSDel;
                psBSDel = conn.prepareStatement("DELETE FROM schedule_locations WHERE schedule_uid = ? AND stp_indicator = ? AND date_from = ? AND schedule_source = 'V'");
                psBSDel.setString(1, schedule.getString("CIF_train_uid"));
                psBSDel.setString(2, schedule.getString("CIF_stp_indicator"));
                psBSDel.setString(3, vstpToCifDate(schedule.getString("schedule_start_date")));
                psBSDel.executeUpdate();
                psBSDel = conn.prepareStatement("DELETE FROM schedules WHERE schedule_uid = ? AND stp_indicator = ? AND date_from = ? AND schedule_source = 'V'");
                psBSDel.setString(1, schedule.getString("CIF_train_uid"));
                psBSDel.setString(2, schedule.getString("CIF_stp_indicator"));
                psBSDel.setString(3, vstpToCifDate(schedule.getString("schedule_start_date")));
                psBSDel.executeUpdate();
            }

            if ("Create".equals(schedule.getString("transaction_type")) || "Update".equals(schedule.getString("transaction_type")))
            {
                JSONObject sched_seg = schedule.getJSONArray("schedule_segment").getJSONObject(0);
                JSONArray sched_locs = sched_seg.getJSONArray("schedule_location");

                PreparedStatement psBS = conn.prepareStatement("INSERT INTO schedules (schedule_uid, date_from, date_to, stp_indicator, schedule_source, days_run, "
                    + "identity, runs_mon, runs_tue, runs_wed, runs_thu, runs_fri, runs_sat, runs_sun, over_midnight, tds) VALUES (?,?,?,?,'V',?,?,?,?,?,?,?,?,?,?,?)");
                PreparedStatement psLO = conn.prepareStatement("INSERT INTO schedule_locations (schedule_uid, date_from, stp_indicator, schedule_source, tiploc, scheduled_arrival, scheduled_departure, scheduled_pass, type, loc_index) VALUES (?,?,?,'V',?,'',?,'','O',0)");
                PreparedStatement psLI = conn.prepareStatement("INSERT INTO schedule_locations (schedule_uid, date_from, stp_indicator, schedule_source, tiploc, scheduled_arrival, scheduled_departure, scheduled_pass, type, loc_index) VALUES (?,?,?,'V',?,?,?,?,'I',?)");
                PreparedStatement psLT = conn.prepareStatement("INSERT INTO schedule_locations (schedule_uid, date_from, stp_indicator, schedule_source, tiploc, scheduled_arrival, scheduled_departure, scheduled_pass, type, loc_index) VALUES (?,?,?,'V',?,?,'','','T',?)");

                String schedule_uid = schedule.getString("CIF_train_uid");
                String date_from = vstpToCifDate(schedule.getString("schedule_start_date"));
                String stp_indicator = schedule.getString("CIF_stp_indicator");
                String days_run = schedule.getString("schedule_days_runs");

                psBS.setString(1, schedule_uid);
                psBS.setString(2, date_from);
                psBS.setString(3, vstpToCifDate(schedule.getString("schedule_end_date")));
                psBS.setString(4, stp_indicator);
                psBS.setString(5, days_run);
                psBS.setString(6, sched_seg.getString("signalling_id"));

                for (int i = 0; i < 7; i++)
                    psBS.setBoolean(i+7, days_run.charAt(i) == '1');

                Set<String> tds = new TreeSet<>();
                PreparedStatement ps = conn.prepareStatement("SELECT td FROM smart INNER JOIN corpus ON corpus.stanox = smart.stanox WHERE tiploc = ?");

                int loc_index = 0;
                for (Object loc_o : sched_locs)
                {
                    JSONObject loc = (JSONObject) loc_o;
                    String tiploc = loc.getJSONObject("location").getJSONObject("tiploc").getString("tiploc_id");
                    ps.setString(1, tiploc);
                    try(ResultSet rs = ps.executeQuery())
                    {
                        if (rs.next())
                            tds.add(rs.getString(1));
                    }

                    if (loc_index == 0) // LO
                    {
                        psLO.setString(1, schedule_uid);
                        psLO.setString(2, date_from);
                        psLO.setString(3, stp_indicator);
                        psLO.setString(4, tiploc);
                        psLO.setString(5, vstpToCifTime(loc.getString("scheduled_departure_time")));
                        psLO.executeUpdate();
                    }
                    else if (loc_index == sched_locs.length() - 1) // LT
                    {
                        if (loc_index > 1)
                            psLI.executeBatch();

                        psLT.setString(1, schedule_uid);
                        psLT.setString(2, date_from);
                        psLT.setString(3, stp_indicator);
                        psLT.setString(4, tiploc);
                        psLT.setString(5, vstpToCifTime(loc.getString("scheduled_arrival_time")));
                        psLT.setInt(6, loc_index);
                        psLT.executeUpdate();
                    }
                    else // LI
                    {
                        psLI.setString(1, schedule_uid);
                        psLI.setString(2, date_from);
                        psLI.setString(3, stp_indicator);
                        psLI.setString(4, tiploc);
                        psLI.setString(5, vstpToCifTime(loc.getString("scheduled_arrival_time")));
                        psLI.setString(6, vstpToCifTime(loc.getString("scheduled_departure_time")));
                        psLI.setString(7, vstpToCifTime(loc.getString("scheduled_pass_time")));
                        psLI.setInt(8, loc_index);
                        psLI.addBatch();
                    }
                    loc_index++;
                }
                psBS.setBoolean(14,
                 Double.parseDouble(vstpToCifTime(sched_locs.getJSONObject(0).getString("scheduled_departure_time")).replace("H", ".5")) >
                    Double.parseDouble(vstpToCifTime(sched_locs.getJSONObject(sched_locs.length()-1).getString("scheduled_arrival_time")).replace("H", ".5")));
                psBS.setString(15, String.join(",", tds));
                psBS.executeUpdate();
            }
            long time = (System.nanoTime() - start)/1000000L;

            printVSTP(String.format("%sd schedule: %s, %s, %s%s (%sms)",
                    schedule.getString("transaction_type"),
                    schedule.getString("CIF_train_uid"),
                    vstpToCifDate(schedule.getString("schedule_start_date")),
                    schedule.getString("CIF_stp_indicator"),
                    ("Delete".equals(schedule.getString("transaction_type")) ? "" :
                            ", with " + schedule.getJSONArray("schedule_segment").getJSONObject(0).getJSONArray("schedule_location").length() + " locations"),
                    time
            ), false, msg.optLong("timestamp", 0L) + time);
        }
        catch (SQLException ex) { NRODLight.printThrowable(ex, "VSTP"); }

        RateMonitor.getInstance().onVSTPMessage();
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 3600000; }

    private static void printVSTP(String message, boolean toErr, long timestamp)
    {
        if (NRODLight.verbose)
        {
            if (toErr)
                NRODLight.printErr("[VSTP] " + message);
            else
                NRODLight.printOut("[VSTP] " + message);
        }

        String newDate = NRODLight.sdfDate.format(new Date());
        if (!lastLogDate.equals(newDate))
        {
            logStream.close();

            lastLogDate = newDate;

            logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "VSTP" + File.separator + newDate.replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODLight.printThrowable(e, "VSTP"); }
        }
        logStream.println("[" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + message);
    }

    private static String vstpToCifTime(String time)
    {
        if (time.trim().length() == 0)
            return "";
        else if ("00".equals(time.substring(4)))
            return time.substring(0, 4);
        else
            return time.substring(0, 4) + "H";
    }

    private static String vstpToCifDate(String date)
    {
        return date.substring(2).replace("-","");
    }
}