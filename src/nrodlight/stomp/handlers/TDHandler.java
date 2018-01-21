package nrodlight.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.stomp.NRODListener;
import nrodlight.stomp.StompConnectionHandler;
import nrodlight.ws.EASMWebSocketImpl;
import org.java_websocket.WebSocket;
import org.json.JSONArray;
import org.json.JSONObject;

public class TDHandler implements NRODListener
{
    private long lastMessageTime = 0;
    
    private static NRODListener instance = null;
    private TDHandler()
    {
        saveTDData(DATA_MAP);

        lastMessageTime = System.currentTimeMillis();
    }
    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new TDHandler();

        return instance;
    }

    public static final Map<String, String> DATA_MAP = new ConcurrentHashMap<>();

    @Override
    public void message(Map<String, String> headers, String body)
    {
        StompConnectionHandler.printStompHeaders(headers);

        JSONArray messageList = new JSONArray(body);
        Map<String, String> updateMap = new HashMap<>();
        int updateCount = 0;

        for (Object mapObj : messageList)
        {
            JSONObject map = (JSONObject) mapObj;
            try
            {
                String msgType = String.valueOf(map.keySet().toArray()[0]);
                JSONObject indvMsg = map.getJSONObject(msgType);
                
                String msgAddr = indvMsg.getString("area_id") + indvMsg.optString("address");
                
                switch (msgType.toUpperCase())
                {
                    case "CA_MSG":
                    {
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "");
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"));

                        printTD(String.format("Step %s from %s to %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"),
                                indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        updateCount++;
                        break;
                    }
                    
                    case "CB_MSG":
                    {
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "");

                        printTD(String.format("Cancel %s from %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        updateCount++;
                        break;
                    }
                    
                    case "CC_MSG":
                    {
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"));

                        printTD(String.format("Interpose %s to %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        updateCount++;
                        break;
                    }
                    
                    case "CT_MSG":
                    {
                        updateMap.put("XXHB" + indvMsg.getString("area_id"), indvMsg.getString("report_time"));

                        printTD(String.format("Heartbeat from %s at time %s",
                                indvMsg.getString("area_id"),
                                indvMsg.getString("report_time")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        updateCount++;
                        break;
                    }

                    case "SF_MSG":
                    {
                        char[] data = zfill(Integer.toBinaryString(Integer.parseInt(indvMsg.getString("data"), 16)), 8).toCharArray();
                        
                        for (int i = 0; i < data.length; i++)
                        {
                            String address = msgAddr + ":" + Integer.toString(8 - i);
                            String dataBit = String.valueOf(data[i]);
                            
                            if (!DATA_MAP.containsKey(address) || DATA_MAP.get(address) == null || !dataBit.equals(DATA_MAP.get(address)))
                            {
                                printTD(String.format("Change %s from %s to %s",
                                        address,
                                        DATA_MAP.getOrDefault(address, "0"),
                                        dataBit
                                    ),
                                    false,
                                    Long.parseLong(indvMsg.getString("time")));
                                updateCount++;
                            }
                            updateMap.put(address, dataBit);
                        }
                        break;
                    }

                    case "SG_MSG":
                    case "SH_MSG":
                    {
                        String binary = zfill(Long.toBinaryString(Long.parseLong(indvMsg.getString("data"), 16)), 32);
                        int start = Integer.parseInt(indvMsg.getString("address"), 16);
                        for (int i = 0; i < 4; i++)
                            for (int j = 0; j < 8; j++)
                            {
                                String id = String.format("%s%s:%s",
                                                indvMsg.getString("area_id"),
                                                zfill(Integer.toHexString(start+i), 2),
                                                8 - j
                                            ).toUpperCase();
                                String dat = String.valueOf(binary.charAt(8 * i + j));
                                updateMap.put(id, dat);
                                if (!DATA_MAP.containsKey(id) || DATA_MAP.get(id) == null || dat.equals(DATA_MAP.get(id)))
                                {
                                    printTD(String.format("Change %s from %s to %s",
                                            id,
                                            DATA_MAP.getOrDefault(id, "0"),
                                            dat
                                        ),
                                        false,
                                        Long.parseLong(indvMsg.getString("time")));
                                    updateCount++;
                                }
                            }
                        break;
                    }
                }
                
            }
            catch (Exception e) { NRODLight.printThrowable(e, "TD"); }
        }
        
        DATA_MAP.putAll(updateMap);
        if (NRODLight.webSocket != null)
        {
            Map<String, Map<String, String>> updateAreaMap = new HashMap<>();
            for (Map.Entry<String, String> e : updateMap.entrySet())
            {
                String area = e.getKey().substring(0, 2);
                Map<String, String> m = updateAreaMap.getOrDefault(area, new HashMap<>());
                m.put(e.getKey(), e.getValue());
                updateAreaMap.put(area, m);
            }

            Map<String, String> messages = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> e : updateAreaMap.entrySet())
            {
                JSONObject container = new JSONObject();
                JSONObject message = new JSONObject();
                message.put("type", "SEND_UPDATE");
                message.put("timestamp", System.currentTimeMillis());
                message.put("message", e.getValue());
                if (!e.getKey().isEmpty())
                    message.put("area", e.getKey());
                container.put("Message", message);

                messages.put(e.getKey(), container.toString());
            }
            for (WebSocket ws : NRODLight.webSocket.connections())
                if (ws instanceof EASMWebSocketImpl)
                    ((EASMWebSocketImpl) ws).send(messages);
        }
        saveTDData(updateMap);
        RateMonitor.getInstance().onTDMessage((System.currentTimeMillis() - Long.parseLong(headers.get("timestamp")))/1000f, updateCount);

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public static String zfill(long l, int len)
    {
        return zfill(String.valueOf(l), len);
    }
    public static String zfill(String s, int len)
    {
        return String.format("%"+len+"s", s).replace(" ", "0");
    }
    
    public static void saveTDData(Map<String, String> mapToSave)
    {
        File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
        if (!mapToSave.isEmpty())
        {
            JSONObject cClObj = new JSONObject();
            JSONObject sClObj = new JSONObject();
            
            mapToSave.keySet().forEach(key ->
            {
                String area = key.substring(0, 2);
                if (key.charAt(4) == ':')
                {
                    if (sClObj.has(area))
                        sClObj.put(area, new JSONObject());
                }
                else
                {
                    if (cClObj.has(area))
                        cClObj.put(area, new JSONObject());
                }
            });
            
            DATA_MAP.forEach((k, v) ->
            {
                String area = k.substring(0, 2);
                if (k.charAt(4) == ':')
                {
                    if (sClObj.has(area))
                        sClObj.getJSONObject(area).put(k, v);
                }
                else
                {
                    if (cClObj.has(area))
                        cClObj.getJSONObject(area).put(k, v);
                }
            });
            
            sClObj.keys().forEachRemaining(k ->
            {
                String out = sClObj.getJSONObject(k).toString();
                try(BufferedWriter bw = new BufferedWriter(new FileWriter(new File(TDDataDir, k+".s.td"))))
                {
                    bw.write(out);
                }
                catch (IOException ex) { NRODLight.printThrowable(ex, "TD"); }
            });
            cClObj.keys().forEachRemaining(k ->
            {
                String out = cClObj.getJSONObject(k).toString();
                try(BufferedWriter bw = new BufferedWriter(new FileWriter(new File(TDDataDir, k+".c.td"))))
                {
                    bw.write(out);
                }
                catch (IOException ex) { NRODLight.printThrowable(ex, "TD"); }
            });
        }
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 30000; }

    private void printTD(String message, boolean toErr, long timestamp)
    {
        if (NRODLight.verbose)
        {
            if (toErr)
                NRODLight.printErr("[TD] [".concat(NRODLight.sdfDateTime.format(new Date(timestamp))).concat("] ").concat(message));
            else
                NRODLight.printOut("[TD] [".concat(NRODLight.sdfDateTime.format(new Date(timestamp))).concat("] ").concat(message));
        }
    }
}