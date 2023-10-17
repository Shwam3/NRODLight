package nrodlight.stomp.handlers;

import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.db.DBHandler;
import nrodlight.db.Queries;
import org.apache.activemq.command.ActiveMQMessage;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import java.io.*;
import java.sql.*;
import java.util.Date;

public class VSTPHandler implements MessageListener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";

    private static MessageListener instance;
    private VSTPHandler()
    {
        lastLogDate = NRODLight.sdfDate.format(new Date());
        logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "VSTP" + File.separator + lastLogDate.replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODLight.printThrowable(e, "VSTP"); }
    }
    public static MessageListener getInstance()
    {
        if (instance == null)
            instance = new VSTPHandler();

        return instance;
    }

    public void handleMessage(final String message, final long timestamp)
    {
        JSONObject msg = new JSONObject(message).getJSONObject("VSTPCIFMsgV1");
        printVSTP(message, !msg.has("timestamp"), Long.parseLong(msg.optString("timestamp", "0")));

        handleVSTP(message);

        RateMonitor.getInstance().onVSTPMessage(
                (System.currentTimeMillis() - timestamp) / 1000d,
                (System.currentTimeMillis() - Long.parseLong(msg.optString("timestamp", "0"))) / 1000d,
                "Delete".equals(msg.getJSONObject("schedule").getString("transaction_type"))
            );
    }

    private void handleVSTP(String message)
    {
        long start = System.nanoTime();
        JSONObject msg = new JSONObject(message).getJSONObject("VSTPCIFMsgV1");
        JSONObject schedule = msg.getJSONObject("schedule");

        try
        {
            Connection conn = DBHandler.getConnection();

            if (schedule.has("CIF_train_uid")) {
                schedule.put("CIF_train_uid", schedule.getString("CIF_train_uid").replace(" ", "O"));
            }

            final String schedule_key = schedule.getString("CIF_train_uid") +
                    vstpToCifDate(schedule.getString("schedule_start_date")) +
                    schedule.getString("CIF_stp_indicator") + "V";

            long creationTS = Long.parseLong(msg.getString("timestamp"));
            if ("Update".equals(schedule.getString("transaction_type")))
            {
                try (PreparedStatement psSchedCreateTime = conn.prepareStatement(Queries.VSTP_CREATE_TIME))
                {
                    psSchedCreateTime.setString(1, schedule_key);
                    try (ResultSet rs = psSchedCreateTime.executeQuery()) {
                        if (rs.next())
                            creationTS = rs.getLong(1) > 0 ? rs.getLong(1) : creationTS;
                    }
                }
            }

            if ("Delete".equals(schedule.getString("transaction_type")) || "Update".equals(schedule.getString("transaction_type")))
            {
                try (PreparedStatement psBSDel = conn.prepareStatement(Queries.VSTP_DEL_LOCS))
                {
                    psBSDel.setString(1, schedule_key);
                    psBSDel.executeUpdate();
                }

                try (PreparedStatement psBSDel = conn.prepareStatement(Queries.VSTP_DEL_SCHED))
                {
                    psBSDel.setString(1, schedule_key);
                    psBSDel.executeUpdate();
                }

                try (PreparedStatement psBSDel = conn.prepareStatement(Queries.VSTP_DEL_CER))
                {
                    psBSDel.setString(1, schedule_key);
                    psBSDel.executeUpdate();
                }
            }

            if ("Create".equals(schedule.getString("transaction_type")) || "Update".equals(schedule.getString("transaction_type")))
            {
                JSONObject sched_seg = schedule.getJSONArray("schedule_segment").getJSONObject(0);
                JSONArray sched_locs = sched_seg.getJSONArray("schedule_location");

                try (PreparedStatement psBS = conn.prepareStatement(Queries.VSTP_INSERT_SCHED);
                     PreparedStatement psLoc = conn.prepareStatement(Queries.VSTP_INSERT_LOCS))
                {
                    final String schedule_uid = schedule.getString("CIF_train_uid");
                    final String date_from = vstpToCifDate(schedule.getString("schedule_start_date"));
                    final String stp_indicator = schedule.getString("CIF_stp_indicator");
                    final String days_run = schedule.getString("schedule_days_runs");

                    psBS.setString(1, schedule_key);
                    psBS.setString(2, schedule_uid);
                    psBS.setString(3, date_from);
                    psBS.setString(4, vstpToCifDate(schedule.getString("schedule_end_date")));
                    psBS.setString(5, stp_indicator);
                    psBS.setString(6, days_run);
                    psBS.setString(7, sched_seg.optString("signalling_id"));

                    for (int i = 0; i < 7; i++)
                        psBS.setBoolean(i + 8, days_run.charAt(i) == '1');

                    int loc_index = 0;
                    for (Object loc_o : sched_locs)
                    {
                        JSONObject loc = (JSONObject)loc_o;
                        String tiploc = loc.getJSONObject("location").getJSONObject("tiploc").getString("tiploc_id");

                        psLoc.setString(1, schedule_key);
                        psLoc.setString(2, schedule_uid);
                        psLoc.setString(3, date_from);
                        psLoc.setString(4, stp_indicator);
                        psLoc.setString(5, tiploc);
                        psLoc.setString(6, vstpToCifTime(loc.optString("scheduled_arrival_time").trim()));
                        psLoc.setString(7, vstpToCifTime(loc.optString("scheduled_departure_time").trim()));
                        psLoc.setString(8, vstpToCifTime(loc.optString("scheduled_pass_time").trim()));
                        psLoc.setString(9, vstpToCifTime(loc.optString("public_arrival_time").trim()));
                        psLoc.setString(10, vstpToCifTime(loc.optString("public_departure_time").trim()));
                        if (loc_index == 0)
                            psLoc.setString(11, "O");
                        else if (loc_index == sched_locs.length() - 1)
                            psLoc.setString(11, "T");
                        else
                            psLoc.setString(11, "I");
                        psLoc.setString(12, loc.optString("CIF_platform").trim());
                        psLoc.setString(13, loc.optString("CIF_path").trim());
                        psLoc.setString(14, loc.optString("CIF_line").trim());
                        psLoc.setString(15, loc.optString("CIF_activity").trim());
                        psLoc.setString(16, loc.optString("CIF_engineering_allowance").trim());
                        psLoc.setString(17, loc.optString("CIF_pathing_allowance").trim());
                        psLoc.setString(18, loc.optString("CIF_performance_allowance").trim());
                        psLoc.setInt(19, loc_index);
                        psLoc.addBatch();
                        loc_index++;
                    }
                    psBS.setBoolean(15,
                            Double.parseDouble(vstpToCifTime(sched_locs.getJSONObject(0).getString("scheduled_departure_time")).replace("H", ".5")) >
                                    Double.parseDouble(vstpToCifTime(sched_locs.getJSONObject(sched_locs.length() - 1).getString("scheduled_arrival_time")).replace("H", ".5")));
                    psBS.setLong(16, creationTS);
                    if ("Update".equals(schedule.getString("transaction_type")))
                        psBS.setLong(17, Long.parseLong(msg.getString("timestamp")));
                    else
                        psBS.setNull(17, Types.BIGINT);
                    psBS.setString(18, sched_seg.optString("CIF_train_category"));
                    psBS.setString(19, schedule.getString("train_status"));
                    psBS.setString(20, sched_seg.optString("CIF_headcode"));
                    psBS.setString(21, sched_seg.getString("CIF_train_service_code"));
                    psBS.setString(22, sched_seg.getString("CIF_power_type"));
                    psBS.setString(23, sched_seg.optString("CIF_timing_load"));
                    int speed = 0;
                    try { speed = Integer.parseInt(sched_seg.optString("CIF_speed")); }
                    catch (NumberFormatException ignored) {}
                    if ((sched_seg.optString("CIF_speed").endsWith("5") || sched_seg.optString("CIF_speed").endsWith("0")) && speed < 186)
                        psBS.setString(24, sched_seg.optString("CIF_speed"));
                    else
                        psBS.setString(24, String.format("%03d", (int)Math.floor(speed / 2.24d)));
                    psBS.setString(25, sched_seg.optString("CIF_operating_characteristics"));
                    psBS.setString(26, sched_seg.optString("CIF_train_class"));
                    psBS.setString(27, sched_seg.optString("CIF_sleepers"));
                    psBS.setString(28, sched_seg.optString("CIF_reservations"));
                    psBS.setString(29, sched_seg.optString("CIF_catering_code"));
                    psBS.setString(30, sched_seg.optString("CIF_service_branding"));
                    psBS.setString(31, sched_seg.optString("atoc_code"));
                    psBS.executeUpdate();
                    psLoc.executeBatch();
                }

                    /*try (PreparedStatement psFindActivation = conn.prepareStatement(Queries.TRUST_FIND_ACTIVATION))
                    {
                        psFindActivation.setString(1, schedule.getString("CIF_train_uid"));
                        psFindActivation.setString(2, schedule.getString("CIF_train_uid"));
                        psFindActivation.setString(3, schedule.getString("CIF_train_uid"));

                        try (ResultSet rs = psFindActivation.executeQuery())
                        {
                            if (rs.next())
                            {
                                String train_id = rs.getString(1);
                                String schedule_uid = schedule.getString("CIF_train_uid");
                                String date_from = vstpToCifDate(schedule.getString("schedule_start_date"));
                                String stp_indicator = schedule.getString("CIF_stp_indicator");

                                printVSTP("Updating activation for " + train_id, false, msg.optLong("timestamp", 0L));

                                try (PreparedStatement ps0001_starttime_smart = conn.prepareStatement(Queries.TRUST_START_SMART);
                                     PreparedStatement ps0001_starttime_any = conn.prepareStatement(Queries.TRUST_START_ANY);
                                     PreparedStatement ps0001_update = conn.prepareStatement(Queries.TRUST_1_FROM_SCHED))
                                {
                                    String scheduled_departure = null;
                                    String scheduled_departure_tiploc = null;
                                    ps0001_starttime_smart.setString(1, schedule_uid);
                                    ps0001_starttime_smart.setString(2, date_from);
                                    ps0001_starttime_smart.setString(3, stp_indicator);
                                    ps0001_starttime_smart.setString(4, "V");
                                    try (ResultSet rs2 = ps0001_starttime_smart.executeQuery())
                                    {
                                        if (rs2.next())
                                        {
                                            if (rs2.getString(3) != null && !rs2.getString(3).trim().isEmpty())
                                                scheduled_departure = rs2.getString(3);
                                            else if (rs2.getString(4) != null && !rs2.getString(4).trim().isEmpty())
                                                scheduled_departure = rs2.getString(4);
                                            else if (rs2.getString(2) != null && !rs2.getString(2).trim().isEmpty())
                                                scheduled_departure = rs2.getString(2);

                                            if (scheduled_departure != null)
                                                scheduled_departure_tiploc = rs2.getString(1);
                                        }
                                    } catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }

                                    if (scheduled_departure == null && !"C".equals(stp_indicator))
                                    {
                                        ps0001_starttime_any.setString(1, schedule_uid);
                                        ps0001_starttime_any.setString(2, date_from);
                                        ps0001_starttime_any.setString(3, stp_indicator);
                                        ps0001_starttime_any.setString(4, "V");
                                        try (ResultSet rs2 = ps0001_starttime_any.executeQuery())
                                        {
                                            if (rs2.next())
                                            {
                                                scheduled_departure_tiploc = rs2.getString(1);
                                                scheduled_departure = rs2.getString(2);
                                            }
                                        } catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }
                                    }

                                    long origin_dep_timestamp;
                                    if (scheduled_departure != null)
                                    {
                                        int hh = Integer.parseInt(scheduled_departure.substring(0, 2));
                                        int mm = Integer.parseInt(scheduled_departure.substring(2, 4));
                                        int ss = "H".equals(scheduled_departure.substring(4)) ? 30 : 0;

                                        LocalDateTime todayMidnight = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()), LocalTime.of(hh, mm, ss));
                                        if (Integer.parseInt(train_id.substring(8)) != todayMidnight.getDayOfMonth())
                                            origin_dep_timestamp = todayMidnight.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                                        else
                                            origin_dep_timestamp = todayMidnight.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

                                        // next_expected_update=?,next_expected_tiploc=?,start_timestamp=?,last_update=?,inferred=0 WHERE train_id=?
                                        ps0001_update.setLong(1, origin_dep_timestamp);
                                        ps0001_update.setString(2, scheduled_departure_tiploc);
                                        ps0001_update.setLong(3, origin_dep_timestamp);
                                        ps0001_update.setLong(4, msg.optLong("timestamp", 0L));
                                        ps0001_update.setString(5, train_id);
                                        ps0001_update.executeUpdate();
                                    }
                                }
                            }
                        }
                    }*/
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
        catch (SQLException ex)
        {
            String errorMsg = String.format("Failed to %s schedule: %s, %s, %s%s",
                    schedule.getString("transaction_type"),
                    schedule.getString("CIF_train_uid"),
                    vstpToCifDate(schedule.getString("schedule_start_date")),
                    schedule.getString("CIF_stp_indicator"),
                    ("Delete".equals(schedule.getString("transaction_type")) ? "" :
                            ", with " + schedule.getJSONArray("schedule_segment").getJSONObject(0).getJSONArray(
                                    "schedule_location").length() + " locations"));

            printVSTP(errorMsg, true, msg.optLong("timestamp", 0L));
            NRODLight.printErr("[VSTP] " + errorMsg);
            NRODLight.printThrowable(ex, "VSTP");
        }
        catch (JSONException ex)
        {
            NRODLight.printErr("[VSTP] " + ex.getMessage());
        }
    }

    private static void printVSTP(final String message, final boolean toErr, final long timestamp)
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
        if (toErr)
            logStream.println("!!!> [" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + message + " <!!!");
        else
            logStream.println("[" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + message);
    }

    private static String vstpToCifTime(final String time)
    {
        if (time.trim().isEmpty())
            return "";
        else if ("00".equals(time.substring(4)))
            return time.substring(0, 4);
        else
            return time.substring(0, 4) + "H";
    }

    private static String vstpToCifDate(final String date)
    {
        return date.substring(2).replace("-","");
    }

    @Override
    public void onMessage(Message msg)
    {
        try
        {
            ActiveMQMessage message = (ActiveMQMessage) msg;
            if (message instanceof TextMessage)
            {
                handleMessage(((TextMessage) message).getText(), (message.propertyExists("origTimestamp") ?
                        message.getLongProperty("origTimestamp") : System.currentTimeMillis()));
            }
            message.acknowledge();
        }
        catch (JMSException ex) { NRODLight.printThrowable(ex, "VSTP"); }
    }
}
