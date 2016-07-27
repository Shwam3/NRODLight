package nrodclient.stomp.handlers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nrodclient.NRODClient;
import nrodclient.stomp.NRODListener;
import nrodclient.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TDHandler implements NRODListener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private long               lastMessageTime = 0;

    private static boolean isSaving = false;
    
    private final static List<String> areaFilters = Collections.unmodifiableList(Arrays.asList("AW","CA","CC","EN","K2","KX","LS","PB","SE","SI","SO","SX","UR","U2","U3","WG"));

    File TDDataFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + "TDData.json");

    private static NRODListener instance = null;
    private TDHandler()
    {
        Date logDate = new Date(System.currentTimeMillis());
        logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = NRODClient.sdfDate.format(logDate);

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODClient.printThrowable(e, "TD"); }
        
        File BaseDataFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + "TDBaseData.json");
        if (BaseDataFile.exists())
        {
            String jsonString = "";
            try (BufferedReader br = new BufferedReader(new FileReader(BaseDataFile)))
            {
                String line;
                while ((line = br.readLine()) != null)
                    jsonString += line;
            }
            catch (IOException e) { NRODClient.printThrowable(e, "TD"); }
            
            try
            {
                JSONObject json = new JSONObject(jsonString).getJSONObject("TDData");

                for (String key : json.keySet())
                    DataMap.put(key, json.getString(key));
            }
            catch (JSONException e) { NRODClient.printThrowable(e, "TD"); }
        }
        
        File TDDataDir = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + "TDData");
        
        for (File perAreaDir : TDDataDir.listFiles())
        {
            String area = perAreaDir.getName();
            if (area.length() != 2 || !perAreaDir.isDirectory())
                continue;
            
            for (File TDData : perAreaDir.listFiles())
            {
                String dataID = TDData.getName();
                
                if (dataID.endsWith(".new"))
                {
                    TDData.delete();
                    continue;
                }
                
                if (TDData.getName().endsWith(".td") && TDData.getName().length() == 7)
                {
                    String data = "";
                    try (BufferedReader br = new BufferedReader(new FileReader(TDData)))
                    {
                        data = br.readLine();
                    }
                    catch (IOException ex) { NRODClient.printThrowable(ex, "TD"); }
                    
                    DataMap.put(area + dataID.substring(0, 4).replace("-", ":"), data);
                }
            }
        }
        
        DataMap.keySet().stream().forEach(key ->
        {
            File DataFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD"
                + File.separator + "TDData" + File.separator + key.substring(0, 2) + File.separator + key.substring(2).replace(":", "-") + ".td");
            File DataFileNew = new File(DataFile.getAbsoluteFile() + ".new");

            if (key.length() != 6)
                return;
            
            try
            {
                DataFileNew.getParentFile().mkdirs();
                if (DataFileNew.exists())
                    DataFileNew.delete();
                DataFileNew.createNewFile();
            }
            catch (IOException ex) { NRODClient.printThrowable(ex, "TD"); }

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(DataFileNew, false)))
            {
                bw.write(DataMap.getOrDefault(key, ""));
                bw.newLine();
            }
            catch (IOException ex) { NRODClient.printThrowable(ex, "TD"); }

            if (DataFile.exists())
                DataFileNew.delete();
            DataFileNew.renameTo(DataFile);
        });

        lastMessageTime = System.currentTimeMillis();
    }
    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new TDHandler();

        return instance;
    }

    public static Map<String, String> DataMap = new HashMap<>();

    @Override
    public void message(Map<String, String> headers, String body)
    {
        StompConnectionHandler.printStompHeaders(headers);

        //<editor-fold defaultstate="collapsed" desc="TD Data">
        JSONArray messageList = new JSONArray(body);
        Map<String, String> updateMap = new HashMap<>();

        for (Object mapObj : messageList)
        {
            JSONObject map = (JSONObject) mapObj;
            try
            {
                String msgType = String.valueOf(map.keySet().toArray()[0]);
                JSONObject indvMsg = map.getJSONObject(msgType);
                if (!areaFilters.contains(indvMsg.getString("area_id")))
                    continue;

                String msgAddr = indvMsg.getString("area_id") + indvMsg.optString("address");

                switch (msgType.toUpperCase())
                {
                    case "CA_MSG":
                    {
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from"), "");
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to"), indvMsg.getString("descr"));
                        DataMap.put(indvMsg.getString("area_id") + indvMsg.getString("from"), "");
                        DataMap.put(indvMsg.getString("area_id") + indvMsg.getString("to"), indvMsg.getString("descr"));

                        printTD(String.format("Step %s from %s to %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("from"),
                                indvMsg.getString("area_id") + indvMsg.getString("to")),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        break;
                    }
                    
                    case "CB_MSG":
                    {
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("from"), "");
                        DataMap.put(indvMsg.getString("area_id") + indvMsg.getString("from"), "");

                        printTD(String.format("Cancel %s from %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("from")),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        break;
                    }
                    
                    case "CC_MSG":
                    {
                        updateMap.put(indvMsg.getString("area_id") + indvMsg.getString("to"), indvMsg.getString("descr"));
                        DataMap.put(indvMsg.getString("area_id") + indvMsg.getString("to"), indvMsg.getString("descr"));

                        printTD(String.format("Interpose %s to %s",
                                indvMsg.getString("descr"),
                                indvMsg.getString("area_id") + indvMsg.getString("to")),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        break;
                    }
                    
                    case "CT_MSG":
                    {
                        updateMap.put("XXHB" + indvMsg.getString("area_id"), indvMsg.getString("report_time"));
                        DataMap.put("XXHB" + indvMsg.getString("area_id"), indvMsg.getString("report_time"));

                        printTD(String.format("Heartbeat from %s at time %s",
                                indvMsg.getString("area_id"),
                                indvMsg.getString("report_time")),
                            false,
                            Long.parseLong(indvMsg.getString("time")));
                        break;
                    }

                    case "SF_MSG":
                    {
                        char[] data = paddedBinaryString(Integer.parseInt(indvMsg.getString("data"), 16)).toCharArray();

                        for (int i = 0; i < data.length; i++)
                        {
                            String address = msgAddr + ":" + Integer.toString(8 - i);

                            if (!DataMap.containsKey(address) || !DataMap.get(address).equals(String.valueOf(data[i])))
                            {
                                if (!DataMap.getOrDefault(address, "0").equals(String.valueOf(data[i])))
                                    printTD(String.format("Change %s from %s to %s",
                                            address,
                                            DataMap.getOrDefault(address, "0"),
                                            data[i]),
                                        false,
                                        Long.parseLong(indvMsg.getString("time")));
                                
                                DataMap.put(address, String.valueOf(data[i]));
                            }
                            updateMap.put(address, String.valueOf(data[i]));
                        }
                        break;
                    }

                    case "SG_MSG":
                    case "SH_MSG":
                    {
                        String addrStart = msgAddr.substring(0, 3);
                        String addrEnd = msgAddr.substring(3);
                        String dataStr = indvMsg.getString("data");

                        int data[] = {
                            Integer.parseInt(dataStr.substring(0, 2), 16),
                            Integer.parseInt(dataStr.substring(2, 4), 16),
                            Integer.parseInt(dataStr.substring(4, 6), 16),
                            Integer.parseInt(dataStr.substring(6, 8), 16)
                        };

                        String[] addresses = {
                            msgAddr,
                            addrStart + (addrEnd.equals("0") ? "1" : addrEnd.equals("4") ? "5" : addrEnd.equals("8") ? "9" : "D"),
                            addrStart + (addrEnd.equals("0") ? "2" : addrEnd.equals("4") ? "6" : addrEnd.equals("8") ? "A" : "E"),
                            addrStart + (addrEnd.equals("0") ? "3" : addrEnd.equals("4") ? "7" : addrEnd.equals("8") ? "B" : "F")
                        };

                        for (int i = 0; i < data.length; i++)
                        {
                            updateMap.put(addresses[i], Integer.toString(data[i]));
                            DataMap.put(addresses[i], Integer.toString(data[i]));
                        }
                        break;
                    }
                }
                NRODClient.guiData.updateData();
                
                JSONObject container = new JSONObject();
                JSONObject message = new JSONObject();
                message.put("type", "SEND_UPDATE");
                message.put("timestamp", System.currentTimeMillis());
                message.put("message", updateMap);
                container.put("Message", message);

                String messageStr = container.toString();
                NRODClient.webSocket.connections().stream()
                        .filter(c -> c != null)
                        .filter(c -> c.isOpen())
                        .forEach(c -> c.send(messageStr));
            }
            catch (Exception e) { NRODClient.printThrowable(e, "TD"); }
        }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="Save File">
        /*if (!isSaving)
        {
            isSaving = true;

            StringBuilder sb = new StringBuilder().append("{\"TDData\":{");
            DataMap.entrySet().stream().filter(p -> p.getValue() != null).forEach(p -> sb.append("\r\n\"").append(p.getKey()).append("\":\"").append(p.getValue()).append("\","));

            if (sb.charAt(sb.length()-1) == ',')
                sb.deleteCharAt(sb.length()-1);
            sb.append("\r\n}}");

            File TDDataFileNew = new File(TDDataFile.getAbsolutePath() + ".new");
            try
            {
                TDDataFileNew.getParentFile().mkdirs();
                TDDataFileNew.createNewFile();
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(TDDataFileNew)))
                {
                    bw.write(sb.toString());
                }
                catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

                if (TDDataFile.exists())
                    TDDataFile.delete();
                TDDataFileNew.renameTo(TDDataFile);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

            isSaving = false;
        }*/
        //</editor-fold>
        
        //<editor-fold defaultstate="collapsed" desc="Save Files">
        if (!updateMap.isEmpty())
        {
            updateMap.keySet().stream().forEach(key ->
            {
                File DataFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD"
                    + File.separator + "TDData" + File.separator + key.substring(0, 2) + File.separator + key.substring(2).replace(":", "-") + ".td");
                File DataFileNew = new File(DataFile.getAbsoluteFile() + ".new");
                
                if (key.length() != 6)
                    return;
                
                try
                {
                    DataFileNew.getParentFile().mkdirs();
                    if (DataFileNew.exists())
                        DataFileNew.delete();
                    DataFileNew.createNewFile();
                }
                catch (IOException ex) { NRODClient.printThrowable(ex, "TD"); }
                
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(DataFileNew, false)))
                {
                    bw.write(updateMap.getOrDefault(key, ""));
                    bw.newLine();
                }
                catch (IOException ex) { NRODClient.printThrowable(ex, "TD"); }
                
                if (DataFile.exists())
                    DataFile.delete();
                DataFileNew.renameTo(DataFile);
            });
        }
        //</editor-fold>

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public static String paddedBinaryString(int i)
    {
        return String.format("%" + ((int) Math.ceil(Integer.toBinaryString(i).length() / 8f) * 8) + "s", Integer.toBinaryString(i)).replace(" ", "0");
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 30000; }

    private void printTD(String message, boolean toErr, long timestamp)
    {
        if (NRODClient.verbose)
        {
            if (toErr)
                NRODClient.printErr("[TD] ".concat(message));
            else
                NRODClient.printOut("[TD] ".concat(message));
        }

        if (!lastLogDate.equals(NRODClient.sdfDate.format(new Date())))
        {
            logStream.close();

            Date logDate = new Date();
            lastLogDate = NRODClient.sdfDate.format(logDate);

            logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

            StringBuilder sb = new StringBuilder().append("{\"TDData\":{");
            DataMap.entrySet().stream().filter(p -> p.getValue() != null).forEach(p -> sb.append("\r\n\"").append(p.getKey()).append("\":\"").append(p.getValue()).append("\","));

            if (sb.charAt(sb.length()-1) == ',')
                sb.deleteCharAt(sb.length()-1);
            sb.append("\r\n}}");

            File fileReplaySave = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "ReplaySaves" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".json");
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileReplaySave)))
            {
                bw.write(sb.toString());
            }
            catch (IOException e) { NRODClient.printThrowable(e, "TD"); }
        }

        logStream.println("[".concat(NRODClient.sdfDateTime.format(new Date(timestamp))).concat("] ").concat(message));
    }
}