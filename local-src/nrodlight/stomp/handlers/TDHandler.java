package nrodlight.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.stomp.NRODListener;
import nrodlight.stomp.StompConnectionHandler;
import nrodlight.ws.EASMWebSocketImpl;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.json.JSONArray;
import org.json.JSONObject;

public class TDHandler implements NRODListener {
   private long lastMessageTime = 0L;
   private static String lastLogDate = "";
   private static NRODListener instance = null;
   public static final Map DATA_MAP = new ConcurrentHashMap();

   private TDHandler() {
      saveTDData(DATA_MAP);
      this.lastMessageTime = System.currentTimeMillis();
   }

   public static NRODListener getInstance() {
      if (instance == null) {
         instance = new TDHandler();
      }

      return instance;
   }

   public void message(Map headers, String body) {
      StompConnectionHandler.printStompHeaders(headers);
      JSONArray messageList = new JSONArray(body);
      Map updateMap = new HashMap();
      int updateCount = 0;
      List timestamps = new ArrayList(messageList.length());
      Iterator var7 = messageList.iterator();

      while(var7.hasNext()) {
         Object mapObj = var7.next();
         JSONObject map = (JSONObject)mapObj;

         try {
            String msgType = String.valueOf(map.keySet().toArray()[0]);
            JSONObject indvMsg = map.getJSONObject(msgType);
            String msgAddr = indvMsg.getString("area_id") + indvMsg.optString("address");
            String var13 = msgType.toUpperCase();
            byte var14 = -1;
            switch(var13.hashCode()) {
            case -1851194507:
               if (var13.equals("SF_MSG")) {
                  var14 = 4;
               }
               break;
            case -1850270986:
               if (var13.equals("SG_MSG")) {
                  var14 = 5;
               }
               break;
            case -1849347465:
               if (var13.equals("SH_MSG")) {
                  var14 = 6;
               }
               break;
            case 1981088768:
               if (var13.equals("CA_MSG")) {
                  var14 = 0;
               }
               break;
            case 1982012289:
               if (var13.equals("CB_MSG")) {
                  var14 = 1;
               }
               break;
            case 1982935810:
               if (var13.equals("CC_MSG")) {
                  var14 = 2;
               }
               break;
            case 1998635667:
               if (var13.equals("CT_MSG")) {
                  var14 = 3;
               }
            }

            long time;
            switch(var14) {
            case 0:
               updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "");
               updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"));
               time = Long.parseLong(indvMsg.getString("time"));
               timestamps.add(time);
               this.printTD(String.format("Step %s from %s to %s", indvMsg.getString("descr"), indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-")), false, time);
               ++updateCount;
               break;
            case 1:
               updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "");
               time = Long.parseLong(indvMsg.getString("time"));
               timestamps.add(time);
               this.printTD(String.format("Cancel %s from %s", indvMsg.getString("descr"), indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-")), false, time);
               ++updateCount;
               break;
            case 2:
               updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"));
               time = Long.parseLong(indvMsg.getString("time"));
               timestamps.add(time);
               this.printTD(String.format("Interpose %s to %s", indvMsg.getString("descr"), indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-")), false, time);
               ++updateCount;
               break;
            case 3:
               updateMap.put("XXHB" + indvMsg.getString("area_id"), indvMsg.getString("report_time"));
               time = Long.parseLong(indvMsg.getString("time"));
               timestamps.add(time);
               this.printTD(String.format("Heartbeat from %s at time %s", indvMsg.getString("area_id"), indvMsg.getString("report_time")), false, time);
               ++updateCount;
               break;
            case 4:
               char[] data = zfill(Integer.toBinaryString(Integer.parseInt(indvMsg.getString("data"), 16)), 8).toCharArray();
               long time = Long.parseLong(indvMsg.getString("time"));
               timestamps.add(time);

               for(int i = 0; i < data.length; ++i) {
                  String address = msgAddr + ":" + Integer.toString(8 - i);
                  String dataBit = String.valueOf(data[i]);
                  if (!DATA_MAP.containsKey(address) || DATA_MAP.get(address) == null || !dataBit.equals(DATA_MAP.get(address))) {
                     this.printTD(String.format("Change %s from %s to %s", address, DATA_MAP.getOrDefault(address, "0"), dataBit), false, time);
                     ++updateCount;
                  }

                  updateMap.put(address, dataBit);
               }
               break;
            case 5:
            case 6:
               String binary = zfill(Long.toBinaryString(Long.parseLong(indvMsg.getString("data"), 16)), 32);
               int start = Integer.parseInt(indvMsg.getString("address"), 16);
               long time = Long.parseLong(indvMsg.getString("time"));
               timestamps.add(time);

               for(int i = 0; i < 4; ++i) {
                  for(int j = 0; j < 8; ++j) {
                     String id = String.format("%s%s:%s", indvMsg.getString("area_id"), zfill(Integer.toHexString(start + i), 2), 8 - j).toUpperCase();
                     String dat = String.valueOf(binary.charAt(8 * i + j));
                     updateMap.put(id, dat);
                     if (!DATA_MAP.containsKey(id) || DATA_MAP.get(id) == null || dat.equals(DATA_MAP.get(id))) {
                        this.printTD(String.format("Change %s from %s to %s", id, DATA_MAP.getOrDefault(id, "0"), dat), false, time);
                        ++updateCount;
                     }
                  }
               }
            }
         } catch (Exception var23) {
            NRODLight.printThrowable(var23, "TD");
         }
      }

      DATA_MAP.putAll(updateMap);
      if (NRODLight.webSocket != null) {
         Map updateAreaMap = new HashMap();
         updateMap.forEach((key, value) -> {
            String area = key.substring(0, 2);
            Map m = (Map)updateAreaMap.getOrDefault(area, new HashMap());
            m.put(key, value);
            updateAreaMap.put(area, m);
         });
         Map messages = new HashMap();
         updateAreaMap.forEach((key, value) -> {
            JSONObject container = new JSONObject();
            JSONObject message = new JSONObject();
            message.put("type", "SEND_UPDATE");
            message.put("timestamp", System.currentTimeMillis());
            message.put("message", value);
            if (!key.isEmpty()) {
               message.put("area", key);
            }

            container.put("Message", message);
            messages.put(key, container.toString());
         });
         NRODLight.webSocket.getConnections().stream().filter(Objects::nonNull).filter(WebSocket::isOpen).filter((c) -> {
            return c instanceof EASMWebSocketImpl;
         }).forEach((ws) -> {
            try {
               ((EASMWebSocketImpl)ws).send(messages);
            } catch (WebsocketNotConnectedException var3) {
            }

         });
      }

      saveTDData(updateMap);
      RateMonitor.getInstance().onTDMessage((double)(System.currentTimeMillis() - Long.parseLong((String)headers.get("timestamp"))) / 1000.0D, timestamps.stream().mapToLong((e) -> {
         return System.currentTimeMillis() - e;
      }).average().orElse(0.0D) / 1000.0D, updateCount);
      this.lastMessageTime = System.currentTimeMillis();
      StompConnectionHandler.lastMessageTimeGeneral = this.lastMessageTime;
      StompConnectionHandler.ack((String)headers.get("ack"));
   }

   public static String zfill(String s, int len) {
      return String.format("%" + len + "s", s).replace(" ", "0");
   }

   public static void saveTDData(Map mapToSave) {
      File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
      if (!mapToSave.isEmpty()) {
         JSONObject cClObj = new JSONObject();
         JSONObject sClObj = new JSONObject();
         mapToSave.keySet().forEach((key) -> {
            String area = key.substring(0, 2);
            if (key.charAt(4) == ':') {
               if (!sClObj.has(area)) {
                  sClObj.put(area, new JSONObject());
               }
            } else if (!cClObj.has(area)) {
               cClObj.put(area, new JSONObject());
            }

         });
         DATA_MAP.forEach((k, v) -> {
            String area = k.substring(0, 2);
            if (k.charAt(4) == ':') {
               if (sClObj.has(area)) {
                  sClObj.getJSONObject(area).put(k, v);
               }
            } else if (cClObj.has(area)) {
               cClObj.getJSONObject(area).put(k, v);
            }

         });
         sClObj.keys().forEachRemaining((k) -> {
            try {
               String out = sClObj.getJSONObject(k).toString();
               File f = new File(TDDataDir, k + ".s.td");
               if (!f.exists()) {
                  f.createNewFile();
               }

               FileWriter fw = new FileWriter(f);
               Throwable var6 = null;

               try {
                  fw.write(out);
               } catch (Throwable var16) {
                  var6 = var16;
                  throw var16;
               } finally {
                  if (fw != null) {
                     if (var6 != null) {
                        try {
                           fw.close();
                        } catch (Throwable var15) {
                           var6.addSuppressed(var15);
                        }
                     } else {
                        fw.close();
                     }
                  }

               }
            } catch (IOException var18) {
               NRODLight.printThrowable(var18, "TD");
            }

         });
         cClObj.keys().forEachRemaining((k) -> {
            try {
               String out = cClObj.getJSONObject(k).toString();
               File f = new File(TDDataDir, k + ".c.td");
               if (!f.exists()) {
                  f.createNewFile();
               }

               FileWriter fw = new FileWriter(f);
               Throwable var6 = null;

               try {
                  fw.write(out);
               } catch (Throwable var16) {
                  var6 = var16;
                  throw var16;
               } finally {
                  if (fw != null) {
                     if (var6 != null) {
                        try {
                           fw.close();
                        } catch (Throwable var15) {
                           var6.addSuppressed(var15);
                        }
                     } else {
                        fw.close();
                     }
                  }

               }
            } catch (IOException var18) {
               NRODLight.printThrowable(var18, "TD");
            }

         });
      }

   }

   public long getTimeout() {
      return System.currentTimeMillis() - this.lastMessageTime;
   }

   public long getTimeoutThreshold() {
      return 30000L;
   }

   private void printTD(String message, boolean toErr, long timestamp) {
      String newDate = NRODLight.sdfDate.format(new Date());
      if (!lastLogDate.equals(newDate)) {
         lastLogDate = newDate;
         File fileReplaySave = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "ReplaySaves" + File.separator + newDate.replace("/", "-") + ".json");
         fileReplaySave.getParentFile().mkdirs();

         try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(fileReplaySave));
            Throwable var8 = null;

            try {
               (new JSONObject()).put("TDData", DATA_MAP).write(bw);
            } catch (Throwable var18) {
               var8 = var18;
               throw var18;
            } finally {
               if (bw != null) {
                  if (var8 != null) {
                     try {
                        bw.close();
                     } catch (Throwable var17) {
                        var8.addSuppressed(var17);
                     }
                  } else {
                     bw.close();
                  }
               }

            }
         } catch (IOException var20) {
            NRODLight.printThrowable(var20, "TD");
         }
      }

   }
}
