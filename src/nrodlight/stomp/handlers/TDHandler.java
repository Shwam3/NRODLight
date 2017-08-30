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
                        if (!"".equals(DATA_MAP.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "")));
                            updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "");
                            
                        if (!indvMsg.getString("descr").equals(DATA_MAP.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"))));
                            updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"));

                        printTD(String.format("Step %s from %s to %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"),
                                indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        break;
                    }
                    
                    case "CB_MSG":
                    {
                        if (!"".equals(DATA_MAP.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "")))
                            updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-"), "");

                        printTD(String.format("Cancel %s from %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("from").replace("*", "-")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        break;
                    }
                    
                    case "CC_MSG":
                    {
                        if (!indvMsg.getString("descr").equals(DATA_MAP.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"))));
                            updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-"), indvMsg.getString("descr"));

                        printTD(String.format("Interpose %s to %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("to").replace("*", "-")
                            ),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
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
                        break;
                    }

                    case "SF_MSG":
                    {
                        char[] data = zfill(Integer.toBinaryString(Integer.parseInt(indvMsg.getString("data"), 16)), 8).toCharArray();
                        
                        String sdata = indvMsg.getString("data");
                        int idata = Integer.parseInt(sdata, 16);
                        String binary = Integer.toBinaryString(idata);
                        char[] data2 = zfill(binary, 8).toCharArray();

                        assert (data2 != data);

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

                                updateMap.put(address, dataBit);
                            }
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
                                updateMap.put(id, String.valueOf(binary.charAt(8*i+j)));
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

                m = updateAreaMap.getOrDefault("", new HashMap<>());
                m.put(e.getKey(), e.getValue());
                updateAreaMap.put("", m);
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
                if (ws != null && ws.isOpen() && ws instanceof EASMWebSocketImpl)
                    ((EASMWebSocketImpl) ws).send(messages);
        }
        saveTDData(updateMap);

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
            mapToSave.keySet().stream().forEach(key ->
            {
                File DataFile = new File(TDDataDir, key.substring(0, 2) + File.separator + key.substring(2).replace(":", "-") + ".td");
                
                if (key.length() != 6)
                    return;
                
                if (!DataFile.exists())
                {
                    try
                    {
                        DataFile.getParentFile().mkdirs();
                        DataFile.createNewFile();
                    }
                    catch (IOException ex) { NRODLight.printThrowable(ex, "TD"); }
                }
                                
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(DataFile, false)))
                {
                    bw.write(mapToSave.getOrDefault(key, ""));
                    bw.newLine();
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