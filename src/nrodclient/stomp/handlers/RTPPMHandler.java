package nrodclient.stomp.handlers;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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
import nrodclient.NRODClient;
import nrodclient.stomp.NRODListener;
import nrodclient.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONObject;

public class RTPPMHandler implements NRODListener
{
    private static PrintWriter logStream;
    private static File        logFile;
  //private static String      lastLogDate = "";

    private String lastMessage = null;
    private static int lastTotal = -1;
    private long lastMessageTime = 0;
    public static final Map<String, Operator> operators = new HashMap<>();

    private static NRODListener instance = null;
    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new RTPPMHandler();

        return instance;
    }

    private RTPPMHandler()
    {
        lastMessageTime = System.currentTimeMillis();

        logFile = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(new Date()).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();

        try
        {
            logFile.createNewFile();
            logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }

        File saveFile = new File(NRODClient.EASM_STORAGE_DIR, "RTPPM.json");
        if (saveFile.exists())
        {
            try (BufferedReader br = new BufferedReader(new FileReader(saveFile)))
            {
                String jsonString = "";

                String line;
                while ((line = br.readLine()) != null)
                    jsonString += line;

                JSONObject json = new JSONObject(jsonString);

                readData(json.getJSONObject("RTPPMData"));

                List<String> operatorNames = new ArrayList<>(operators.keySet());
                Collections.sort(operatorNames, String.CASE_INSENSITIVE_ORDER);
                operatorNames.stream().forEachOrdered((operator) -> operators.get(operator).printPrettyString());

                printRTPPM("Incident Messages:", false);
                for (String incidentMessage : json.optString("IncidentMessages").split("\\n"))
                    printRTPPM("  " + incidentMessage.trim(), false);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
            catch (Exception e) { NRODClient.printThrowable(e, "RTPPM"); }
        }

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        int mins = calendar.get(Calendar.MINUTE);
        calendar.add(Calendar.MINUTE, 5 - (mins % 5));
        calendar.set(Calendar.SECOND, 30);
        calendar.set(Calendar.MILLISECOND, 500);

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> uploadHTML(), calendar.getTimeInMillis() - System.currentTimeMillis(), 300000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void message(Map<String, String> headers, final String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        JSONObject ppmMap = new JSONObject(message).getJSONObject("RTPPMDataMsgV1");
        String incidentMessages = ppmMap.getJSONObject("RTPPMData").getJSONObject("NationalPage").optString("WebMsgOfMoment").trim();

        Date timestamp = new Date(Long.parseLong(ppmMap.getString("timestamp")));

        int newTotal = Integer.parseInt(ppmMap.getJSONObject("RTPPMData").getJSONObject("NationalPage").getJSONObject("NationalPPM").getString("Total"));

        if (newTotal*0.75 < lastTotal && lastTotal >= 0)
        {
            logFinalPPMs();

            operators.clear();
        }
        lastTotal = newTotal;

        try
        {
            JSONArray operatorsPPM = ppmMap.getJSONObject("RTPPMData").getJSONArray("OperatorPage");
            for (Object mapObj : operatorsPPM)
            {
                JSONObject map = (JSONObject) mapObj;
                
                String operatorName = map.getJSONObject("Operator").getString("name");
                Operator operator;
                if (operators.containsKey(operatorName))
                    operator = operators.get(operatorName);
                else
                {
                    operator = new Operator(operatorName, Integer.parseInt(map.getJSONObject("Operator").getString("code")));
                    operators.put(operatorName, operator);
                }

                operator.putService("Total", map.getJSONObject("Operator"));

                if (map.has("OprServiceGrp"))
                {
                    if (map.get("OprServiceGrp") instanceof JSONObject)
                        operator.putService(map.getJSONObject("OprServiceGrp").getString("name"), map.getJSONObject("OprServiceGrp"));
                    else if (map.get("OprServiceGrp") instanceof JSONArray)
                        map.getJSONArray("OprServiceGrp").forEach((serviceObj) -> operator.putService(((JSONObject)serviceObj).getString("name"), (JSONObject) serviceObj));
                }
            }
        }
        catch (Exception e) { NRODClient.printThrowable(e, "RTPPM"); }

        lastMessage = message;
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));

        JSONObject ppmData = getPPMData();

        //<editor-fold defaultstate="collapsed" desc="Print out">
        //List<String> operatorNames = new ArrayList<>(operators.keySet());
        //Collections.sort(operatorNames, String.CASE_INSENSITIVE_ORDER);
        //operatorNames.stream().forEachOrdered((operator) -> operators.get(operator).printPrettyString());

        //printRTPPM("Incident Messages:", false);
        //for (String incidentMessage : incidentMessages.split("\\n"))
        //    printRTPPM("  " + incidentMessage.trim(), false);
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="JSON file">
        try
        {
            File saveFile = new File(NRODClient.EASM_STORAGE_DIR, "RTPPM.json");
            if (!saveFile.exists())
            {
                saveFile.getParentFile().mkdirs();
                saveFile.createNewFile();
            }

            JSONObject RTPPMData = new JSONObject();
            RTPPMData.put("RTPPMData", ppmData);

            if (incidentMessages.trim().equals("null") || incidentMessages.trim().equals(""))
                incidentMessages = "No messages";

            RTPPMData.put("IncidentMessages", incidentMessages);
            RTPPMData.put("timestamp", timestamp.getTime());

            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(saveFile))))
            {
                bw.write(RTPPMData.toString());
            }

            printRTPPM("Saved json file (" + (saveFile.length() / 1024L) + "kb)", false);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
        //</editor-fold>

        //<editor-fold defaultstate="collapsed" desc="HTML file">
        StringBuilder html = new StringBuilder("<!DOCTYPE html>");
        html.append("<html>");
        html.append("<head>");
        html.append("<title>Real-Time PPM</title>");
        html.append("<meta charset=\"utf-8\">");
        html.append("<meta content=\"width=device-width,initial-scale=1.0\" name=\"viewport\">");
        html.append("<meta http-equiv=\"refresh\" content=\"300\">");
        html.append("<meta name=\"description\" content=\"Real-Time PPM\">");
        html.append("<meta name=\"author\" content=\"Cameron Bird\">");
        html.append("<meta name=\"theme-color\" content=\"#646464\">");
        html.append("<link rel=\"icon\" type=\"image/x-icon\" href=\"/favicon.ico\">");
        html.append("<link rel=\"stylesheet\" type=\"text/css\" href=\"/default.css\">");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"ppmMain\">");
        html.append("<p id=\"title\"><abbr title=\"Real-Time (at 15 min intervals) Public Performance Measure\">Real-Time PPM</abbr>&nbsp;<span class=\"small\">(")
                .append(NRODClient.sdfDateTimeShort.format(new Date(timestamp.getTime() - (TimeZone.getDefault().inDaylightTime(timestamp) ? 3600000 : 0)))) // Time fix
                .append(")</span></p>");

        String[] keys = operators.keySet().toArray(new String[0]);
        Arrays.sort(keys, String.CASE_INSENSITIVE_ORDER);
        for (String key : keys)
            html.append(operators.get(key).htmlString());

        html.append("<div id=\"ppmIncidents\">");
        html.append("<p><b>Incident Messages:</b></p>");

        for (String incidentMessage : incidentMessages.split("\\n"))
            html.append("<p>").append(incidentMessage.trim()).append("</p>");

        html.append("</div>");
        html.append("</div>");
        html.append("<script>");
        html.append("(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){(i[r].q=i[r].q||[]).push(arguments)},i[r].l=Date.now();a=s.createElement(o),m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)})(window,document,'script','//www.google-analytics.com/analytics.js','ga');");
        html.append("ga('create', 'UA-72821900-1', 'auto');");
        html.append("ga('send', 'pageview');");
      //html.append("google_ad_type = \"image\";");
      //html.append("document.write('<p class=\"panelContainer\"><script src=\"http://en.ad.altervista.org/js2.ad/size=728X90/?ref='+encodeURIComponent(location.hostname+location.pathname)+'&r='+Date.now()+'\"><\\/script><\\/p>');");
      //html.append("$('html,body').animate({ scrollTop: 0 }, 500);");
        html.append("setInterval(function() { location.reload(true) }, 120000);");
        html.append("</script>");
        html.append("</body>");
        html.append("</html>");

        try
        {
            File htmlFile = new File(NRODClient.EASM_STORAGE_DIR, "ppm.php");
            if (!htmlFile.exists())
            {
                htmlFile.getParentFile().mkdirs();
                htmlFile.createNewFile();
            }

            try (BufferedWriter out = new BufferedWriter(new FileWriter(htmlFile)))
            {
                out.write(html.toString());
            }
            catch (FileNotFoundException e) {}
            catch (IOException e)  { NRODClient.printThrowable(e, "RTPPM HTML"); }

            printRTPPM("Saved html (" + (htmlFile.length() / 1024L) + "kb)", false);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM HTML"); }
        //</editor-fold>
    }

    private void logFinalPPMs()
    {
        try
        {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DATE, -1);

            File file = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(cal.getTime()).replace("/", "-") + "-final.json");
            file.getParentFile().mkdirs();
            file.createNewFile();

            logStream.close();

            logFile = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(new Date()).replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, false)))
            {
                bw.write(lastMessage);
                bw.write("\r\n");
                bw.flush();
            }
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
    }

    public static class Operator
    {
        public  final String NAME;
        public  final int    CODE;
        private       JSONObject serviceMap = new JSONObject();
        private       String keySymbol = "";

        public Operator(String name, int code)
        {
            NAME = name;
            CODE = code;
        }

        public void setKeySymbol(String keySymbol) { this.keySymbol = keySymbol; }

        public String getKeySymbol() { return keySymbol; }

        public void putService(String serviceName, JSONObject fullIndividualServiceMap)
        {
            JSONObject trimmedServiceMap = new JSONObject();
            if (serviceMap.has(serviceName))
                trimmedServiceMap = serviceMap.getJSONObject(serviceName);

            keySymbol = fullIndividualServiceMap.optString("keySymbol");

            int casl = Integer.parseInt(fullIndividualServiceMap.getString("CancelVeryLate"));
            int late = Integer.parseInt(fullIndividualServiceMap.getString("Late")) - casl;
            
            trimmedServiceMap.put("CancelVeryLate", Integer.toString(casl));
            trimmedServiceMap.put("Late",           Integer.toString(late));
            trimmedServiceMap.put("OnTime",         fullIndividualServiceMap.getString("OnTime"));
            trimmedServiceMap.put("Total",          fullIndividualServiceMap.getString("Total"));
            trimmedServiceMap.put("PPM",            fullIndividualServiceMap.getJSONObject("PPM"));
            trimmedServiceMap.put("RollingPPM",     fullIndividualServiceMap.getJSONObject("RollingPPM"));
            trimmedServiceMap.put("Timeband",       fullIndividualServiceMap.optString("timeband", keySymbol.replace("*", "10").replace("^", "5")));
            trimmedServiceMap.put("SectorCode",     fullIndividualServiceMap.optString("sectorCode"));

            serviceMap.put(serviceName, trimmedServiceMap);
        }

        public void printPrettyString()
        {
            printRTPPM(NAME + (CODE > 0 ? " (" + CODE + ")" : ""), false);

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

                JSONObject map = serviceMap.optJSONObject(serviceName);
                if (map != null)
                {
                    StringBuilder sb = new StringBuilder("  ");
                    sb.append(lengthen(serviceName + ": ", length));

                    String ppm = map.getJSONObject("PPM").getString("text");
                    if (!ppm.equals("-1"))
                        sb.append(lengthen(ppm + "%, ", 6));
                    else
                    {
                        try { sb.append(lengthen((map.getString("Total").equals("0") ? "0" : (100 * (Integer.parseInt(map.getString("OnTime")) / Integer.parseInt(map.getString("Total"))))) + "?, ", 6)); }
                        catch (NumberFormatException | ArithmeticException e) { sb.append("N/A,  ");  }
                    }

                    sb.append(lengthen(map.getString("Total")          + ",  ", 7));
                    sb.append(lengthen(map.getString("OnTime")         + ", ",  6));
                    sb.append(lengthen(map.getString("Late")           + ", ",  6));
                    sb.append(lengthen(map.getString("CancelVeryLate") + ", ",  6));

                    String rPPM = map.getJSONObject("RollingPPM").getString("text");
                    if (!rPPM.equals("-1"))
                        sb.append(lengthen(rPPM + "% ", 5)).append(getTrendArrow(map.getJSONObject("RollingPPM").getString("trendInd")));
                    else
                        sb.append("0%   ").append(getTrendArrow("="));

                    printRTPPM(sb.toString(), false);
                }
            }

            JSONObject map = serviceMap.optJSONObject("Total");
            if (map != null)
            {
                StringBuilder sb = new StringBuilder("  ");
                sb.append(lengthen("Total: ", length));

                String ppm = map.getJSONObject("PPM").getString("text");
                if (!ppm.equals("-1"))
                    sb.append(lengthen(ppm + "%, ", 6));
                else if (!map.getString("Total").equals("0"))
                {
                    try { sb.append(lengthen((100 * ((Integer) Integer.parseInt(map.getString("OnTime")) / Integer.parseInt(map.getString("Total")))) + "?, ", 6)); }
                    catch (NumberFormatException | ArithmeticException e) { sb.append("N/A,  ");  }
                }
                else
                    sb.append("0?,   ");

                sb.append(lengthen(map.getString("Total")          + ",  ", 7));
                sb.append(lengthen(map.getString("OnTime")         + ", ",  6));
                sb.append(lengthen(map.getString("Late")           + ", ",  6));
                sb.append(lengthen(map.getString("CancelVeryLate") + ", ",  6));

                String rPPM = map.getJSONObject("RollingPPM").getString("text");
                if (!rPPM.equals("-1"))
                    sb.append(lengthen(rPPM + "% ", 5)).append(getTrendArrow(map.getJSONObject("RollingPPM").getString("trendInd")));
                else
                    sb.append("0%   ").append(getTrendArrow("="));

                printRTPPM(sb.toString(), false);
            }
        }

        public String htmlString()
        {
            StringBuilder sb = new StringBuilder();

            sb.append("<h3 class=\"ppmTableTitle\">").append(NAME).append(CODE > 0 ? " ("+CODE+")" : "").append("<br/></h3>");
            sb.append("<table class=\"ppmTable\" sortable>");
            sb.append("<tr>");
            sb.append("<th class=\"ppmTable\" rowspan=\"2\">Service Name</th>");
            sb.append("<th class=\"ppmTable\" rowspan=\"2\"><abbr title=\"Public Performance Measure (today)\">PPM</abbr></th>");
            sb.append("<th class=\"ppmTable\" colspan=\"4\"><abbr title=\"Public Performance Measure Breakdown\">PPM Breakdown</abbr></th>");
            sb.append("<th class=\"ppmTable\" colspan=\"2\"><abbr title=\"Rolling Public Performance Measure (period of time)\">Rolling PPM</abbr></th>");
            sb.append("</tr>");
            sb.append("<tr>");
            sb.append("<th class=\"ppmTable\">Total</th>");
            sb.append("<th class=\"ppmTable\"><abbr title=\"<5/10 mins late\">On Time</abbr></th>");
            sb.append("<th class=\"ppmTable\"><abbr title=\">5/10 mins late and <30 mins late\">Late</abbr></th>");
            sb.append("<th class=\"ppmTable\"><abbr title=\">30 mins late or cancelled\">Cancel/Very Late</abbr></th>");
            sb.append("<th class=\"ppmTable\">%</th>");
          //sb.append("<th class=\"ppmTable\">▲▼</th>");
            sb.append("<th class=\"ppmTable\">&#x25B2;&#x25BC;</th>");
            sb.append("</tr>");

            String[] keys = serviceMap.keySet().toArray(new String[0]);
            Arrays.sort(keys, String.CASE_INSENSITIVE_ORDER);

            for (String key : keys)
            {
                if (key.equals("Total"))
                    continue;

                sb.append("<tr>");

                JSONObject map = serviceMap.optJSONObject(key);
                if (map != null)
                {
                    sb.append("<td class=\"ppmTable\">").append(key.replace("&", "&amp;")).append("</td>");

                    String ppm = map.getJSONObject("PPM").getString("text");
                    if (!ppm.equals("-1"))
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(map.getJSONObject("PPM").getString("rag"))).append("\">").append(ppm).append("%</td>");
                    else
                    {
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(map.getJSONObject("PPM").getString("rag"))).append("\">");
                        try { sb.append("<abbr title=\"estimated\">").append(100 * (Integer.parseInt(map.getString("OnTime")) / Integer.parseInt(map.getString("Total")))).append("%</abbr>"); }
                        catch (NumberFormatException | ArithmeticException e) { sb.append("N/A"); }
                        sb.append("</td>");
                    }

                    sb.append("<td class=\"ppmTable\">").append(map.getString("Total")).append("</td>");
                    sb.append("<td class=\"ppmTable\">").append(map.getString("OnTime")).append("</td>");
                    sb.append("<td class=\"ppmTable\">").append(map.getString("Late")).append("</td>");
                    sb.append("<td class=\"ppmTable\">").append(map.getString("CancelVeryLate")).append("</td>");

                    String rollPPM = map.getJSONObject("RollingPPM").getString("text");
                    if (!rollPPM.equals("-1"))
                    {
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(map.getJSONObject("RollingPPM").getString("rag"))).append("\">").append(rollPPM).append("%").append("</td>");
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(getTrendArrow(map.getJSONObject("RollingPPM").getString("trendInd")))).append("\">").append(getTrendArrow(map.getJSONObject("RollingPPM").getString("trendInd"))).append("</td>");
                    }
                    else
                    {
                        sb.append("<td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                        sb.append("<td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                    }

                }

                sb.append("</tr>");
            }

            JSONObject map = serviceMap.optJSONObject("Total");
            if (map != null)
            {
                sb.append("<tr>");

                sb.append("<td class=\"ppmTable\">Total</td>");
                sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(map.getJSONObject("PPM").getString("rag"))).append("\">").append(map.getJSONObject("PPM").getString("text")).append("%").append("</td>");

                sb.append("<td class=\"ppmTable\">").append(map.getString("Total")).append("</td>");
                sb.append("<td class=\"ppmTable\">").append(map.getString("OnTime")).append("</td>");
                sb.append("<td class=\"ppmTable\">").append(map.getString("Late")).append("</td>");
                sb.append("<td class=\"ppmTable\">").append(map.getString("CancelVeryLate")).append("</td>");

                String rollPPM = map.getJSONObject("RollingPPM").getString("text");
                if (!rollPPM.equals("-1"))
                {
                    sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(map.getJSONObject("RollingPPM").getString("rag"))).append("\">").append(rollPPM).append("%").append("</td>");
                    sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(map.getJSONObject("RollingPPM").getString("rag"))).append("\">").append(getTrendArrow(map.getJSONObject("RollingPPM").getString("trendInd"))).append("</td>");
                }
                else
                {
                    sb.append("<td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                    sb.append("<td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                }

                sb.append("</tr>");
            }

            sb.append("</table>");

            return sb.toString().replace("▲", "&#x25B2;").replace("▬", "&#9644;").replace("▼", "&#x25BC;");
        }

        public JSONObject getMap()
        {
            JSONObject map = new JSONObject();

            map.put("name",       NAME);
            map.put("code",       CODE);
            map.put("keySymbol",  keySymbol);
            map.put("serviceMap", serviceMap);

            return map;
        }

        public void readMap(JSONObject map)
        {
            if (map.has("keySymbol")  && map.opt("keySymbol")  != null) keySymbol  = map.getString("keySymbol");
            if (map.has("serviceMap") && map.opt("serviceMap") != null) serviceMap = map.getJSONObject("serviceMap");
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
            return lengthen(string, length, ' ');
        }
        private String lengthen(String string, int length, char spacer)
        {
            while (string.length() < length)
                string += spacer;

            return string.substring(0, length);
        }
    }

    public static JSONObject getPPMData()
    {
        JSONObject obj = new JSONObject();

        operators.entrySet().parallelStream().forEach((pairs) -> obj.put(pairs.getKey(), pairs.getValue().getMap()));

        return obj;
    }

    public static void readData(JSONObject dataMap)
    {
        for (String key : dataMap.keySet())
        {
            if (!operators.containsKey(key))
                operators.put(key, new Operator(dataMap.getJSONObject(key).getString("name"), dataMap.getJSONObject(key).getInt("code")));

            operators.get(key).readMap(dataMap.getJSONObject(key));
        }
    }

    public static void uploadHTML()
    {
        try
        {
            File htmlFile = new File(NRODClient.EASM_STORAGE_DIR, "ppm.php");
            if (htmlFile.exists())
            {
                try
                {
                    NRODClient.reloadConfig();
                    JSch jsch = new JSch();

                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); BufferedInputStream is = new BufferedInputStream(new FileInputStream(new File(NRODClient.EASM_STORAGE_DIR, "key.openssh"))))
                    {
                        byte[] buff = new byte[8192];
                        int read;
                        while ((read = is.read(buff, 0, buff.length)) != -1)
                            baos.write(buff, 0, read);
                        
                        jsch.getIdentityRepository().add(baos.toByteArray());
                        jsch.setKnownHosts(new ByteArrayInputStream(NRODClient.config.getString("SCP_Host").getBytes()));
                    }

                    Session session = jsch.getSession(NRODClient.config.getString("SCP_User"), "signalmaps.co.uk");
                    session.connect();
                    Channel channel = session.openChannel("sftp");
                    channel.connect();
                    ChannelSftp channelSftp = (ChannelSftp) channel;

                    channelSftp.put(new FileInputStream(htmlFile), NRODClient.config.getString("SCP_PPM_Path"));

                    channelSftp.exit();
                    session.disconnect();

                    printRTPPM("Uploaded HTML", false);
                }
                catch (JSchException | SftpException ex) { NRODClient.printThrowable(ex, "RTPPM"); }
            }
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }
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