package nrodlight.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.db.DBHandler;
import nrodlight.stomp.NRODListener;
import nrodlight.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONObject;

public class TRUSTHandler implements NRODListener {
   private static PrintWriter logStream;
   private static File logFile;
   private static String lastLogDate = "";
   private long lastMessageTime = 0L;
   private static final SimpleDateFormat cifTime = new SimpleDateFormat("HHmmss");
   private static NRODListener instance = null;

   private TRUSTHandler() {
      String logDate = NRODLight.sdfDate.format(new Date());
      lastLogDate = logDate;
      logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TRUST" + File.separator + logDate.replace("/", "-") + ".log");
      logFile.getParentFile().mkdirs();

      try {
         logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
      } catch (IOException var3) {
         NRODLight.printThrowable(var3, "TRUST");
      }

      this.lastMessageTime = System.currentTimeMillis();
   }

   public static NRODListener getInstance() {
      if (instance == null) {
         instance = new TRUSTHandler();
      }

      return instance;
   }

   public void message(Map headers, String message) {
      StompConnectionHandler.printStompHeaders(headers);
      JSONArray messageList = new JSONArray(message);

      try {
         Connection conn = DBHandler.getConnection();
         PreparedStatement ps0001_starttime = conn.prepareStatement("SELECT scheduled_departure FROM schedule_locations WHERE schedule_uid=? AND date_from=? AND stp_indicator=? AND schedule_source=? AND loc_index=0");
         PreparedStatement ps0001 = conn.prepareStatement("INSERT INTO activations (train_id,train_id_current,start_timestamp,schedule_uid,schedule_date_from,schedule_date_to,stp_indicator,schedule_source,creation_timestamp,next_expected_update,last_update) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE next_expected_update=?, start_timestamp=?, last_update=?");
         PreparedStatement ps0002_0005 = conn.prepareStatement("UPDATE activations SET cancelled=?, last_update=? WHERE train_id=?");
         PreparedStatement ps0003_update = conn.prepareStatement("UPDATE activations SET current_delay=?, last_update=?, next_expected_update=?, finished=?, off_route=0 WHERE train_id=?");
         PreparedStatement ps0003_offroute = conn.prepareStatement("UPDATE activations SET off_route=1, last_update=?, finished=? WHERE train_id=?");
         PreparedStatement ps0006 = conn.prepareStatement("UPDATE activations SET next_expected_update=?, last_update=? WHERE train_id=?");
         PreparedStatement ps0007 = conn.prepareStatement("UPDATE activations SET train_id_current=?, last_update=? WHERE train_id=?");
         PreparedStatement ps0008 = conn.prepareStatement("UPDATE activations SET last_update=? WHERE train_id=?");

         JSONObject map;
         for(Iterator var13 = messageList.iterator(); var13.hasNext(); printTRUST(map.toString(), false)) {
            Object msgObj = var13.next();
            map = (JSONObject)msgObj;
            JSONObject header = map.getJSONObject("header");
            JSONObject body = map.getJSONObject("body");

            try {
               String var18 = header.getString("msg_type");
               byte var19 = -1;
               switch(var18.hashCode()) {
               case 1477633:
                  if (var18.equals("0001")) {
                     var19 = 0;
                  }
                  break;
               case 1477634:
                  if (var18.equals("0002")) {
                     var19 = 1;
                  }
                  break;
               case 1477635:
                  if (var18.equals("0003")) {
                     var19 = 2;
                  }
               case 1477636:
               default:
                  break;
               case 1477637:
                  if (var18.equals("0005")) {
                     var19 = 3;
                  }
                  break;
               case 1477638:
                  if (var18.equals("0006")) {
                     var19 = 4;
                  }
                  break;
               case 1477639:
                  if (var18.equals("0007")) {
                     var19 = 5;
                  }
                  break;
               case 1477640:
                  if (var18.equals("0008")) {
                     var19 = 6;
                  }
               }

               switch(var19) {
               case 0:
                  if ("O".equals(body.getString("schedule_type"))) {
                     body.put("schedule_type", "P");
                  } else if ("P".equals(body.getString("schedule_type"))) {
                     body.put("schedule_type", "O");
                  }

                  ps0001_starttime.setString(1, body.getString("train_uid"));
                  ps0001_starttime.setString(2, body.getString("schedule_start_date").substring(2).replace("-", ""));
                  ps0001_starttime.setString(3, body.getString("schedule_type"));
                  ps0001_starttime.setString(4, body.getString("schedule_source"));
                  String scheduled_departure = null;

                  try {
                     ResultSet rs = ps0001_starttime.executeQuery();
                     Throwable var22 = null;

                     try {
                        while(rs.next()) {
                           scheduled_departure = rs.getString(1);
                        }
                     } catch (Throwable var35) {
                        var22 = var35;
                        throw var35;
                     } finally {
                        if (rs != null) {
                           if (var22 != null) {
                              try {
                                 rs.close();
                              } catch (Throwable var34) {
                                 var22.addSuppressed(var34);
                              }
                           } else {
                              rs.close();
                           }
                        }

                     }
                  } catch (SQLException var37) {
                     NRODLight.printThrowable(var37, "TRUST");
                  }

                  long origin_dep_timestamp;
                  if (scheduled_departure != null) {
                     int hh = Integer.parseInt(scheduled_departure.substring(0, 2));
                     int mm = Integer.parseInt(scheduled_departure.substring(2, 4));
                     int ss = "H".equals(scheduled_departure.substring(4)) ? 30 : 0;
                     LocalDateTime todayMidnight = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()), LocalTime.of(hh, mm, ss));
                     if (Integer.parseInt(body.getString("train_id").substring(8)) != todayMidnight.getDayOfMonth()) {
                        todayMidnight = todayMidnight.plusDays(1L);
                     }

                     origin_dep_timestamp = todayMidnight.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                  } else {
                     origin_dep_timestamp = fixTimestamp(Long.parseLong(body.getString("origin_dep_timestamp")));
                  }

                  ps0001.setString(1, body.getString("train_id"));
                  ps0001.setString(2, body.getString("train_id"));
                  ps0001.setLong(3, origin_dep_timestamp);
                  ps0001.setString(4, body.getString("train_uid").replace(" ", "O"));
                  ps0001.setString(5, body.getString("schedule_start_date").substring(2).replace("-", ""));
                  ps0001.setString(6, body.getString("schedule_end_date").substring(2).replace("-", ""));
                  ps0001.setString(7, body.getString("schedule_type"));
                  ps0001.setString(8, body.getString("schedule_source"));
                  long creation_timestamp = Long.parseLong(body.getString("creation_timestamp"));
                  ps0001.setLong(9, creation_timestamp);
                  ps0001.setLong(10, origin_dep_timestamp);
                  ps0001.setLong(11, creation_timestamp);
                  ps0001.setLong(12, origin_dep_timestamp);
                  ps0001.setLong(13, origin_dep_timestamp);
                  ps0001.setLong(14, creation_timestamp);
                  ps0001.execute();
                  break;
               case 1:
                  ps0002_0005.setBoolean(1, true);
                  ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("canx_timestamp"))));
                  ps0002_0005.setString(3, body.getString("train_id"));
                  ps0002_0005.execute();
                  break;
               case 2:
                  if (!"false".equals(body.getString("correction_ind"))) {
                     break;
                  }

