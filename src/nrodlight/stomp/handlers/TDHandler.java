package nrodlight.stomp.handlers;

import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.stepping.Stepping;
import nrodlight.ws.EASMWebSocketImpl;
import org.apache.activemq.command.ActiveMQMessage;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TDHandler implements MessageListener
{
    private static PrintWriter logStream;
    private static File logFile;
    private static String lastLogDate = "";

    private static MessageListener instance = null;
    private TDHandler()
    {
        lastLogDate = NRODLight.sdfDate.format(new Date());

        if (NRODLight.config.optBoolean("TDLogging", false))
            startLogging();

        saveTDData(DATA_MAP);
    }
    public static MessageListener getInstance()
    {
        if (instance == null)
            instance = new TDHandler();

        return instance;
    }

    public static final Map<String, String> DATA_MAP = new ConcurrentHashMap<>();

    public void handleMessage(final String body, final long timestamp)
    {
        JSONArray messageList = new JSONArray(body);
        Map<String, String> updateMap = new HashMap<>();
        long updateCount = 0L;
        long heartbeatCount = 0L;
        List<Long> timestamps = new ArrayList<>(messageList.length());
        Map<String, Long> tdCounts = new HashMap<>();

        for (Object mapObj : messageList)
        {
            JSONObject map = (JSONObject) mapObj;
            try
            {
                String msgType = null;
                if (map.has("CA_MSG"))
                    msgType = "CA_MSG";
                else if (map.has("CB_MSG"))
                    msgType = "CB_MSG";
                else if (map.has("CC_MSG"))
                    msgType = "CC_MSG";
                else if (map.has("CT_MSG"))
                    msgType = "CT_MSG";
                else if (map.has("SF_MSG"))
                    msgType = "SF_MSG";
                else if (map.has("SG_MSG"))
                    msgType = "SG_MSG";
                else if (map.has("SH_MSG"))
                    msgType = "SH_MSG";

                if (map.length() > 1)
                    NRODLight.printErr("[TD] Message has more than 1 message in an object: " + map);

                JSONObject indvMsg = map.getJSONObject(msgType);
                final long time = Long.parseLong(indvMsg.getString("time"));
                long updateCountEvent = 0L;

                if ("CA_MSG".equals(msgType)) // Step
                {
                    final String from = indvMsg.getString("area_id") + indvMsg.getString("from");
                    final String to = indvMsg.getString("area_id") + indvMsg.getString("to");
                    final String descr = indvMsg.getString("descr");
                    final String oldVal = updateMap.getOrDefault(to, DATA_MAP.getOrDefault(to, ""));

                    printTD(String.format("CA %s %s %s%s", descr, from, to, oldVal.isEmpty() ? "" : (" " + oldVal)), time);
                    updateMap.put(from, "");
                    updateMap.put(to, descr);
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CA %s %s\",\"descr\":\"%s\",\"time\":%s}", from, to, descr, time)), descr));
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CA * %s\",\"descr\":\"%s\",\"time\":%s}", to, descr, time)), descr));
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CA %s *\",\"descr\":\"%s\",\"time\":%s}", from, descr, time)), descr));

                    timestamps.add(time);
                    updateCountEvent++;
                }
                else if ("CB_MSG".equals(msgType)) // Cancel
                {
                    final String from = indvMsg.getString("area_id") + indvMsg.getString("from");
                    final String descr = indvMsg.getString("descr");

                    printTD(String.format("CB %s %s", descr, from), time);
                    updateMap.put(from, "");
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CB %s\",\"descr\":\"%s\",\"time\":%s}", from, descr, time))));

                    timestamps.add(time);
                    updateCountEvent++;
                }
                else if ("CC_MSG".equals(msgType)) // Interpose
                {
                    final String to = indvMsg.getString("area_id") + indvMsg.getString("to");
                    final String descr = indvMsg.getString("descr");
                    final String oldVal = updateMap.getOrDefault(to, DATA_MAP.getOrDefault(to, ""));

                    printTD(String.format("CC %s %s%s", descr, to, oldVal.isEmpty() ? "" : (" " + oldVal)), time);
                    updateMap.put(to, descr);
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CC %s\",\"descr\":\"%s\",\"oldVal\":\"%s\",\"time\":%s}", to, descr, oldVal, time)), descr));

                    timestamps.add(time);
                    updateCountEvent++;
                }
                else if ("CT_MSG".equals(msgType))
                {
                    final String area = indvMsg.getString("area_id");
                    final String address = "XXHB" + area;
                    final String report = indvMsg.getString("report_time");
                    final String oldVal = updateMap.getOrDefault(address, DATA_MAP.getOrDefault(address, ""));

                    printTD(String.format("CT %s %s", area, report), time);
                    updateMap.put(address, report);
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CC %s\",\"descr\":\"%s\",\"oldVal\":\"%s\",\"time\":%s}", address, report, oldVal, time)), report));
                    updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"CT %s\",\"descr\":\"%s\",\"time\":%s}", area, report, time))));

                    timestamps.add(time);
                    updateCountEvent++;
                    heartbeatCount++;
                }
                else if ("SF_MSG".equals(msgType))
                {
                    final int data = Integer.parseInt(indvMsg.getString("data"), 16);
                    timestamps.add(time);

                    for (int i = 0; i < 8; i++)
                    {
                        final String address = indvMsg.getString("area_id") + indvMsg.optString("address") + ":" + (i + 1);
                        final String dataBit = ((data >> i) & 1) == 1 ? "1" : "0";
                        final String oldVal = updateMap.getOrDefault(address, DATA_MAP.get(address));

                        if (!DATA_MAP.containsKey(address) || !dataBit.equals(oldVal))
                        {
                            printTD(String.format("SF %s %s %s", address, oldVal == null ? "0" : oldVal, dataBit), time);
                            updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"%s %s\",\"time\":%s}", "1".equals(dataBit) ? "SET" : "UNSET", address, time))));
                            updateCountEvent++;
                        }
                    }
                }
                else if ("SG_MSG".equals(msgType) || "SH_MSG".equals(msgType))
                {
                    final int start = Integer.parseInt(indvMsg.getString("address"), 16);
                    timestamps.add(time);

                    for (int i = 0; i < 4; i++)
                    {
                        final int bitField = Integer.parseInt(indvMsg.getString("data").substring(i * 2, i * 2 + 2), 16);
                        final String byteAddress = indvMsg.getString("area_id") + (start + i < 0x10 ? "0" : "") + Integer.toHexString(start + i).toUpperCase() + ":";
                        for (int j = 0; j < 8; j++)
                        {
                            final String address = byteAddress + (j + 1);
                            final String dataBit = ((bitField >> j) & 1) == 1 ? "1" : "0";
                            final String oldVal = updateMap.getOrDefault(address, DATA_MAP.get(address));

                            updateMap.put(address, dataBit);
                            if (!DATA_MAP.containsKey(address) || !dataBit.equals(oldVal))
                            {
                                printTD(String.format("%s %s %s %s", indvMsg.get("msg_type"), address, oldVal == null ? "0" : oldVal, dataBit), time);
                                updateMap.putAll(Stepping.processEvent(new JSONObject(String.format("{\"event\":\"%s %s\",\"time\":%s}", "1".equals(dataBit) ? "SET" : "UNSET", address, time))));
                                updateCountEvent++;
                            }
                        }
                    }
                }

                tdCounts.put(indvMsg.getString("area_id"), tdCounts.getOrDefault(indvMsg.getString("area_id"), 0L) + updateCountEvent);
                updateCount += updateCountEvent;
            }
            catch (Exception e) { NRODLight.printThrowable(e, "TD"); }
        }

        DATA_MAP.putAll(updateMap);
        updateClients(updateMap);
        saveTDData(updateMap);

        RateMonitor.getInstance().onTDMessage(
                (System.currentTimeMillis() - timestamp) / 1000d,
                timestamps.stream().mapToLong(e -> System.currentTimeMillis() - e).average().orElse(0) / 1000d,
                updateCount, heartbeatCount, tdCounts);
    }

    public static void updateClientsAndSave(Map<String, String> updateMap)
    {
        Map<String, String> saveMap = new HashMap<>(updateMap);
        for (Iterator<Map.Entry<String, String>> iter = updateMap.entrySet().iterator(); iter.hasNext(); )
        {
            Map.Entry<String, String> pair = iter.next();
            if (pair.getValue() == null)
            {
                String key = pair.getKey();
                DATA_MAP.remove(key);
                saveMap.put(key, key.charAt(4) == ':' ? "0" : "");
                iter.remove(); //updateMap.remove(pair.getKey());
            }
        }
        if (!updateMap.isEmpty())
        {
            DATA_MAP.putAll(updateMap);
            updateClients(updateMap);
        }
        if (!saveMap.isEmpty())
            saveTDData(saveMap);
    }

    private static void updateClients(Map<String, String> updateMap)
    {
        if (NRODLight.webSocket != null)
        {
            Map<String, Map<String, String>> updateAreaMap = new HashMap<>();
            updateMap.forEach((key, value) ->
            {
                String area = key.substring(0, 2);
                Map<String, String> m = updateAreaMap.getOrDefault(area, new HashMap<>());
                m.put(key, value);
                updateAreaMap.put(area, m);
            });

            Map<String, String> messages = new HashMap<>();
            updateAreaMap.forEach((key, value) ->
            {
                JSONObject container = new JSONObject();
                JSONObject message = new JSONObject();
                message.put("type", "SEND_UPDATE");
                message.put("messageID", "%nextid%");
                message.put("timestamp", System.currentTimeMillis());
                message.put("message", value);
                if (!key.isEmpty())
                    message.put("area", key);
                container.put("Message", message);

                messages.put(key, container.toString());
            });
            NRODLight.webSocket.getConnections().stream()
                    .filter(Objects::nonNull)
                    .filter(WebSocket::isOpen)
                    .filter(c -> c instanceof EASMWebSocketImpl)
                    .forEach(ws ->
                    {
                        try { ((EASMWebSocketImpl)ws).send(messages); }
                        catch (WebsocketNotConnectedException ignored) {}
                    });
        }
    }

    private static void saveTDData(final Map<String, String> mapToSave)
    {
        final File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
        if (!mapToSave.isEmpty())
        {
            if (!TDDataDir.exists())
                TDDataDir.mkdirs();

            final JSONObject cClObj = new JSONObject();
            final JSONObject sClObj = new JSONObject();

            mapToSave.forEach((k, v) -> ("0".equals(v) || "1".equals(v) ? sClObj : cClObj).put(k.substring(0, 2), new JSONObject()));

            DATA_MAP.forEach((k, v) ->
            {
                String area = k.substring(0, 2);
                if ("0".equals(v) || "1".equals(v))
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
                try(BufferedWriter bw = new BufferedWriter(new FileWriter(new File(TDDataDir, k+".s.td"))))
                {
                    sClObj.getJSONObject(k).write(bw);
                }
                catch (IOException ex) { NRODLight.printThrowable(ex, "TD"); }
            });
            cClObj.keys().forEachRemaining(k ->
            {
                try(BufferedWriter bw = new BufferedWriter(new FileWriter(new File(TDDataDir, k+".c.td"))))
                {
                    cClObj.getJSONObject(k).write(bw);
                }
                catch (IOException ex) { NRODLight.printThrowable(ex, "TD"); }
            });
        }
    }

    public static void printTD(String message, long timestamp)
    {
        if (NRODLight.verbose)
            NRODLight.printOut("[TD] [" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + message);

        logRotate();

        if (logStream != null)
            logStream.println("[" + NRODLight.sdfDateTime.format(new Date(timestamp)) + "] " + message);
    }

    private static void logRotate()
    {
        String newDate = NRODLight.sdfDate.format(new Date());
        if (!lastLogDate.equals(newDate))
        {
            if (logStream != null)
                logStream.close();

            lastLogDate = newDate;

            if (NRODLight.config.optBoolean("TDLogging", false))
            {
                logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TD" + File.separator + newDate.replace("/", "-") + ".log");
                logFile.getParentFile().mkdirs();
                try
                {
                    logFile.createNewFile();
                    logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
                }
                catch (IOException e) {NRODLight.printThrowable(e, "TD");}
            }

            File fileReplaySave = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "ReplaySaves" + File.separator + newDate.replace("/", "-") + ".json");
            if (!fileReplaySave.exists())
            {
                fileReplaySave.getParentFile().mkdirs();
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileReplaySave)))
                {
                    new JSONObject().put("TDData", DATA_MAP).write(bw);
                }
                catch (IOException e) { NRODLight.printThrowable(e, "TD"); }
            }
        }
    }

    public static void startLogging()
    {
        logRotate();

        if (logStream == null)
        {
            logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "TD" + File.separator + lastLogDate.replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();
            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) {NRODLight.printThrowable(e, "TD");}
        }
    }

    public static void stopLogging()
    {
        PrintWriter ls = logStream;
        logStream = null;

        if (ls != null)
            ls.close();
    }

    /*
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args)
    {
        NRODLight.EASM_STORAGE_DIR = new File("D:\\Shwam\\Documents\\GitHub\\NRODLight\\sigmaps");
        String logDate = NRODLight.sdfDate.format(new Date());
        NRODLight.logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "NRODLight" + File.separator + logDate.replace("/", "-") + ".log");
        NRODLight.logFile.getParentFile().mkdirs();
        NRODLight.lastLogDate = logDate;

        try
        {
            NRODLight.logStream = new PrintStream(new FileOutputStream(NRODLight.logFile, NRODLight.logFile.length() > 0), true);
            System.setOut(new DoublePrintStream(System.out, NRODLight.logStream));
            System.setErr(new DoublePrintStream(System.err, NRODLight.logStream));
        }
        catch (FileNotFoundException e) { NRODLight.printErr("Could not create log file"); NRODLight.printThrowable(e, "Startup"); }

        TDHandler tdh = (TDHandler) getInstance();

        Map<String, String> headers = new HashMap(new JSONObject("{}").toMap());
        TDHandler.startLogging();
        tdh.message(
                 headers,
                "[{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X7\",\"time\":\"1657126531000\",\"address\":\"24\",\"data\":\"06\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"07\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"WY\",\"time\":\"1657126530000\",\"address\":\"1E\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X0\",\"time\":\"1657126531000\",\"address\":\"66\",\"data\":\"0F\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"SV\",\"time\":\"1657126531000\",\"address\":\"08\",\"data\":\"EB\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"ZB\",\"time\":\"1657126531000\",\"address\":\"07\",\"data\":\"08\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EA\",\"time\":\"1657126531000\",\"from\":\"P601\",\"to\":\"P609\",\"descr\":\"2Y11\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"NK\",\"time\":\"1657126531000\",\"address\":\"50\",\"data\":\"1F\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EB\",\"time\":\"1657126531000\",\"from\":\"P601\",\"to\":\"P609\",\"descr\":\"2Y11\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R2\",\"time\":\"1657126531000\",\"address\":\"11\",\"data\":\"7F\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"ZG\",\"time\":\"1657126531000\",\"from\":\"EW24\",\"to\":\"EW22\",\"descr\":\"1P60\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"1B\",\"data\":\"F8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"U3\",\"time\":\"1657126531000\",\"address\":\"29\",\"data\":\"C8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"C2\",\"time\":\"1657126531000\",\"address\":\"7E\",\"data\":\"0A\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"EK\",\"time\":\"1657126531000\",\"address\":\"50\",\"data\":\"F6\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X0\",\"time\":\"1657126531000\",\"address\":\"6B\",\"data\":\"C0\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"M1\",\"time\":\"1657126531000\",\"from\":\"0044\",\"to\":\"0010\",\"descr\":\"1L17\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q4\",\"time\":\"1657126531000\",\"address\":\"21\",\"data\":\"20\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X3\",\"time\":\"1657126531000\",\"address\":\"4A\",\"data\":\"40\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EB\",\"time\":\"1657126531000\",\"from\":\"S684\",\"to\":\"S686\",\"descr\":\"1D57\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q0\",\"time\":\"1657126531000\",\"address\":\"0A\",\"data\":\"A0\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"T2\",\"time\":\"1657126531000\",\"address\":\"4A\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"1B\",\"data\":\"F8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"DA\",\"time\":\"1657126531000\",\"address\":\"22\",\"data\":\"02\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"M3\",\"time\":\"1657126531000\",\"address\":\"0B\",\"data\":\"B0\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q3\",\"time\":\"1657126531000\",\"address\":\"58\",\"data\":\"BC\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"YO\",\"time\":\"1657126531000\",\"address\":\"06\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"D9\",\"time\":\"1657126531000\",\"address\":\"33\",\"data\":\"C6\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Y9\",\"time\":\"1657126531000\",\"address\":\"10\",\"data\":\"10\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"M4\",\"time\":\"1657126531000\",\"address\":\"08\",\"data\":\"59\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"Q0\",\"time\":\"1657126531000\",\"from\":\"0142\",\"to\":\"146B\",\"descr\":\"9U49\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"Q2\",\"time\":\"1657126531000\",\"from\":\"0491\",\"to\":\"0495\",\"descr\":\"2W06\"}}]"
        );
        TDHandler.stopLogging();
        tdh.message(
                 headers,
                "[{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X7\",\"time\":\"1657126531000\",\"address\":\"24\",\"data\":\"06\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"07\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"WY\",\"time\":\"1657126530000\",\"address\":\"1E\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X0\",\"time\":\"1657126531000\",\"address\":\"66\",\"data\":\"0F\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"SV\",\"time\":\"1657126531000\",\"address\":\"08\",\"data\":\"EB\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"ZB\",\"time\":\"1657126531000\",\"address\":\"07\",\"data\":\"08\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EA\",\"time\":\"1657126531000\",\"from\":\"P601\",\"to\":\"P609\",\"descr\":\"2Y11\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"NK\",\"time\":\"1657126531000\",\"address\":\"50\",\"data\":\"1F\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EB\",\"time\":\"1657126531000\",\"from\":\"P601\",\"to\":\"P609\",\"descr\":\"2Y11\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R2\",\"time\":\"1657126531000\",\"address\":\"11\",\"data\":\"7F\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"ZG\",\"time\":\"1657126531000\",\"from\":\"EW24\",\"to\":\"EW22\",\"descr\":\"1P60\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"1B\",\"data\":\"F8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"U3\",\"time\":\"1657126531000\",\"address\":\"29\",\"data\":\"C8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"C2\",\"time\":\"1657126531000\",\"address\":\"7E\",\"data\":\"0A\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"EK\",\"time\":\"1657126531000\",\"address\":\"50\",\"data\":\"F6\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X0\",\"time\":\"1657126531000\",\"address\":\"6B\",\"data\":\"C0\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"M1\",\"time\":\"1657126531000\",\"from\":\"0044\",\"to\":\"0010\",\"descr\":\"1L17\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q4\",\"time\":\"1657126531000\",\"address\":\"21\",\"data\":\"20\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X3\",\"time\":\"1657126531000\",\"address\":\"4A\",\"data\":\"40\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EB\",\"time\":\"1657126531000\",\"from\":\"S684\",\"to\":\"S686\",\"descr\":\"1D57\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q0\",\"time\":\"1657126531000\",\"address\":\"0A\",\"data\":\"A0\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"T2\",\"time\":\"1657126531000\",\"address\":\"4A\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"1B\",\"data\":\"F8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"DA\",\"time\":\"1657126531000\",\"address\":\"22\",\"data\":\"02\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"M3\",\"time\":\"1657126531000\",\"address\":\"0B\",\"data\":\"B0\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q3\",\"time\":\"1657126531000\",\"address\":\"58\",\"data\":\"BC\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"YO\",\"time\":\"1657126531000\",\"address\":\"06\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"D9\",\"time\":\"1657126531000\",\"address\":\"33\",\"data\":\"C6\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Y9\",\"time\":\"1657126531000\",\"address\":\"10\",\"data\":\"10\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"M4\",\"time\":\"1657126531000\",\"address\":\"08\",\"data\":\"59\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"Q0\",\"time\":\"1657126531000\",\"from\":\"0142\",\"to\":\"146B\",\"descr\":\"9U49\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"Q2\",\"time\":\"1657126531000\",\"from\":\"0491\",\"to\":\"0495\",\"descr\":\"2W06\"}}]"
        );
        TDHandler.startLogging();
        tdh.message(
                 headers,
                "[{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X7\",\"time\":\"1657126531000\",\"address\":\"24\",\"data\":\"06\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"07\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"WY\",\"time\":\"1657126530000\",\"address\":\"1E\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X0\",\"time\":\"1657126531000\",\"address\":\"66\",\"data\":\"0F\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"SV\",\"time\":\"1657126531000\",\"address\":\"08\",\"data\":\"EB\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"ZB\",\"time\":\"1657126531000\",\"address\":\"07\",\"data\":\"08\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EA\",\"time\":\"1657126531000\",\"from\":\"P601\",\"to\":\"P609\",\"descr\":\"2Y11\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"NK\",\"time\":\"1657126531000\",\"address\":\"50\",\"data\":\"1F\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EB\",\"time\":\"1657126531000\",\"from\":\"P601\",\"to\":\"P609\",\"descr\":\"2Y11\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R2\",\"time\":\"1657126531000\",\"address\":\"11\",\"data\":\"7F\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"ZG\",\"time\":\"1657126531000\",\"from\":\"EW24\",\"to\":\"EW22\",\"descr\":\"1P60\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"1B\",\"data\":\"F8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"U3\",\"time\":\"1657126531000\",\"address\":\"29\",\"data\":\"C8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"C2\",\"time\":\"1657126531000\",\"address\":\"7E\",\"data\":\"0A\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"EK\",\"time\":\"1657126531000\",\"address\":\"50\",\"data\":\"F6\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X0\",\"time\":\"1657126531000\",\"address\":\"6B\",\"data\":\"C0\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"M1\",\"time\":\"1657126531000\",\"from\":\"0044\",\"to\":\"0010\",\"descr\":\"1L17\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q4\",\"time\":\"1657126531000\",\"address\":\"21\",\"data\":\"20\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"X3\",\"time\":\"1657126531000\",\"address\":\"4A\",\"data\":\"40\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"EB\",\"time\":\"1657126531000\",\"from\":\"S684\",\"to\":\"S686\",\"descr\":\"1D57\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q0\",\"time\":\"1657126531000\",\"address\":\"0A\",\"data\":\"A0\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"T2\",\"time\":\"1657126531000\",\"address\":\"4A\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"R3\",\"time\":\"1657126531000\",\"address\":\"1B\",\"data\":\"F8\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"DA\",\"time\":\"1657126531000\",\"address\":\"22\",\"data\":\"02\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"M3\",\"time\":\"1657126531000\",\"address\":\"0B\",\"data\":\"B0\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Q3\",\"time\":\"1657126531000\",\"address\":\"58\",\"data\":\"BC\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"YO\",\"time\":\"1657126531000\",\"address\":\"06\",\"data\":\"00\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"D9\",\"time\":\"1657126531000\",\"address\":\"33\",\"data\":\"C6\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"Y9\",\"time\":\"1657126531000\",\"address\":\"10\",\"data\":\"10\"}},{\"SF_MSG\":{\"msg_type\":\"SF\",\"area_id\":\"M4\",\"time\":\"1657126531000\",\"address\":\"08\",\"data\":\"59\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"Q0\",\"time\":\"1657126531000\",\"from\":\"0142\",\"to\":\"146B\",\"descr\":\"9U49\"}},{\"CA_MSG\":{\"msg_type\":\"CA\",\"area_id\":\"Q2\",\"time\":\"1657126531000\",\"from\":\"0491\",\"to\":\"0495\",\"descr\":\"2W06\"}}]"
        );
    }
    */

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
        catch (JMSException ex) { NRODLight.printThrowable(ex, "TD"); }
    }
}
