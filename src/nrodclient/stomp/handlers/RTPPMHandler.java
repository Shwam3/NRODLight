package nrodclient.stomp.handlers;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jsonparser.JSONParser;
import net.ser1.stomp.Listener;
import nrodclient.NRODClient;
import nrodclient.stomp.StompConnectionHandler;

public class RTPPMHandler implements Listener
{
    private static PrintWriter logStream;
    private static File        logFile;
  //private static String      lastLogDate = "";

    private String lastMessage = null;
    private long lastMessageTime = 0;
    private Date purgeDateTime = null;
    public final static Map<String, Operator> operators = new HashMap<>();

    private static Listener instance = null;
    public static Listener getInstance()
    {
        if (instance == null)
            instance = new RTPPMHandler();

        return instance;
    }

    private RTPPMHandler()
    {
        lastMessageTime = System.currentTimeMillis();

        Date currDate = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(currDate);
        cal.set(Calendar.HOUR_OF_DAY, 2);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTime().before(currDate))
            cal.add(Calendar.DAY_OF_YEAR, 1);
        purgeDateTime = cal.getTime();

        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.add(Calendar.DATE, -1);
        logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(cal.getTime()).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();

        try
        {
            logFile.createNewFile();
            logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }

        File saveFile = new File(NRODClient.EASMStorageDir, "RTPPM.save");
        if (saveFile.exists())
        {
            try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(saveFile))))
            {
                Object obj = ois.readObject();

                readData((Map<String, Map<String, Object>>) obj);
            }
            catch (ClassNotFoundException e) {}
            catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
        }

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        int mins = calendar.get(Calendar.MINUTE);
        calendar.add(Calendar.MINUTE, 10 - (mins % 10));
        calendar.set(Calendar.SECOND, 30);
        calendar.set(Calendar.MILLISECOND, 500);

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> { uploadHTML(); }, calendar.getTimeInMillis() - System.currentTimeMillis(), 600000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void message(Map<String, String> headers, final String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        Map<String, Object> ppmMap = (Map<String, Object>) JSONParser.parseJSON(message).get("RTPPMDataMsgV1");
        String incidentMessages = String.valueOf(((Map) ((Map) ppmMap.get("RTPPMData")).get("NationalPage")).get("WebMsgOfMoment")).trim();

        Date timestamp = new Date(Long.parseLong(String.valueOf(ppmMap.get("timestamp"))));

        operators.clear();

        if (timestamp.after(purgeDateTime))
        {
            logFinalPPMs();

            Calendar purgeDateTimeCal = Calendar.getInstance();
            purgeDateTimeCal.setTime(timestamp);
            purgeDateTimeCal.set(Calendar.HOUR_OF_DAY, 2);
            purgeDateTimeCal.set(Calendar.MINUTE, 0);
            purgeDateTimeCal.set(Calendar.SECOND, 0);
            purgeDateTimeCal.set(Calendar.MILLISECOND, 0);
            if (purgeDateTimeCal.getTime().before(timestamp))
                purgeDateTimeCal.add(Calendar.DAY_OF_YEAR, 1);
            purgeDateTime = purgeDateTimeCal.getTime();

            operators.clear();
        }

        try
        {
            List<Map<String, Object>> operatorsPPM = (List<Map<String, Object>>) ((Map) ppmMap.get("RTPPMData")).get("OperatorPage");
            for (Map<String, Object> map : operatorsPPM)
            {
                String operatorName = String.valueOf(((Map) map.get("Operator")).get("name"));
                Operator operator;
                if (operators.containsKey(operatorName))
                    operator = operators.get(operatorName);
                else
                {
                    operator = new Operator(operatorName, Integer.parseInt(String.valueOf(((Map) map.get("Operator")).get("code"))));
                    operators.put(operatorName, operator);
                }

                operator.putService("Total", (Map<String, Object>) map.get("Operator"));

                if (map.get("OprServiceGrp") instanceof Map)
                    operator.putService(String.valueOf(((Map) map.get("OprServiceGrp")).get("name")), (Map<String, Object>) map.get("OprServiceGrp"));
                else if (map.get("OprServiceGrp") instanceof List)
                    for (Object serviceObj : (List) map.get("OprServiceGrp"))
                        operator.putService(String.valueOf(((Map) serviceObj).get("name")), ((Map) serviceObj));
            }
        }
        catch (Exception e) { NRODClient.printThrowable(e, "RTPPM"); }

        Map<String, Map<String, Object>> ppmData = new HashMap<>(getPPMData());

        //<editor-fold defaultstate="collapsed" desc="Print out">
        List<String> operatorNames = new ArrayList<>(operators.keySet());
        Collections.sort(operatorNames, String.CASE_INSENSITIVE_ORDER);
        operatorNames.stream().forEach((operator) -> { operators.get(operator).printPrettyString(); });

        printRTPPM("Incident Messages:", false);
        for (String incidentMessage : incidentMessages.split("\\n"))
            printRTPPM("  " + incidentMessage.trim(), false);
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="Obj. Out Stream file">
        try
        {
            File saveFile = new File(NRODClient.EASMStorageDir, "RTPPM.save");
            if (!saveFile.exists())
            {
                saveFile.getParentFile().mkdirs();
                saveFile.createNewFile();
            }

            try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(saveFile))))
            {
                oos.writeObject(ppmData);
            }

            printRTPPM("Saved oos file (" + (saveFile.length() / 1024L) + "kb)", false);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="JSON file">
        try
        {
            File saveFile = new File(NRODClient.EASMStorageDir, "RTPPM.json");
            if (!saveFile.exists())
            {
                saveFile.getParentFile().mkdirs();
                saveFile.createNewFile();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\"RTPPMData\":{");

            for (Map.Entry<String, Map<String, Object>> operator : ppmData.entrySet())
            {
                StringBuilder sb2 = new StringBuilder();
                for (Map.Entry<String, Object> operatorInfo : operator.getValue().entrySet())
                {
                    sb2.append('"').append(operatorInfo.getKey()).append("\":");

                    if (operatorInfo.getValue() instanceof Map)
                    {
                        sb2.append('{');
                        for (Map.Entry<String, Map<String, Object>> serviceRoute : ((Map<String, Map<String, Object>>) operatorInfo.getValue()).entrySet())
                        {
                            sb2.append('"').append(serviceRoute.getKey()).append('"').append(':').append('{');
                            for (Map.Entry<String, Object> breakdown : serviceRoute.getValue().entrySet())
                            {
                                sb2.append('"').append(breakdown.getKey()).append('"').append(':');
                                if (breakdown.getValue() instanceof Integer)
                                    sb2.append(breakdown.getValue());
                                else if (breakdown.getValue() instanceof Map)
                                {
                                    sb2.append('{');
                                    for (Map.Entry<String, String> ppmDisplayInfo : ((Map<String, String>) breakdown.getValue()).entrySet())
                                    {
                                        sb2.append('"').append(ppmDisplayInfo.getKey()).append('"').append(':');
                                        sb2.append('"').append(ppmDisplayInfo.getValue()).append('"').append(',');
                                    }
                                    if (sb2.charAt(sb2.length()-1) == ',')
                                        sb2.deleteCharAt(sb2.length()-1);
                                    sb2.append('}');
                                }
                                else
                                    sb2.append('"').append(breakdown.getValue()).append('"');

                                sb2.append(',');
                            }
                            if (sb2.charAt(sb2.length()-1) == ',')
                                sb2.deleteCharAt(sb2.length()-1);
                            sb2.append('}').append(',');
                        }

                        if (sb2.charAt(sb2.length()-1) == ',')
                            sb2.deleteCharAt(sb2.length()-1);
                        sb2.append('}');
                    }
                    else if (operatorInfo.getValue() instanceof String)
                        sb2.append('"').append(operatorInfo.getValue()).append('"');
                    else if (operatorInfo.getValue() instanceof Integer)
                        sb2.append(operatorInfo.getValue());

                    sb2.append(',');
                }
                sb.append('"').append(operator.getKey()).append("\":{").append(sb2.toString().substring(0, sb2.length()-1)).append("},");
            }
            if (sb.charAt(sb.length()-1) == ',')
                sb.deleteCharAt(sb.length()-1);
            sb.append('}');

            if (incidentMessages == null || incidentMessages.trim().equals("null") || incidentMessages.trim().equals(""))
                incidentMessages = "No messages";

            sb.append(",\"IncidentMessages\":\"").append(incidentMessages)
                    .append("\",\"timestamp\":").append(timestamp.getTime())
                    /*.append(",\"lastMessage\":\"").append(message)*/
                    .append('"');

            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(saveFile))))
            {
                bw.write(sb.toString());
            }

            printRTPPM("Saved json file (" + (saveFile.length() / 1024L) + "kb)", false);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="HTML file">
        StringBuilder html = new StringBuilder("<!DOCTYPE html>\r\n");
        html.append("<html>\r\n");
        html.append("  <head>\r\n");
        html.append("    <title>Real-Time PPM</title>\r\n");
        html.append("    <meta charset=\"utf-8\">\r\n");
        html.append("    <meta content=\"width=device-width,initial-scale=1.0\" name=\"viewport\">\r\n");
        html.append("    <meta http-equiv=\"refresh\" content=\"600\">\r\n");
        html.append("    <meta name=\"description\" content=\"Real-Time PPM\">\r\n");
        html.append("    <meta name=\"author\" content=\"Cameron Bird\">\r\n");
        html.append("    <link rel=\"icon\" type=\"image/x-icon\" href=\"/favicon.ico\">\r\n");
        html.append("    <link rel=\"stylesheet\" type=\"text/css\" href=\"/default.css\">\r\n");
        html.append("  </head>\r\n");
        html.append("  <body>\r\n");
        html.append("    <div class=\"ppmMain\">\r\n");
        html.append("    <p id=\"title\"><abbr title=\"Real-Time (at 15 min intervals) Public Performance Measure\">Real-Time PPM</abbr>&nbsp;<span class=\"small\">").append(new SimpleDateFormat("(dd/MM HH:mm:ss)").format(timestamp)).append("</span></p>\r\n");
        //html.append("    <p id=\"title\"><img id=\"logo\" src=\"/logo.png\"><abbr title=\"Real-Time (15 min intervals) Public Performance Measure\">Real-Time PPM</abbr>&nbsp;<span class=\"small\">").append(new SimpleDateFormat("(dd/MM HH:mm)").format(new Date())).append("</span></p>\r\n");

        String[] keys = operators.keySet().toArray(new String[0]);
        Arrays.sort(keys, String.CASE_INSENSITIVE_ORDER);
        for (String key : keys)
            html.append(operators.get(key).htmlString()).append("\r\n");

        html.append("      <div id=\"ppmIncidents\">\r\n");
        html.append("        <p><b>Incident Messages:</b></p>\r\n");

        for (String incidentMessage : incidentMessages.split("\\n"))
            html.append("        <p>").append(incidentMessage.trim()).append("</p>\r\n");

        html.append("      </div>");
        html.append("    </div>");
        html.append("    <script type=\"text/javascript\">setInterval(function() { document.reload(true) }, 600000);</script>");
        html.append("  </body>");
        html.append("</html>");

        try
        {
            File htmlFile = new File(NRODClient.EASMStorageDir, "ppm.php");
            if (!htmlFile.exists())
            {
                htmlFile.getParentFile().mkdirs();
                htmlFile.createNewFile();
            }

            try (BufferedWriter out = new BufferedWriter(new FileWriter(htmlFile)))
            {
                out.write(html.toString().replace("  ", "").replace("\n", ""));
            }
            catch (FileNotFoundException e) {}
            catch (IOException e)  { NRODClient.printThrowable(e, "RTPPM HTML"); }

            printRTPPM("Saved html (" + (htmlFile.length() / 1024L) + "kb)", false);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM HTML"); }
        //</editor-fold>

        lastMessage = message;
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    private void logFinalPPMs()
    {
        try
        {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DATE, -1);

            File file = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(cal.getTime()).replace("/", "-") + "-final.json");
            file.getParentFile().mkdirs();
            file.createNewFile();

            logStream.close();

            logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(new Date()).replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }

            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file))))
            {
                bw.write(lastMessage);
            }
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
    }

    public static class Operator
    {
        public  final String NAME;
        public  final int    CODE;
      //private       Map<String, Map<String, String>> ppmMap     = new HashMap<>();
        private       Map<String, Map<String, Object>> serviceMap = new HashMap<>();
        private       String keySymbol = "";

        public Operator(String name, int code)
        {
            NAME = name;
            CODE = code;
        }

        /*public void addPPMData(Map<String, Map<String, String>> map)
        {
            ppmMap.putAll(map);
        }

        public Map<String, String> getService(String serviceName)
        {
            return ppmMap.get(serviceName);
        }*/

        public void setKeySymbol(String keySymbol) { this.keySymbol = keySymbol; }

        public String getKeySymbol() { return keySymbol; }

        public void putService(String serviceName, Map<String, Object> fullIndividualServiceMap)
        {
            Map<String, Object> trimmedServiceMap = new HashMap<>();
            if (serviceMap.containsKey(serviceName))
                trimmedServiceMap = serviceMap.get(serviceName);

            keySymbol = String.valueOf(fullIndividualServiceMap.get("keySymbol"));
            keySymbol = keySymbol.replace("null", " ");

            int casl = Integer.parseInt((String) fullIndividualServiceMap.get("CancelVeryLate"));
            int late = Integer.parseInt((String) fullIndividualServiceMap.get("Late")) - casl;

            trimmedServiceMap.put("CancelVeryLate", (String) fullIndividualServiceMap.get("CancelVeryLate"));
            trimmedServiceMap.put("Late",           Integer.toString(late));
            trimmedServiceMap.put("OnTime",         (String) fullIndividualServiceMap.get("OnTime"));
            trimmedServiceMap.put("Total",          (String) fullIndividualServiceMap.get("Total"));
            trimmedServiceMap.put("PPM",            (Map)    fullIndividualServiceMap.get("PPM"));
            trimmedServiceMap.put("RollingPPM",     (Map)    fullIndividualServiceMap.get("RollingPPM"));

            serviceMap.put(serviceName, trimmedServiceMap);
        }

        public void printPrettyString()
        {
            printRTPPM(NAME + " (" + CODE + ")", false);

            String[] serviceNames = serviceMap.keySet().toArray(new String[0]);
            Arrays.sort(serviceNames, String.CASE_INSENSITIVE_ORDER);

            int length = 12;
            for (String serviceName : serviceNames)
                if (serviceName.length() > length)
                    length = serviceName.length();
            length += 2;

            printRTPPM("  " + lengthen("Service Name, ", length) + "PPM,  Total, RT,   Late, CaSL, Roll PPM", false);

            for (String serviceName : serviceNames)
            {
                if (serviceName.equals("Total"))
                    continue;

                Map<String, Object> map = serviceMap.get(serviceName);
                if (map != null)
                {
                    StringBuilder sb = new StringBuilder("  ");
                    sb.append(lengthen(serviceName + ": ", length));

                    String ppm = String.valueOf(((Map) map.get("PPM")).get("text"));
                    if (!ppm.equals("-1"))
                        sb.append(lengthen(ppm + "%, ", 6));
                    else
                    {
                        try { sb.append(lengthen((100 * ((Integer) Integer.parseInt((String) map.get("OnTime")) / Integer.parseInt((String) map.get("Total")))) + "?, ", 6)); }
                        catch (NumberFormatException e) { sb.append("N/A,  ");  }
                    }

                    sb.append(lengthen(String.valueOf(map.get("Total"))          + ",  ", 7));
                    sb.append(lengthen(String.valueOf(map.get("OnTime"))         + ", ",  6));
                    sb.append(lengthen(String.valueOf(map.get("Late"))           + ", ",  6));
                    sb.append(lengthen(String.valueOf(map.get("CancelVeryLate")) + ", ",  6));

                    String rPPM = String.valueOf(((Map) map.get("RollingPPM")).get("text"));
                    if (!rPPM.equals("-1"))
                        sb.append(lengthen(rPPM + "% ", 5)).append(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd"))));
                    else
                        sb.append("0%   ").append(getTrendArrow("="));

                    printRTPPM(sb.toString(), false);
                }
            }

            Map<String, Object> map = serviceMap.get("Total");
            if (map != null)
            {
                StringBuilder sb = new StringBuilder("  ");
                sb.append(lengthen("Total: ", length));

                String ppm = String.valueOf(((Map) map.get("PPM")).get("text"));
                if (!ppm.equals("-1"))
                    sb.append(lengthen(ppm + "%, ", 6));
                else
                {
                    try { sb.append(lengthen((100 * ((Integer) Integer.parseInt((String) map.get("OnTime")) / Integer.parseInt((String) map.get("Total")))) + "?, ", 6)); }
                    catch (NumberFormatException e) { sb.append("N/A,  ");  }
                }

                sb.append(lengthen(String.valueOf(map.get("Total"))          + ",  ", 7));
                sb.append(lengthen(String.valueOf(map.get("OnTime"))         + ", ",  6));
                sb.append(lengthen(String.valueOf(map.get("Late"))           + ", ",  6));
                sb.append(lengthen(String.valueOf(map.get("CancelVeryLate")) + ", ",  6));

                String rPPM = String.valueOf(((Map) map.get("RollingPPM")).get("text"));
                if (!rPPM.equals("-1"))
                    sb.append(lengthen(rPPM + "% ", 5)).append(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd"))));
                else
                    sb.append("0%   ").append(getTrendArrow("="));

                printRTPPM(sb.toString(), false);
            }

            //<editor-fold defaultstate="collapsed" desc="Old prettyString">
            /*String formattedMap  = "    Name: " + NAME + " (" + CODE + ")";
                   formattedMap += "\n    Services:";

            String[] keys = serviceMap.keySet().toArray(new String[0]);
            Arrays.sort(keys);

            int length = 0;
            for (String key : keys)
                if (key.length() > length)
                    length = key.length();

            for (String serviceName : serviceNames)
            {
                if (serviceName.equals("Total"))
                    continue;

                Map<String, Object> map = serviceMap.get(serviceName);
                if (map != null)
                {
                    formattedMap += "\n      " + lengthen(serviceName + ": ", length + 2);

                    formattedMap += "PPM: " + lengthen(((Map) map.get("PPM")).get("text") + "%, ", 6);
                    if (!String.valueOf(((Map) map.get("RollingPPM")).get("text")).equals("-1"))
                        formattedMap += "Rolling PPM: " + lengthen(((Map) map.get("RollingPPM")).get("text") + "%", 4) + " (" + String.valueOf(((Map) map.get("RollingPPM")).get("trendInd")) + "), ";
                    else
                        formattedMap += "Rolling PPM: --------, ";
                    formattedMap += "(Total: " + lengthen(String.valueOf(map.get("Total")) + ",", 5) + " On Time: " + lengthen(String.valueOf(map.get("OnTime")) + ",", 5) + " Late: " + lengthen(String.valueOf(map.get("Late")) + ",", 4) + " Very Late/Cancelled: " + String.valueOf(map.get("CancelVeryLate")) + ")";
                }
            }

            Map<String, Object> map = serviceMap.get("Total");
            if (map != null)
            {
                formattedMap += "\n      " + lengthen("Total: ", length + 2);

                formattedMap += "PPM: " + lengthen(((Map) map.get("PPM")).get("text") + "%, ", 6);
                if (!String.valueOf(((Map) map.get("RollingPPM")).get("text")).equals("-1"))
                    formattedMap += "Rolling PPM: " + lengthen(((Map) map.get("RollingPPM")).get("text") + "%", 4) + " (" + String.valueOf(((Map) map.get("RollingPPM")).get("trendInd")) + "), ";
                else
                    formattedMap += "Rolling PPM: --------, ";
                formattedMap += "(Total: " + lengthen(String.valueOf(map.get("Total")) + ",", 5) + " On Time: " + lengthen(String.valueOf(map.get("OnTime")) + ",", 5) + " Late: " + lengthen(String.valueOf(map.get("Late")) + ",", 4) + " Very Late/Cancelled: " + String.valueOf(map.get("CancelVeryLate")) + ")";
            }

            return formattedMap;*/
            //</editor-fold>
        }

        public String htmlString()
        {
            StringBuilder sb = new StringBuilder();

            sb.append("    <h3 class=\"ppmTableTitle\">").append(NAME).append(" (").append(CODE).append(")").append(keySymbol.trim().replace("*", " (10 mins)").replace("^", " (5 mins)")).append("<br/></h3>");
            sb.append("    <table class=\"ppmTable\" sortable>");
            sb.append("      <tr>");
            sb.append("        <th class=\"ppmTable\" rowspan=\"2\">Service Name</th>");
            sb.append("        <th class=\"ppmTable\" rowspan=\"2\"><abbr title=\"Public Performance Measure (today)\">PPM</abbr></th>");
            sb.append("        <th class=\"ppmTable\" colspan=\"4\"><abbr title=\"Public Performance Measure Breakdown\">PPM Breakdown</abbr></th>");
            sb.append("        <th class=\"ppmTable\" colspan=\"2\"><abbr title=\"Rolling Public Performance Measure (period of time)\">Rolling PPM</abbr></th>");
            sb.append("      </tr>");
            sb.append("      <tr>");
            sb.append("        <th class=\"ppmTable\">Total</th>");
            sb.append("        <th class=\"ppmTable\"><abbr title=\"<5/10 mins late\">On Time</abbr></th>");
            sb.append("        <th class=\"ppmTable\"><abbr title=\">5/10 mins late and <30 mins late\">Late</abbr></th>");
            sb.append("        <th class=\"ppmTable\"><abbr title=\">30 mins late or cancelled\">Cancel/Very Late</abbr></th>");
            sb.append("        <th class=\"ppmTable\">%</th>");
          //sb.append("        <th class=\"ppmTable\">▲▼</th>");
            sb.append("        <th class=\"ppmTable\">&#x25B2;&#x25BC;</th>");
            sb.append("      </tr>");

            String[] keys = serviceMap.keySet().toArray(new String[0]);
            Arrays.sort(keys, String.CASE_INSENSITIVE_ORDER);

            for (String key : keys)
            {
                if (key.equals("Total"))
                    continue;

                sb.append("      <tr>");

                Map<String, Object> map = serviceMap.get(key);
                if (map != null)
                {
                    sb.append("         <td class=\"ppmTable\">").append(key.replace("&", "&amp;")).append("</td>");

                    String ppm = String.valueOf(((Map) map.get("PPM")).get("text"));
                    if (!ppm.equals("-1"))
                        sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("PPM")).get("rag")))).append("\">").append(ppm).append("%</td>");
                    else
                    {
                        sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("PPM")).get("rag")))).append("\">");
                        try { sb.append("<abbr title=\"guess\">").append(100 * ((Integer) Integer.parseInt((String) map.get("OnTime")) / Integer.parseInt((String) map.get("Total")))).append("%</abbr>"); }
                        catch (NumberFormatException e) { sb.append("N/A"); }
                        sb.append("</td>");
                    }

                    sb.append("        <td class=\"ppmTable\">").append(map.get("Total")).append("</td>");
                    sb.append("        <td class=\"ppmTable\">").append(map.get("OnTime")).append("</td>");
                    sb.append("        <td class=\"ppmTable\">").append(map.get("Late")).append("</td>");
                    sb.append("        <td class=\"ppmTable\">").append(map.get("CancelVeryLate")).append("</td>");

                    String rollPPM = String.valueOf(((Map) map.get("RollingPPM")).get("text"));
                    if (!rollPPM.equals("-1"))
                    {
                        sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("RollingPPM")).get("rag")))).append("\">").append(rollPPM).append("%").append("</td>");
                        sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd"))))).append("\">").append(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd")))).append("</td>");
                    }
                    else
                    {
                        sb.append("        <td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                        sb.append("        <td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                    }

                }

                sb.append("      </tr>");
            }

            Map<String, Object> map = serviceMap.get("Total");
            if (map != null)
            {
                sb.append("      <tr>");

                sb.append("        <td class=\"ppmTable\">Total</td>");
                sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("PPM")).get("rag")))).append("\">").append(((Map) map.get("PPM")).get("text")).append("%").append("</td>");

                sb.append("        <td class=\"ppmTable\">").append(map.get("Total")).append("</td>");
                sb.append("        <td class=\"ppmTable\">").append(map.get("OnTime")).append("</td>");
                sb.append("        <td class=\"ppmTable\">").append(map.get("Late")).append("</td>");
                sb.append("        <td class=\"ppmTable\">").append(map.get("CancelVeryLate")).append("</td>");

                String rollPPM = String.valueOf(((Map) map.get("RollingPPM")).get("text"));
                if (!rollPPM.equals("-1"))
                {
                    sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("RollingPPM")).get("rag")))).append("\">").append(rollPPM).append("%").append("</td>");
                    sb.append("        <td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("RollingPPM")).get("rag")))).append("\">").append(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd")))).append("</td>");
                }
                else
                {
                    sb.append("        <td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                    sb.append("        <td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                }

                sb.append("      </tr>");
            }

            sb.append("    </table>");

            return sb.toString().replace("▲", "&#x25B2;").replace("▬", "&#9644;").replace("▼", "&#x25BC;");
        }

        public Map<String, Object> getMap()
        {
            Map<String, Object> map = new HashMap<>();

            map.put("name",       NAME);
            map.put("code",       CODE);
            map.put("keySymbol",  keySymbol);
          //map.put("ppmMap",     ppmMap);
            map.put("serviceMap", serviceMap);

            return map;
        }

        public void readMap(Map<String, Object> map)
        {
            if (map.containsKey("keySymbol")  && map.get("keySymbol")  != null) keySymbol  = (String) map.get("keySymbol");
          //if (map.containsKey("ppmMap")     && map.get("ppmMap")     != null) ppmMap     = (Map<String, Map<String, String>>) map.get("ppmMap");
            if (map.containsKey("serviceMap") && map.get("serviceMap") != null) serviceMap = (Map<String, Map<String, Object>>) map.get("serviceMap");
        }

        private String getTrendArrow(String trendChar)
        {
            switch (trendChar)
            {
                case "+":
                    return "▲";
                case "=":
                    return "▬";
                case "-":
                    return "▼";
                default:
                    return trendChar;
            }
        }

        private String getColour(String rag)
        {
            switch (rag)
            {
                case "▲":
                case "G":
                    return "#25B225";

                case "▬":
                case "A":
                    return "#D0A526";

                case "▼":
                case "R":
                    return "#C50000";

                default:
                    return "#000";
            }
        }

        private String lengthen(String string, int length)
        {
            while (string.length() < length)
                string += " ";

            return string.substring(0, length);
        }
    }

    public static Map<String, Map<String, Object>> getPPMData()
    {
        Map<String, Map<String, Object>> map = new HashMap<>();

        operators.entrySet().parallelStream().forEach((pairs) -> map.put(pairs.getKey(), pairs.getValue().getMap()));

        return map;
    }

    public static void readData(Map<String, Map<String, Object>> dataMap)
    {
        dataMap.entrySet().stream().forEach((pairs) ->
        {
            if (!operators.containsKey(pairs.getKey()))
                operators.put(pairs.getKey(), new Operator((String) pairs.getValue().get("name"), (int) pairs.getValue().get("code")));

            operators.get(pairs.getKey()).readMap(pairs.getValue());
        });
    }

    public static void uploadHTML()
    {
        if (!NRODClient.ftpBaseUrl.isEmpty())
        {
            try
            {
                File htmlFile = new File(NRODClient.EASMStorageDir, "ppm.php");
                if (htmlFile.exists())
                {
                    String html = "<html>Upload failed</html>";
                    try (BufferedReader in = new BufferedReader(new FileReader(htmlFile)))
                    {
                        html = "";
                        String line;
                        while ((line = in.readLine()) != null)
                            html += line + "\n";
                    }
                    catch (FileNotFoundException e) {}
                    catch (IOException e) {}

                    URLConnection con = new URL(NRODClient.ftpBaseUrl + "PPM/index.php;type=i").openConnection();
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(10000);
                    try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(con.getOutputStream())))
                    {
                        out.write(html);
                        out.flush();

                        printRTPPM("Uploaded HTML", false);
                    }
                    catch (SocketTimeoutException e) { printRTPPM("HTML upload Timeout", true); }
                    catch (IOException e) {}
                }
            }
            catch (MalformedURLException e) {}
            catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
        }
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 180000; }

    private static void printRTPPM(String message, boolean toErr)
    {
        if (NRODClient.verbose)
        {
            if (toErr)
                NRODClient.printErr("[RTPPM] ".concat(message));
            else
                NRODClient.printOut("[RTPPM] ".concat(message));
        }

        logStream.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(toErr ? "!!!> " : "").concat(message).concat(toErr ? " <!!!" : ""));
    }
}