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
         PreparedStatement ps0001_starttime_smart = conn.prepareStatement("SELECT scheduled_departure,scheduled_pass FROM schedule_locations l INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE schedule_uid=? AND date_from=? AND stp_indicator=? AND schedule_source=? AND s.reports=1 ORDER BY loc_index ASC LIMIT 1");
         PreparedStatement ps0001_starttime_any = conn.prepareStatement("SELECT scheduled_departure FROM schedule_locations WHERE schedule_uid=? AND date_from=? AND stp_indicator=? AND schedule_source=? AND loc_index=0");
         PreparedStatement ps0001 = conn.prepareStatement("INSERT INTO activations (train_id,train_id_current,start_timestamp,schedule_uid,schedule_date_from,schedule_date_to,stp_indicator,schedule_source,creation_timestamp,next_expected_update,last_update) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE next_expected_update=?, start_timestamp=?, last_update=?");
         PreparedStatement ps0002_0005 = conn.prepareStatement("UPDATE activations SET cancelled=?, last_update=? WHERE train_id=?");
         PreparedStatement ps0003_update = conn.prepareStatement("UPDATE activations SET current_delay=?, last_update=?, last_update_tiploc=COALESCE((SELECT tiploc FROM corpus WHERE stanox=? LIMIT 1), ?), next_expected_update=?, next_expected_tiploc=COALESCE((SELECT tiploc FROM corpus WHERE stanox=? LIMIT 1), ?), finished=?, off_route=0 WHERE train_id=? AND last_update<=?");
         PreparedStatement ps0003_next_update_arr = conn.prepareStatement("SELECT l.tiploc,c.stanox,scheduled_arrival,scheduled_departure,scheduled_pass FROM schedule_locations l INNER JOIN activations a ON l.schedule_uid=a.schedule_uid AND a.schedule_date_from=l.date_from AND l.stp_indicator=a.stp_indicator AND l.schedule_source=a.schedule_source INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE a.train_id=? AND s.reports=1 AND loc_index>=(SELECT loc_index FROM schedule_locations l2 INNER JOIN activations a2 ON l2.schedule_uid=a2.schedule_uid AND l2.date_from=a2.schedule_date_from AND l2.stp_indicator=a2.stp_indicator AND l2.schedule_source=a2.schedule_source WHERE a2.train_id=? AND (l2.scheduled_arrival=? OR l2.scheduled_pass=?)) ORDER BY loc_index ASC LIMIT 1");
         PreparedStatement ps0003_next_update_dep = conn.prepareStatement("SELECT l.tiploc,c.stanox,scheduled_arrival,scheduled_departure,scheduled_pass FROM schedule_locations l INNER JOIN activations a ON l.schedule_uid=a.schedule_uid AND a.schedule_date_from=l.date_from AND l.stp_indicator=a.stp_indicator AND l.schedule_source=a.schedule_source INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE a.train_id=? AND s.reports=1 AND loc_index>(SELECT loc_index FROM schedule_locations l2 INNER JOIN activations a2 ON l2.schedule_uid=a2.schedule_uid AND l2.date_from=a2.schedule_date_from AND l2.stp_indicator=a2.stp_indicator AND l2.schedule_source=a2.schedule_source WHERE a2.train_id=? AND (l2.scheduled_departure=? OR l2.scheduled_pass=?)) ORDER BY loc_index ASC LIMIT 1");
         PreparedStatement ps0003_offroute = conn.prepareStatement("UPDATE activations SET off_route=1, last_update=?, finished=? WHERE train_id=? AND last_update<=?");
         PreparedStatement ps0006 = conn.prepareStatement("UPDATE activations SET next_expected_update=?, last_update=? WHERE train_id=? AND next_expected_update<=? AND last_update<=?");
         PreparedStatement ps0007 = conn.prepareStatement("UPDATE activations SET train_id_current=?, last_update=? WHERE train_id=?");
         PreparedStatement ps0008 = conn.prepareStatement("UPDATE activations SET last_update=? WHERE train_id=?");

         JSONObject map;
         for(Iterator var16 = messageList.iterator(); var16.hasNext(); printTRUST(map.toString(), false)) {
            Object msgObj = var16.next();
            map = (JSONObject)msgObj;
            JSONObject header = map.getJSONObject("header");
            JSONObject body = map.getJSONObject("body");
            long messageTime = Long.parseLong(header.getString("msg_queue_timestamp"));

            try {
               String var23 = header.getString("msg_type");
               byte var24 = -1;
               switch(var23.hashCode()) {
               case 1477633:
                  if (var23.equals("0001")) {
                     var24 = 0;
                  }
                  break;
               case 1477634:
                  if (var23.equals("0002")) {
                     var24 = 1;
                  }
                  break;
               case 1477635:
                  if (var23.equals("0003")) {
                     var24 = 2;
                  }
               case 1477636:
               default:
                  break;
               case 1477637:
                  if (var23.equals("0005")) {
                     var24 = 3;
                  }
                  break;
               case 1477638:
                  if (var23.equals("0006")) {
                     var24 = 4;
                  }
                  break;
               case 1477639:
                  if (var23.equals("0007")) {
                     var24 = 5;
                  }
                  break;
               case 1477640:
                  if (var23.equals("0008")) {
                     var24 = 6;
                  }
               }

               long next_expected_update;
               switch(var24) {
               case 0:
                  if ("O".equals(body.getString("schedule_type"))) {
                     body.put("schedule_type", "P");
                  } else if ("P".equals(body.getString("schedule_type"))) {
                     body.put("schedule_type", "O");
                  }

                  String scheduled_departure = null;
                  ps0001_starttime_smart.setString(1, body.getString("train_uid"));
                  ps0001_starttime_smart.setString(2, body.getString("schedule_start_date").substring(2).replace("-", ""));
                  ps0001_starttime_smart.setString(3, body.getString("schedule_type"));
                  ps0001_starttime_smart.setString(4, body.getString("schedule_source"));

                  ResultSet rs;
                  Throwable var27;
                  try {
                     rs = ps0001_starttime_smart.executeQuery();
                     var27 = null;

                     try {
                        if (rs.next()) {
                           if (rs.getString(1) != null && !"     ".equals(rs.getString(1))) {
                              scheduled_departure = rs.getString(1);
                           } else if (rs.getString(2) != null && !"     ".equals(rs.getString(2))) {
                              scheduled_departure = rs.getString(2);
                           }
                        }
                     } catch (Throwable var142) {
                        var27 = var142;
                        throw var142;
                     } finally {
                        if (rs != null) {
                           if (var27 != null) {
                              try {
                                 rs.close();
                              } catch (Throwable var132) {
                                 var27.addSuppressed(var132);
                              }
                           } else {
                              rs.close();
                           }
                        }

                     }
                  } catch (SQLException var144) {
                     NRODLight.printThrowable(var144, "TRUST");
                  }

                  if (scheduled_departure == null) {
                     ps0001_starttime_any.setString(1, body.getString("train_uid"));
                     ps0001_starttime_any.setString(2, body.getString("schedule_start_date").substring(2).replace("-", ""));
                     ps0001_starttime_any.setString(3, body.getString("schedule_type"));
                     ps0001_starttime_any.setString(4, body.getString("schedule_source"));

                     try {
                        rs = ps0001_starttime_any.executeQuery();
                        var27 = null;

                        try {
                           while(rs.next()) {
                              scheduled_departure = rs.getString(1);
                           }
                        } catch (Throwable var139) {
                           var27 = var139;
                           throw var139;
                        } finally {
                           if (rs != null) {
                              if (var27 != null) {
                                 try {
                                    rs.close();
                                 } catch (Throwable var131) {
                                    var27.addSuppressed(var131);
                                 }
                              } else {
                                 rs.close();
                              }
                           }

                        }
                     } catch (SQLException var141) {
                        NRODLight.printThrowable(var141, "TRUST");
                     }
                  }

                  long origin_dep_timestamp;
                  if (scheduled_departure != null) {
                     int hh = Integer.parseInt(scheduled_departure.substring(0, 2));
                     int mm = Integer.parseInt(scheduled_departure.substring(2, 4));
                     int ss = "H".equals(scheduled_departure.substring(4)) ? 30 : 0;
                     LocalDateTime todayMidnight = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()), LocalTime.of(hh, mm, ss));
                     if (Integer.parseInt(body.getString("train_id").substring(8)) != todayMidnight.getDayOfMonth()) {
                        origin_dep_timestamp = todayMidnight.plusDays(1L).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                     } else {
                        origin_dep_timestamp = todayMidnight.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                     }
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

                  double delay = 0.0D;
                  next_expected_update = -1L;
                  long at = fixTimestamp(Long.parseLong(body.getString("actual_timestamp")));
                  String next_expected_tiploc = null;
                  if (!"true".equals(body.getString("offroute_ind")) && !"OFF ROUTE".equals(body.getString("timetable_variation"))) {
                     long pt;
                     String time;
                     ResultSet rs;
                     Throwable var41;
                     if ("DEPARTURE".equals(body.get("event_type"))) {
                        pt = fixTimestamp(Long.parseLong(body.getString("planned_timestamp")));
                        delay = (double)(at - pt) / 60000.0D;
                        ps0003_next_update_dep.setString(1, body.getString("train_id"));
                        ps0003_next_update_dep.setString(2, body.getString("train_id"));
                        time = cifTime(pt);
                        ps0003_next_update_dep.setString(3, time);
                        ps0003_next_update_dep.setString(4, time);

                        try {
                           rs = ps0003_next_update_dep.executeQuery();
                           var41 = null;

                           try {
                              if (rs.next()) {
                                 next_expected_tiploc = rs.getString(1);
                                 if (rs.getString(3) != null && !rs.getString(3).trim().isEmpty()) {
                                    next_expected_update = timeCif(rs.getString(3), pt);
                                 } else if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty()) {
                                    next_expected_update = timeCif(rs.getString(4), pt);
                                 } else if (rs.getString(5) != null && !rs.getString(5).trim().isEmpty()) {
                                    next_expected_update = timeCif(rs.getString(5), pt);
                                 }
                              } else {
                                 next_expected_update = -2L;
                              }
                           } catch (Throwable var133) {
                              var41 = var133;
                              throw var133;
                           } finally {
                              if (rs != null) {
                                 if (var41 != null) {
                                    try {
                                       rs.close();
                                    } catch (Throwable var130) {
                                       var41.addSuppressed(var130);
                                    }
                                 } else {
                                    rs.close();
                                 }
                              }

                           }
                        } catch (SQLException var135) {
                           NRODLight.printThrowable(var135, "TRUST");
                        }

                        if (next_expected_update == -1L) {
                           next_expected_tiploc = body.getString("next_report_stanox");
                           next_expected_update = at + ("".equals(body.getString("next_report_run_time")) ? 0L : Long.parseLong(body.getString("next_report_run_time")) * 60000L);
                        }
                     } else if ("ARRIVAL".equals(body.get("event_type"))) {
                        pt = fixTimestamp(Long.parseLong(body.getString("planned_timestamp")));
                        delay = (double)(at - pt) / 60000.0D;
                        ps0003_next_update_arr.setString(1, body.getString("train_id"));
                        ps0003_next_update_arr.setString(2, body.getString("train_id"));
                        time = cifTime(pt);
                        ps0003_next_update_arr.setString(3, time);
                        ps0003_next_update_arr.setString(4, time);

                        try {
                           rs = ps0003_next_update_arr.executeQuery();
                           var41 = null;

                           try {
                              if (rs.next()) {
                                 next_expected_tiploc = rs.getString(1);
                                 if (body.getString("loc_stanox").equals(rs.getString(2))) {
                                    if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty()) {
                                       next_expected_update = timeCif(rs.getString(4), pt);
                                    } else if (rs.getString(5) != null && !rs.getString(5).trim().isEmpty()) {
                                       next_expected_update = timeCif(rs.getString(5), pt);
                                    }
                                 } else if (rs.getString(3) != null && !rs.getString(3).trim().isEmpty()) {
                                    next_expected_update = timeCif(rs.getString(3), pt);
                                 } else if (rs.getString(4) != null && !rs.getString(4).trim().isEmpty()) {
                                    next_expected_update = timeCif(rs.getString(4), pt);
                                 } else if (rs.getString(5) != null && !rs.getString(5).trim().isEmpty()) {
                                    next_expected_update = timeCif(rs.getString(5), pt);
                                 }
                              } else {
                                 next_expected_update = -2L;
                              }
                           } catch (Throwable var136) {
                              var41 = var136;
                              throw var136;
                           } finally {
                              if (rs != null) {
                                 if (var41 != null) {
                                    try {
                                       rs.close();
                                    } catch (Throwable var129) {
                                       var41.addSuppressed(var129);
                                    }
                                 } else {
                                    rs.close();
                                 }
                              }

                           }
                        } catch (SQLException var138) {
                           NRODLight.printThrowable(var138, "TRUST");
                        }

                        if (next_expected_update == -1L) {
                           next_expected_tiploc = body.getString("next_report_stanox");
                           next_expected_update = at + ("".equals(body.getString("next_report_run_time")) ? 0L : Long.parseLong(body.getString("next_report_run_time")) * 60000L);
                        }
                     }
                  } else {
                     ps0003_offroute.setLong(1, messageTime);
                     ps0003_offroute.setBoolean(2, "true".equals(body.getString("train_terminated")));
                     ps0003_offroute.setString(3, body.getString("train_id"));
                     ps0003_offroute.setLong(4, at);
                     ps0003_offroute.execute();
                  }

                  if (next_expected_update != -1L) {
                     ps0003_update.setDouble(1, delay);
                     ps0003_update.setLong(2, at);
                     ps0003_update.setString(3, body.getString("loc_stanox"));
                     ps0003_update.setString(4, body.getString("loc_stanox"));
                     ps0003_update.setLong(5, next_expected_update == -2L ? -1L : next_expected_update);
                     ps0003_update.setString(6, next_expected_tiploc);
                     ps0003_update.setString(7, next_expected_tiploc);
                     ps0003_update.setBoolean(8, "true".equals(body.getString("train_terminated")));
                     ps0003_update.setString(9, body.getString("train_id"));
                     ps0003_update.setLong(10, at);
                     ps0003_update.execute();
                  }
                  break;
               case 3:
                  ps0002_0005.setBoolean(1, false);
                  ps0002_0005.setLong(2, fixTimestamp(Long.parseLong(body.getString("reinstatement_timestamp"))));
                  ps0002_0005.setString(3, body.getString("train_id"));
                  ps0002_0005.execute();
                  break;
               case 4:
                  long dt = fixTimestamp(Long.parseLong(body.getString("dep_timestamp")));
                  next_expected_update = fixTimestamp(Long.parseLong(body.getString("coo_timestamp")));
                  ps0006.setLong(1, dt);
                  ps0006.setLong(2, next_expected_update);
                  ps0006.setString(3, body.getString("train_id"));
                  ps0006.setLong(4, dt);
                  ps0006.setLong(5, next_expected_update);
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
            } catch (SQLException var145) {
               NRODLight.printThrowable(var145, "TRUST");
            }
         }
      } catch (SQLException var146) {
         NRODLight.printThrowable(var146, "TRUST");
      }

      RateMonitor.getInstance().onTRUSTMessage(messageList.length());
      this.lastMessageTime = System.currentTimeMillis();
      StompConnectionHandler.lastMessageTimeGeneral = this.lastMessageTime;
      StompConnectionHandler.ack((String)headers.get("ack"));
   }

   private static long fixTimestamp(long timestamp) {
      return timestamp - (TimeZone.getDefault().inDaylightTime(new Date(timestamp)) ? 3600000L : 0L);
   }

   private static String cifTime(String timestamp, boolean fix) {
      return fix ? cifTime(fixTimestamp(Long.parseLong(timestamp))) : cifTime(Long.parseLong(timestamp));
   }

   private static String cifTime(long timestamp) {
      String s = cifTime.format(new Date(timestamp));
      return s.substring(0, 4) + (s.endsWith("30") ? "H" : "");
   }

   private static long timeCif(String s, long refTimestamp) {
      int hh = Integer.parseInt(s.substring(0, 2));
      int mm = Integer.parseInt(s.substring(2, 4));
      int ss = "H".equals(s.substring(4)) ? 30 : 0;
      LocalDateTime time = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()), LocalTime.of(hh, mm, ss));
      long out = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
      return out < refTimestamp ? time.plusDays(1L).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : out;
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
