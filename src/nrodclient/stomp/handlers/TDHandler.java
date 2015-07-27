package nrodclient.stomp.handlers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jsonparser.JSONParser;
import net.ser1.stomp.Listener;
import nrodclient.NRODClient;
import nrodclient.stomp.StompConnectionHandler;

public class TDHandler implements Listener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private long               lastMessageTime = 0;

    private static boolean isSaving = false;

    File TDDataFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + "TDData.json");

    private static Listener instance = null;
    private TDHandler()
    {
        Date logDate = new Date(System.currentTimeMillis());
        logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "TD" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = NRODClient.sdfDate.format(logDate);

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

        if (TDDataFile.exists())
        {
            String jsonString = "";
            try (BufferedReader br = new BufferedReader(new FileReader(TDDataFile)))
            {
                String line;
                while ((line = br.readLine()) != null)
                    jsonString += line;
            }
            catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

            Map<String, Object> json = (Map<String, Object>) JSONParser.parseJSON(jsonString).get("TDData");

            json.entrySet().stream().forEach(p ->
                DataMap.put(p.getKey(), (String) p.getValue())
            );
        }

        lastMessageTime = System.currentTimeMillis();
    }
    public static Listener getInstance()
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
        List<Map<String, Map<String, String>>> messageList = (List<Map<String, Map<String, String>>>) JSONParser.parseJSON("{\"TDMessage\":" + body + "}").get("TDMessage");
        final String areas = "LS SE SI CC CA EN WG SO SX";

        final Map<String, String> HistoryMap = new HashMap<>();

        for (Map<String, Map<String, String>> map : messageList)
        {
            try
            {
                String msgType = map.keySet().toArray(new String[0])[0];
                Map<String, String> indvMsg = map.get(msgType);

                if (!areas.contains(indvMsg.get("area_id")))
                    continue;

                indvMsg.put("address", indvMsg.get("area_id") + indvMsg.get("address"));

                switch (msgType.toUpperCase())
                {
                    case "CA_MSG":
                        HistoryMap.put(indvMsg.get("area_id") + indvMsg.get("to"), String.format("[%s] Move %s", NRODClient.sdfDateTimeShort.format(new Date(Long.parseLong(indvMsg.get("time")))), indvMsg.get("descr")));
                        DataMap.put(indvMsg.get("area_id") + indvMsg.get("from"), "");
                        DataMap.put(indvMsg.get("area_id") + indvMsg.get("to"),   indvMsg.get("descr"));

                        printTD(String.format("Step %s from %s to %s",
                                indvMsg.get("descr"),
                                indvMsg.get("area_id") + indvMsg.get("from"),
                                indvMsg.get("area_id") + indvMsg.get("to")),
                            false,
                            Long.parseLong(indvMsg.get("time")));
                        break;
                    case "CB_MSG":
                        HistoryMap.put(indvMsg.get("area_id") + indvMsg.get("from"), String.format("[%s] Cancel %s", NRODClient.sdfDateTimeShort.format(new Date(Long.parseLong(indvMsg.get("time")))), indvMsg.get("descr")));
                        DataMap.put(indvMsg.get("area_id") + indvMsg.get("from"), "");

                        printTD(String.format("Cancel %s from %s",
                                indvMsg.get("descr"),
                                indvMsg.get("area_id") + indvMsg.get("from")),
                            false,
                            Long.parseLong(indvMsg.get("time")));
                        break;
                    case "CC_MSG":
                        HistoryMap.put(indvMsg.get("area_id") + indvMsg.get("to"), String.format("[%s] Interpose %s", NRODClient.sdfDateTimeShort.format(new Date(Long.parseLong(indvMsg.get("time")))), indvMsg.get("descr")));
                        DataMap.put(indvMsg.get("area_id") + indvMsg.get("to"), indvMsg.get("decsr"));

                        printTD(String.format("Interpose %s to %s",
                                indvMsg.get("descr"),
                                indvMsg.get("area_id") + indvMsg.get("to")),
                            false,
                            Long.parseLong(indvMsg.get("time")));
                        break;

                    case "SF_MSG":
                    {
                        char[] data = toBinaryString(Integer.parseInt(indvMsg.get("data"), 16)).toCharArray();

                        for (int i = 0; i < data.length; i++)
                        {
                            String changedBit = Integer.toString(8 - i);
                            String address = indvMsg.get("address") + ":" + changedBit;

                            if (!DataMap.containsKey(address) || !DataMap.get(address).equals(String.valueOf(data[i])))
                            {
                                if (!(DataMap.containsKey(address) ? DataMap.get(address) : "0").equals(String.valueOf(data[i])))
                                    printTD(String.format("Change %s from %s to %s",
                                            indvMsg.get("address") + ":" + changedBit,
                                            DataMap.containsKey(address) ? DataMap.get(address) : "0",
                                            data[i]),
                                        false,
                                        Long.parseLong(indvMsg.get("time")));

                                HistoryMap.put(address, String.format("[%s] Change to %s", NRODClient.sdfDateTimeShort.format(new Date(Long.parseLong(indvMsg.get("time")))), data[i]));
                                DataMap.put(address, String.valueOf(data[i]));
                            }
                        }
                        break;
                    }

                    case "SG_MSG":
                    case "SH_MSG":
                    {
                        String addrStart = indvMsg.get("address").substring(0, 3);
                        String addrEnd = indvMsg.get("address").substring(3);

                        int data[] = { Integer.parseInt(indvMsg.get("data").substring(0, 2), 16),
                            Integer.parseInt(indvMsg.get("data").substring(2, 4), 16),
                            Integer.parseInt(indvMsg.get("data").substring(4, 6), 16),
                            Integer.parseInt(indvMsg.get("data").substring(6, 8), 16) };

                        String[] addresses = {indvMsg.get("address"),
                            addrStart + (addrEnd.equals("0") ? "1" : addrEnd.equals("4") ? "5" : addrEnd.equals("8") ? "9" : "D"),
                            addrStart + (addrEnd.equals("0") ? "2" : addrEnd.equals("4") ? "6" : addrEnd.equals("8") ? "A" : "E"),
                            addrStart + (addrEnd.equals("0") ? "3" : addrEnd.equals("4") ? "7" : addrEnd.equals("8") ? "B" : "F")};

                        for (int i = 0; i < data.length; i++)
                        {
                            DataMap.put(addresses[i], Integer.toString(data[i]));
                            HistoryMap.put(addresses[i], String.format("[%s] Change to %s", NRODClient.sdfDateTimeShort.format(new Date(Long.parseLong(indvMsg.get("time")))), data[i]));
                        }
                        break;
                    }
                }
            }
            catch (Exception e) { NRODClient.printThrowable(e, "TD"); }
        }

        SimpleDateFormat fileSDF = new SimpleDateFormat("YYYY" + File.separator + "MM" + File.separator + "dd");
        File baseFolder = new File(NRODClient.EASMStorageDir, "TDData" + File.separator + fileSDF.format(new Date(Long.parseLong(headers.get("timestamp")))) + File.separator);
        if (!baseFolder.exists())
            baseFolder.mkdirs();

        HistoryMap.entrySet().parallelStream().forEach(pairs ->
        {
            try
            {
                File historyFile = new File(baseFolder, pairs.getKey().replace(":", "-") + ".txt");
                if (!historyFile.exists())
                    historyFile.createNewFile();

                try (BufferedWriter bw = new BufferedWriter(new FileWriter(historyFile, true)))
                {
                    bw.write(pairs.getValue());
                    bw.write("\r\n");
                }
                catch (IOException e) { NRODClient.printThrowable(e, "TDDataSave-Write"); }
            }
            catch (IOException e) { NRODClient.printThrowable(e, "TDDataSave-General"); }
        });
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="Save File">
        if (!isSaving)
        {
            isSaving = true;

            StringBuilder sb = new StringBuilder().append("{\"TDData\":{");
            DataMap.entrySet().stream().filter(p -> p.getValue() != null).forEach(p -> sb.append("\r\n\"").append(p.getKey()).append("\":\"").append(p.getValue()).append("\","));

            if (sb.charAt(sb.length()-1) == ',')
                sb.deleteCharAt(sb.length()-1);
            sb.append("\r\n}}");

            try
            {
                if (TDDataFile.exists())
                    TDDataFile.delete();
                TDDataFile.getParentFile().mkdirs();
                TDDataFile.createNewFile();
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(TDDataFile)))
                {
                    bw.write(sb.toString());
                }
                catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

            }
            catch (IOException e) { NRODClient.printThrowable(e, "TD"); }

            isSaving = false;
        }
        //</editor-fold>

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public static String toBinaryString(int i)
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