                  if (!"true".equals(body.getString("offroute_ind")) && !"OFF ROUTE".equals(body.getString("timetable_variation"))) {
                     if ("DEPARTURE".equals(body.get("event_type")) || "ARRIVAL".equals(body.get("event_type"))) {
                        ps0003_update.setInt(1, Integer.parseInt(body.getString("timetable_variation")) * ("EARLY".equals(body.getString("variation_status")) ? -1 : 1));
                        ps0003_update.setLong(2, System.currentTimeMillis());
                        ps0003_update.setLong(3, fixTimestamp(Long.parseLong(body.getString("actual_timestamp"))) + ("".equals(body.getString("next_report_run_time")) ? 0L : Long.parseLong(body.getString("next_report_run_time")) * 60000L));
                        ps0003_update.setBoolean(4, "true".equals(body.getString("train_terminated")));
                        ps0003_update.setString(5, body.getString("train_id"));
                        ps0003_update.execute();
                     }
                     break;
                  }

                  ps0003_offroute.setLong(1, System.currentTimeMillis());
                  ps0003_offroute.setBoolean(2, "true".equals(body.getString("train_terminated")));
                  ps0003_offroute.setString(3, body.getString("train_id"));
                  ps0003_offroute.execute();
                  break;
               case 3:
                  ps0002_0005.setBoolean(1, false);
                  ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("reinstatement_timestamp"))));
                  ps0002_0005.setString(3, body.getString("train_id"));
                  ps0002_0005.execute();
                  break;
               case 4:
                  ps0006.setLong(1, Long.parseLong(body.getString("coo_timestamp")));
                  ps0006.setLong(2, fixTimestamp(Long.parseLong(body.getString("dep_timestamp"))));
                  ps0006.setString(3, body.getString("train_id"));
                  ps0006.execute();
                  break;
               case 5:
                  ps0007.setString(1, body.getString("revised_train_id"));
                  ps0007.setLong(2, Long.parseLong(body.getString("event_timestamp")));
                  ps0007.setString(3, body.getString("train_id"));
                  ps0007.execute();
                  break;
               case 6:
                  ps0008.setLong(1, Long.parseLong(body.getString("event_timestamp")));
                  ps0008.setString(2, body.getString("train_id"));
                  ps0008.execute();
               }
            } catch (SQLException var38) {
               NRODLight.printThrowable(var38, "TRUST");
            }
         }
      } catch (SQLException var39) {
         NRODLight.printThrowable(var39, "TRUST");
      }

      RateMonitor.getInstance().onTRUSTMessage(messageList.length());
      this.lastMessageTime = System.currentTimeMillis();
      StompConnectionHandler.lastMessageTimeGeneral = this.lastMessageTime;
      StompConnectionHandler.ack((String)headers.get("ack"));
   }

   private static long fixTimestamp(long timestamp) {
      return timestamp - (TimeZone.getDefault().inDaylightTime(new Date(timestamp)) ? 3600000L : 0L);
   }

   private static String cifTime(long timestamp) {
      String s = cifTime.format(new Date(timestamp));
      return s.substring(0, 4) + (s.endsWith("30") ? "H" : "");
   }

   public long getTimeout() {
      return System.currentTimeMillis() - this.lastMessageTime;
   }

   public long getTimeoutThreshold() {
      return 30000L;
   }

   private static synchronized void printTRUST(String message, boolean toErr) {
      printTRUST(message, toErr, System.currentTimeMillis());
   }

   private static void printTRUST(String message, boolean toErr, long timestamp) {
      String newDate = NRODLight.sdfDate.format(new Date());
      if (!lastLogDate.equals(newDate)) {
         logStream.close();
         lastLogDate = newDate;
         logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TRUST" + File.separator + newDate.replace("/", "-") + ".log");
         logFile.getParentFile().mkdirs();

         try {
            logFile.createNewFile();
            logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
         } catch (IOException var6) {
            NRODLight.printThrowable(var6, "TRUST");
         }
      }

      logStream.println("[" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + (toErr ? "!!!> " : "") + message + (toErr ? " <!!!" : ""));
   }
}
