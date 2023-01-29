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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class TRUSTHandler implements MessageListener
{
    //private static PrintWriter logStream;
    //private static File        logFile;
    //private static String      lastLogDate = "";
    private long lastMessageTime;
    private static final SimpleDateFormat cifTime = new SimpleDateFormat("HHmmss");
    private static final SimpleDateFormat cifDate = new SimpleDateFormat("yyMMdd");

    private static MessageListener instance;
    private TRUSTHandler()
    {
        //String logDate = NRODLight.sdfDate.format(new Date());
        //lastLogDate = logDate;
        //logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TRUST" + File.separator + logDate.replace("/", "-") + ".log");
        //logFile.getParentFile().mkdirs();

        //try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        //catch (IOException e) { NRODLight.printThrowable(e, "TRUST"); }

        lastMessageTime = System.currentTimeMillis();
    }
    public static MessageListener getInstance()
    {
        if (instance == null)
            instance = new TRUSTHandler();

        return instance;
    }

    public void handleMessage(final String message, final long timestamp)
    {
        JSONArray messageList = new JSONArray(message);
        List<Long> timestamps = new ArrayList<>(messageList.length());
        long acts = 0;
        long cans = 0;
        long moves = 0;
        long reins = 0;
        long coo = 0;
        long coi = 0;
        long col = 0;
        long inf = 0;

        try
        {
            Connection conn = DBHandler.getConnection();
            try (PreparedStatement ps0001_starttime_smart = conn.prepareStatement(Queries.TRUST_START_SMART);
                 PreparedStatement ps0001_starttime_any = conn.prepareStatement(Queries.TRUST_START_ANY);
                 PreparedStatement ps0001 = conn.prepareStatement(Queries.TRUST_1);
                 PreparedStatement ps0002_0005 = conn.prepareStatement(Queries.TRUST_UPDATE);
                 PreparedStatement ps0003_find = conn.prepareStatement(Queries.TRUST_3_FIND_ACTIVATION);
                 PreparedStatement ps0003_infer_sched = conn.prepareStatement(Queries.TRUST_3_INFER_SCHEDULE);
                 PreparedStatement ps0003_add_inferred = conn.prepareStatement(Queries.TRUST_3_ADD_INFERRED);
                 PreparedStatement ps0003_update = conn.prepareStatement(Queries.TRUST_3_UPDATE);
                 PreparedStatement ps0003_insert = conn.prepareStatement(Queries.TRUST_3_INSERT);
                 PreparedStatement ps0003_next_update_arr = conn.prepareStatement(Queries.TRUST_3_ARR);
                 PreparedStatement ps0003_next_update_dep = conn.prepareStatement(Queries.TRUST_3_DEP);
                 PreparedStatement ps0003_offroute = conn.prepareStatement(Queries.TRUST_OFFROUTE);
                 PreparedStatement ps0006 = conn.prepareStatement(Queries.TRUST_6);
                 PreparedStatement ps0007 = conn.prepareStatement(Queries.TRUST_7);
                 PreparedStatement ps0008 = conn.prepareStatement(Queries.TRUST_8))
            {
            for (Object msgObj : messageList)
            {
                JSONObject map = (JSONObject)msgObj;
                JSONObject header = map.getJSONObject("header");
                JSONObject body = map.getJSONObject("body");
                long messageTime = Long.parseLong(header.getString("msg_queue_timestamp"));
                timestamps.add(messageTime);
                try
                {
                    switch (header.getString("msg_type"))
                    {
                        case "0001": // Activation
                        {
                            acts++;
                            // O and P STP types are switched in the feed
                            if ("O".equals(body.getString("schedule_type")))
                                body.put("schedule_type", "P");
                            else if ("P".equals(body.getString("schedule_type")))
                                body.put("schedule_type", "O");

                            // normalise activation-style values to CIF-style values
                            body.put("train_uid", body.getString("train_uid").replace(" ", "O"));
                            body.put("schedule_start_date", body.getString("schedule_start_date").substring(2).replace("-", ""));
                            body.put("schedule_end_date", body.getString("schedule_end_date").substring(2).replace("-", ""));
                            body.put("schedule_key", body.getString("train_uid") +
                                    body.getString("schedule_start_date") +
                                    body.getString("schedule_type") +
                                    body.getString("schedule_source")
                            );

                            // find the first departure time expected to have a SMART report
                            String scheduled_departure = null;
                            String scheduled_departure_tiploc = null;
                            ps0001_starttime_smart.setString(1, body.getString("schedule_key"));
                            try (ResultSet rs = ps0001_starttime_smart.executeQuery())
                            {
                                if (rs.next())
                                {
                                    if (rs.getString(3) != null && !rs.getString(3).trim().isEmpty())
                                        scheduled_departure = rs.getString(3);
                                    else if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty())
                                        scheduled_departure = rs.getString(4);
                                    else if (rs.getString(2) != null && !rs.getString(2).trim().isEmpty())
                                        scheduled_departure = rs.getString(2);

                                    if (scheduled_departure != null)
                                        scheduled_departure_tiploc = rs.getString(1);
                                }
                            } catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }

                            if (scheduled_departure == null && !"C".equals(body.getString("schedule_type")))
                            {
                                // no SMART locations found, use first location
                                ps0001_starttime_any.setString(1, body.getString("schedule_key"));
                                try (ResultSet rs = ps0001_starttime_any.executeQuery())
                                {
                                    if (rs.next())
                                    {
                                        scheduled_departure_tiploc = rs.getString(1);
                                        scheduled_departure = rs.getString(2);
                                    }
                                    else
                                    {
                                        printTRUST(String.format("No schedule found for: %s (%s/%s/%s/%s)",
                                                body.getString("train_id"),
                                                body.getString("train_uid"),
                                                body.getString("schedule_start_date"),
                                                body.getString("schedule_type"),
                                                body.getString("schedule_source")
                                        ));
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
                                if (Integer.parseInt(body.getString("train_id").substring(8)) != todayMidnight.getDayOfMonth() || LocalTime.now(ZoneId.systemDefault()).getHour() - hh >= 12)
                                    origin_dep_timestamp = todayMidnight.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                                else
                                    origin_dep_timestamp = todayMidnight.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                            }
                            else
                                origin_dep_timestamp = fixTimestamp(Long.parseLong(body.getString("origin_dep_timestamp")));

                            ps0001.setString(1, body.getString("train_id"));
                            ps0001.setString(2, body.getString("train_id"));
                            ps0001.setLong(3, origin_dep_timestamp);
                            ps0001.setString(4, body.getString("schedule_key"));
                            ps0001.setString(5, body.getString("train_uid"));
                            ps0001.setString(6, body.getString("schedule_start_date"));
                            ps0001.setString(7, body.getString("schedule_end_date"));
                            ps0001.setString(8, body.getString("schedule_type"));
                            ps0001.setString(9, body.getString("schedule_source"));
                            long creation_timestamp = fixTimestamp(Long.parseLong(body.getString("creation_timestamp")));
                            ps0001.setLong(10, creation_timestamp);
                            ps0001.setLong(11, origin_dep_timestamp);
                            ps0001.setString(12, scheduled_departure_tiploc);
                            ps0001.setLong(13, creation_timestamp);
                            ps0001.addBatch();

                            /*
                             1 train_id,
                             2 train_id_current,
                             3 start_timestamp,
                             4 schedule_key,
                             5 schedule_uid,
                             6 schedule_date_from,
                             7 schedule_date_to,
                             8 stp_indicator,
                             9 schedule_source,
                            10 creation_timestamp,
                            11 next_expected_update,
                            12 next_expected_tiploc,
                            13 last_update
                            */
                            break;
                        }

                        case "0002": // Cancellation
                        {
                            cans++;
                            ps0002_0005.setBoolean(1, true);
                            ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("canx_timestamp"))));
                            ps0002_0005.setString(3, body.getString("train_id"));

                            ps0002_0005.addBatch();
                            break;
                        }

                        case "0003": // Movement
                        {
                            moves++;
                            if ("false".equals(body.getString("offroute_ind")) && !"OFF ROUTE".equals(body.getString("timetable_variation")))
                            {
                                ps0003_find.setString(1, body.getString("train_id"));
                                try (ResultSet rs = ps0003_find.executeQuery())
                                {
                                    if (!rs.first())
                                    {
                                        ps0003_infer_sched.setString(1, body.getString("loc_stanox"));
                                        long pt = fixTimestamp(Long.parseLong(body.getString("planned_timestamp")));
                                        String time = cifTime(pt);
                                        ps0003_infer_sched.setString(2, time);
                                        ps0003_infer_sched.setBoolean(3, "ARRIVAL".equals(body.get("event_type")));
                                        ps0003_infer_sched.setString(4, time);
                                        ps0003_infer_sched.setBoolean(5, "DEPARTURE".equals(body.get("event_type")));
                                        ps0003_infer_sched.setString(6, time);
                                        ps0003_infer_sched.setInt(7, LocalDate.now().getDayOfWeek().getValue());
                                        ps0003_infer_sched.setInt(8, LocalDate.now().minus(Period.ofDays(1)).getDayOfWeek().getValue());
                                        int date_today = Integer.parseInt(cifDate.format(new Date()));
                                        int date_yest = Integer.parseInt(cifDate.format(new Date(System.currentTimeMillis()-24*60*60*1000)));
                                        ps0003_infer_sched.setInt(9, date_today);
                                        ps0003_infer_sched.setInt(10, date_yest);
                                        ps0003_infer_sched.setInt(11, date_today);
                                        ps0003_infer_sched.setInt(12, date_yest);
                                        ps0003_infer_sched.setInt(13, date_today);
                                        ps0003_infer_sched.setInt(14, date_yest);
                                        String identity = body.getString("train_id").substring(2, 6);
                                        ps0003_infer_sched.setBoolean(15, identity.matches("[0-9][A-Z][0-9][0-9]"));
                                        ps0003_infer_sched.setString(16, identity);

                                        try (ResultSet rs2 = ps0003_infer_sched.executeQuery())
                                        {
                                            if (rs2.first())
                                            {
                                                String uid = rs2.getString(1).substring(0, 6);
                                                boolean diffUIDs = false;
                                                while (rs2.next())
                                                {
                                                    String key = rs2.getString(1).substring(0, 6);
                                                    if (!key.equals(uid))
                                                    {
                                                        diffUIDs = true;
                                                        printTRUST("Could not infer activation for " + body.getString("train_id") + ", multiple matches (" + uid + "/" + key + ", maybe more)");
                                                        break;
                                                    }
                                                }

                                                if (!diffUIDs)
                                                {
                                                    rs2.first();
                                                    String schedule_key = rs2.getString(1);
                                                    ps0003_add_inferred.setString(1, body.getString("train_id"));
                                                    ps0003_add_inferred.setString(2, "".equals(body.optString("current_train_id", "")) ? body.getString("train_id") : body.getString("current_train_id"));
                                                    ps0003_add_inferred.setLong(3, Long.parseLong(body.getString("planned_timestamp")));

                                                    ps0001_starttime_any.setString(1, schedule_key);
                                                    try (ResultSet rs3 = ps0001_starttime_any.executeQuery())
                                                    {
                                                        if (rs3.next())
                                                        {
                                                            String scheduled_departure = rs3.getString(2);
                                                            int hh = Integer.parseInt(scheduled_departure.substring(0, 2));
                                                            int mm = Integer.parseInt(scheduled_departure.substring(2, 4));
                                                            int ss = "H".equals(scheduled_departure.substring(4)) ? 30 : 0;

                                                            LocalDateTime todayMidnight = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()), LocalTime.of(hh, mm, ss));
                                                            if (Integer.parseInt(body.getString("train_id").substring(8)) != todayMidnight.getDayOfMonth())
                                                                ps0003_add_inferred.setLong(3, todayMidnight.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                                                            else
                                                                ps0003_add_inferred.setLong(3, todayMidnight.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                                                        }
                                                    } catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }

                                                    ps0003_add_inferred.setString(4, schedule_key);
                                                    ps0003_add_inferred.setString(5, schedule_key.substring(0, 6));
                                                    ps0003_add_inferred.setString(6, schedule_key.substring(7, 12));
                                                    ps0003_add_inferred.setString(7, rs2.getString(2));
                                                    ps0003_add_inferred.setString(8, String.valueOf(schedule_key.charAt(12)));
                                                    ps0003_add_inferred.setString(9, String.valueOf(schedule_key.charAt(13)));
                                                    long at = fixTimestamp(Long.parseLong(body.getString("actual_timestamp")));
                                                    ps0003_add_inferred.setLong(10, at);
                                                    ps0003_add_inferred.setLong(11, at);
                                                    ps0003_add_inferred.executeUpdate();
                                                    printTRUST("Inferred activation for " + body.getString("train_id") + " of " + schedule_key);
                                                    inf++;
                                                }
                                            }
                                            else
                                                printTRUST("Could not infer activation for " + body.getString("train_id") + ", no matches");
                                        }
                                    }
                                } //catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }
                                catch (Exception e) { NRODLight.printThrowable(e, "TRUST"); }
                            }

                            if ("false".equals(body.getString("correction_ind")))
                            {
                                double delay = 0;
                                long next_expected_update = -1;
                                long at = fixTimestamp(Long.parseLong(body.getString("actual_timestamp")));
                                String next_expected_tiploc = null;
                                if ("true".equals(body.getString("offroute_ind")) || "OFF ROUTE".equals(body.getString("timetable_variation")))
                                {
                                    ps0003_offroute.setLong(1, messageTime);
                                    ps0003_offroute.setBoolean(2, "true".equals(body.getString("train_terminated")));
                                    ps0003_offroute.setString(3, body.getString("train_id"));
                                    ps0003_offroute.setLong(4, at);
                                    ps0003_offroute.addBatch();
                                }
                                else if ("DEPARTURE".equals(body.get("event_type")))
                                {
                                    long pt = fixTimestamp(Long.parseLong(body.getString("planned_timestamp")));
                                    delay = (at - pt) / 60000d;

                                    ps0003_next_update_dep.setString(1, body.getString("train_id"));
                                    ps0003_next_update_dep.setString(2, body.getString("train_id"));
                                    String time = cifTime(pt);
                                    ps0003_next_update_dep.setString(3, time);
                                    ps0003_next_update_dep.setString(4, time);
                                    try (ResultSet rs = ps0003_next_update_dep.executeQuery())
                                    {
                                        if (rs.next())
                                        {
                                            next_expected_tiploc = rs.getString(1);

                                            if (rs.getString(3) != null && !rs.getString(3).trim().isEmpty())
                                                next_expected_update = timeCif(rs.getString(3), pt) + rs.getInt(6);
                                            else if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty())
                                                next_expected_update = timeCif(rs.getString(4), pt) + rs.getInt(6);
                                            else if (rs.getString(5) != null && !rs.getString(5).trim().isEmpty())
                                                next_expected_update = timeCif(rs.getString(5), pt) + rs.getInt(6);
                                        }
                                        else
                                        {
                                            next_expected_update = -2;
                                        }
                                    } catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }

                                    if (next_expected_update == -1)
                                    {
                                        next_expected_tiploc = body.optString("next_report_stanox");
                                        next_expected_update = at + Long.parseLong(body.optString("next_report_run_time", "0")) * 60000L;
                                    }
                                }
                                else if ("ARRIVAL".equals(body.get("event_type")))
                                {
                                    long pt = fixTimestamp(Long.parseLong(body.getString("planned_timestamp")));
                                    delay = (at - pt) / 60000d;

                                    ps0003_next_update_arr.setString(1, body.getString("train_id"));
                                    ps0003_next_update_arr.setString(2, body.getString("train_id"));
                                    String time = cifTime(pt);
                                    ps0003_next_update_arr.setString(3, time);
                                    ps0003_next_update_arr.setString(4, time);
                                    try (ResultSet rs = ps0003_next_update_arr.executeQuery())
                                    {
                                        if (rs.next())
                                        {
                                            next_expected_tiploc = rs.getString(1);

                                            if (body.getString("loc_stanox").equals(rs.getString(2)))
                                            {
                                                if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty())
                                                    next_expected_update = timeCif(rs.getString(4), pt) + rs.getInt(6);
                                                else if (rs.getString(5) != null && !rs.getString(5).trim().isEmpty())
                                                    next_expected_update = timeCif(rs.getString(5), pt) + rs.getInt(6);
                                            } else if (rs.getString(3) != null && !rs.getString(3).trim().isEmpty())
                                                next_expected_update = timeCif(rs.getString(3), pt) + rs.getInt(6);
                                            else if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty())
                                                next_expected_update = timeCif(rs.getString(4), pt) + rs.getInt(6);
                                            else if (rs.getString(5) != null && !rs.getString(5).trim().isEmpty())
                                                next_expected_update = timeCif(rs.getString(5), pt) + rs.getInt(6);
                                        }
                                        else
                                        {
                                            next_expected_update = -2;
                                        }
                                    } catch (SQLException s) { NRODLight.printThrowable(s, "TRUST"); }

                                    if (next_expected_update == -1)
                                    {
                                        next_expected_tiploc = body.optString("next_report_stanox");
                                        next_expected_update = at + parseLong(body.optString("next_report_run_time"), 0) * 60000L;
                                    }
                                }

                                if (next_expected_update != -1)
                                {
                                    ps0003_update.setDouble(1, delay);
                                    ps0003_update.setLong(2, at);
                                    ps0003_update.setString(3, body.getString("loc_stanox"));
                                    ps0003_update.setString(4, body.getString("loc_stanox"));
                                    ps0003_update.setString(5, body.getString("loc_stanox"));
                                    ps0003_update.setLong(6, next_expected_update == -2 ? -1 : next_expected_update);
                                    ps0003_update.setString(7, next_expected_tiploc);
                                    ps0003_update.setString(8, next_expected_tiploc);
                                    ps0003_update.setString(9, next_expected_tiploc);
                                    ps0003_update.setBoolean(10, "true".equals(body.getString("train_terminated")));
                                    ps0003_update.setString(11, body.getString("train_id"));
                                    ps0003_update.setLong(12, at);
                                    ps0003_update.setString(13, body.getString("event_source"));
                                    ps0003_update.addBatch();
                                }
                            }

                            ps0003_insert.setString(1, body.getString("train_id"));
                            ps0003_insert.setString(2, body.getString("loc_stanox"));
                            ps0003_insert.setLong(3, fixTimestamp(
                                                parseLong(body.optString("planned_timestamp"),
                                                parseLong(body.getString("actual_timestamp"), 0))
                                            ));
                            ps0003_insert.setLong(4, fixTimestamp(Long.parseLong(body.getString("actual_timestamp"))));
                            ps0003_insert.setString(5, String.valueOf(body.getString("event_type").charAt(0)));
                            ps0003_insert.setString(6, String.valueOf(header.getString("original_data_source").charAt(1)));
                            ps0003_insert.setString(7, body.optString("line_ind"));
                            ps0003_insert.setString(8, body.optString("platform"));
                            ps0003_insert.setBoolean(9, body.optBoolean("offroute_ind"));
                            ps0003_insert.setBoolean(10, body.optBoolean("train_terminated"));
                            ps0003_insert.addBatch();
                            break;
                        }

                        case "0005": // Reinstatement
                        {
                            reins++;
                            ps0002_0005.setBoolean(1, false);
                            ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("reinstatement_timestamp"))));
                            ps0002_0005.setString(3, body.getString("train_id"));

                            ps0002_0005.addBatch();
                            break;
                        }

                        case "0006": // Change Origin
                        {
                            coo++;
                            long dt = fixTimestamp(Long.parseLong(body.getString("dep_timestamp")));
                            long ct = fixTimestamp(Long.parseLong(body.getString("coo_timestamp")));
                            ps0006.setLong(1, dt);
                            ps0006.setString(2, body.getString("loc_stanox"));
                            ps0006.setString(3, body.getString("loc_stanox"));
                            ps0006.setString(4, body.getString("loc_stanox"));
                            ps0006.setLong(5, ct);
                            ps0006.setString(6, body.getString("train_id"));
                            ps0006.setLong(7, dt);
                            ps0006.setLong(8, ct);

                            ps0006.addBatch();
                            break;
                        }

                        case "0007": // Change Identity
                        {
                            coi++;
                            ps0007.setString(1, body.getString("revised_train_id"));
                            ps0007.setLong(2, Long.parseLong(body.getString("event_timestamp")));
                            ps0007.setString(3, body.getString("train_id"));

                            ps0007.addBatch();
                            break;
                        }

                        case "0008": // Change Location
                        {
                            col++;
                            ps0008.setLong(1, Long.parseLong(body.getString("event_timestamp")));
                            ps0008.setString(2, body.getString("train_id"));

                            ps0008.addBatch();
                            break;
                        }
                    }
                }
                catch (JSONException ex) { NRODLight.printErr("[TRUST] " + ex.getMessage()); }
                catch (Exception ex) { NRODLight.printThrowable(ex, "TRUST"); }

                //printTRUST(map.toString());
            }

            ps0001_starttime_smart.executeBatch();
            ps0001_starttime_any.executeBatch();
            ps0001.executeBatch();
            ps0002_0005.executeBatch();
            ps0003_update.executeBatch();
            ps0003_insert.executeBatch();
            ps0003_next_update_arr.executeBatch();
            ps0003_next_update_dep.executeBatch();
            ps0003_offroute.executeBatch();
            ps0003_add_inferred.executeBatch();
            ps0006.executeBatch();
            ps0007.executeBatch();
            ps0008.executeBatch();
            }
        }
        catch (SQLException e) { NRODLight.printThrowable(e, "TRUST"); }

        RateMonitor.getInstance().onTRUSTMessage(
                (System.currentTimeMillis() - timestamp) / 1000d,
                timestamps.stream().mapToLong(e -> System.currentTimeMillis() - e).average().orElse(0) / 1000d,
                acts, cans, moves, reins, coo, coi, col, inf);
    }

    private static long fixTimestamp(final long timestamp)
    {
        return timestamp - (TimeZone.getDefault().inDaylightTime(new Date(timestamp)) ? 3600000L : 0L);
    }

    private static String cifTime(final long timestamp)
    {
        String s = cifTime.format(new Date(timestamp));
        return s.substring(0, 4) + (s.endsWith("30") ? "H" : "");
    }

    private static long timeCif(final String s, final long refTimestamp)
    {
        int hh = Integer.parseInt(s.substring(0, 2));
        int mm = Integer.parseInt(s.substring(2, 4));
        int ss = "H".equals(s.substring(4)) ? 30 : 0;

        LocalDateTime time = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()), LocalTime.of(hh, mm, ss));
        long out = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (out < refTimestamp)
            return time.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return out;
    }

    //public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    //public long getTimeoutThreshold() { return 30000; }

    private static void printTRUST(final String message)
    {
        //if (NRODLight.verbose)
        //{
            NRODLight.printOut("[TRUST] " + message);
        //}

        //String newDate = NRODLight.sdfDate.format(new Date());
        //if (!lastLogDate.equals(newDate))
        //{
        //    logStream.close();

        //    lastLogDate = newDate;

        //    logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TRUST" + File.separator + newDate.replace("/", "-") + ".log");
        //    logFile.getParentFile().mkdirs();

        //    try
        //    {
        //        logFile.createNewFile();
        //        logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
        //    }
        //    catch (IOException e) { NRODLight.printThrowable(e, "TRUST"); }
        //}

        //logStream.println("[" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + message);
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
        catch (JMSException ex) { NRODLight.printThrowable(ex, "TRUST"); }
    }

    /*
    @Override
    public void message(Map<String, String> headers, String body)
    {
        StompConnectionHandler.printStompHeaders(headers);

        handleMessage(body, Long.parseLong(headers.getOrDefault("timestamp", "0")));

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }
    */

    private long parseLong(String longStr, long defaultVal)
    {
        try
        {
            return Long.parseLong(longStr);
        }
        catch (NumberFormatException ignored)
        {
            return defaultVal;
        }
    }
}
