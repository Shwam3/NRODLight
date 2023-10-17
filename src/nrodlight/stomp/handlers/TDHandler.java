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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TDHandler implements MessageListener
{
    private static PrintWriter logStream;
    private static File logFile;
    private static String lastLogDate = "";

    private static final ReentrantLock saveFileLock = new ReentrantLock();

    private static final Set<String> modifiedAreasSinceSave = new HashSet<>();

    private static MessageListener instance = null;
    private TDHandler()
    {
        lastLogDate = NRODLight.sdfDate.format(new Date());

        if (NRODLight.config.optBoolean("TDLogging", false))
            startLogging();

        saveTDDataFull(true);
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
        Set<String> modifiedAreas = new HashSet<>();
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
                    modifiedAreas.add(indvMsg.getString("area_id"));

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
                    modifiedAreas.add(indvMsg.getString("area_id"));

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
                    modifiedAreas.add(indvMsg.getString("area_id"));

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
                    modifiedAreas.add("XX");

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

                        updateMap.put(address, dataBit);
                        modifiedAreas.add(indvMsg.getString("area_id"));
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
                            modifiedAreas.add(indvMsg.getString("area_id"));
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

        updateClients(updateMap);

        saveFileLock.lock();
        try {
            DATA_MAP.putAll(updateMap);
            saveTDDataSmall(updateMap, modifiedAreas);
        } finally {
            saveFileLock.unlock();
        }

        RateMonitor.getInstance().onTDMessage(
                (System.currentTimeMillis() - timestamp) / 1000d,
                timestamps.stream().mapToLong(e -> System.currentTimeMillis() - e).average().orElse(0) / 1000d,
                updateCount, heartbeatCount, tdCounts);
    }

    public static void updateClientsAndSave(Map<String, String> updateMap)
    {
        Set<String> modifiedAreas = new HashSet<>();
        saveFileLock.lock();
        try {
            for (Iterator<Map.Entry<String, String>> iter = updateMap.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<String, String> pair = iter.next();
                if (pair.getValue() == null) {
                    String key = pair.getKey();
                    DATA_MAP.remove(key);
                    iter.remove();
                }
                modifiedAreas.add(pair.getKey().substring(0, 2));
            }
            if (!updateMap.isEmpty()) {
                DATA_MAP.putAll(updateMap);
                updateClients(updateMap);
            }
            ((TDHandler) getInstance()).saveTDDataSmall(updateMap, modifiedAreas);
        } finally {
            saveFileLock.unlock();
        }
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

    public void saveTDDataFull(final boolean saveAll)
    {
        if (!modifiedAreasSinceSave.isEmpty() || saveAll)
        {
            saveFileLock.lock();

            try {
                ConcurrentSkipListMap<String, String> copyDataMap = new ConcurrentSkipListMap<>(
                        Comparator.<String, String>comparing(a -> a.substring(0, 2)) // Sort by area, then C/S class, then ID
                                .thenComparing(a -> a.charAt(4) == ':')
                                .thenComparing(a -> a.substring(2, 6))
                );
                copyDataMap.putAll(DATA_MAP);
                Set<String> copyModifiedAreas = new HashSet<>(modifiedAreasSinceSave);
                long time = System.currentTimeMillis();
                modifiedAreasSinceSave.clear();

                saveFileLock.unlock();

                final File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
                if (!TDDataDir.exists())
                    TDDataDir.mkdirs();

                String lastArea = "";
                StringBuilder lines = new StringBuilder();
                AtomicInteger count = new AtomicInteger();
                for (Map.Entry<String, String> entry : copyDataMap.entrySet()) {
                    String id = entry.getKey();
                    String val = entry.getValue();
                    String area = id.substring(0, 2);

                    if (saveAll || copyModifiedAreas.contains(area)) {
                        if (lastArea.isEmpty()) {
                            lastArea = area;
                        }

                        if (!area.equals(lastArea)) {
                            try {
                                lines.insert(0, '\n').insert(0, count.getAndSet(0));
                                File newFile = new File(TDDataDir, lastArea + ".td.new");
                                Files.writeString(newFile.toPath(), lines.toString(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                                Files.move(newFile.toPath(), new File(TDDataDir, lastArea + ".td").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException ex) {
                                NRODLight.printErr("[TD] Exception saving " + lastArea + ": " + ex);
                            }
                            lines.setLength(0);
                        }
                        lines.append(id).append('\t').append(val).append('\n');
                        count.incrementAndGet();
                    }
                    lastArea = area;
                }

                if (count.get() > 0 && (saveAll || copyModifiedAreas.contains(lastArea))) {
                    try {
                        lines.insert(0, '\n').insert(0, count.getAndSet(0));
                        File newFile = new File(TDDataDir, lastArea + ".td.new");
                        Files.writeString(newFile.toPath(), lines.toString(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                        Files.move(newFile.toPath(), new File(TDDataDir, lastArea + ".td").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        NRODLight.printErr("[TD] Exception saving " + lastArea + ": " + ex);
                    }
                }

                final File[] TDDataFiles = TDDataDir.listFiles();
                Pattern p = Pattern.compile("([0-9]+)\\.td");
                if (TDDataFiles != null) {
                    for (File file : TDDataFiles) {
                         if (file.exists() && file.isFile()) {
                             Matcher m = p.matcher(file.getName());
                             if (m.matches() && Long.parseLong(m.group(1)) < time) {
                                 if (!file.delete() && file.exists()) {
                                     NRODLight.printErr("[TD] Couldn't delete " + file.getName());
                                 }
                             }
                         }
                    }
                }
            } catch (Exception ex) {
                NRODLight.printThrowable(ex, "TD");
            } finally {
                if (saveFileLock.isHeldByCurrentThread()) {
                    saveFileLock.unlock();
                }
            }
        }
    }

    private void saveTDDataSmall(final Map<String, String> updateMap, Set<String> modifiedAreas)
    {
        if (updateMap != null && !updateMap.isEmpty())
        {
            saveFileLock.lock();
            modifiedAreasSinceSave.addAll(modifiedAreas);
            long time = System.currentTimeMillis();

            try {
                final File TDDataDir = new File(NRODLight.EASM_STORAGE_DIR, "TDData");
                if (!TDDataDir.exists())
                    TDDataDir.mkdirs();

                StringBuilder lines = new StringBuilder().append(updateMap.size()).append('\n');
                for (Map.Entry<String, String> entry : updateMap.entrySet()) {
                    lines.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
                }
                File newFile = new File(TDDataDir, time + ".td");
                Files.writeString(newFile.toPath(), lines.toString(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            } catch (IOException ex) {
                NRODLight.printErr("[TD] Exception saving " + time + ".td: " + ex);
            } finally {
                if (saveFileLock.getHoldCount() > 1) {
                    NRODLight.printOut("[TD] saveFileLock > 0 on release");
                }
                saveFileLock.unlock();
            }
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
                saveFileLock.lock();
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileReplaySave))) {
                    new JSONObject().put("TDData", DATA_MAP).write(bw);
                } catch (IOException e) {
                    NRODLight.printThrowable(e, "TD");
                } finally {
                    saveFileLock.unlock();
                }
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
