package nrodclient.stomp.handlers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
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
import nrodclient.NRODClient;
import nrodclient.stomp.NRODListener;
import nrodclient.stomp.StompConnectionHandler;

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

        logFile = new File(NRODClient.EASMStorageDir, "Logs" + File.separator + "RTPPM" + File.separator + NRODClient.sdfDate.format(new Date()).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();

        try
        {
            logFile.createNewFile();
            logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
        }
        catch (IOException e) { NRODClient.printThrowable(e, "RTPPM"); }

        File saveFile = new File(NRODClient.EASMStorageDir, "RTPPM.json");
        if (saveFile.exists())
        {
            try (BufferedReader br = new BufferedReader(new FileReader(saveFile)))
            {
                String jsonString = "";

                String line;
                while ((line = br.readLine()) != null)
                    jsonString += line;

                NRODClient.stdOut.println(jsonString);
                Map<String, Object> json = JSONParser.parseJSON("{" + jsonString + "}");

                readData((Map<String, Map<String, Object>>) json.get("RTPPMData"));

                List<String> operatorNames = new ArrayList<>(operators.keySet());
                Collections.sort(operatorNames, String.CASE_INSENSITIVE_ORDER);
                operatorNames.stream().forEachOrdered((operator) -> operators.get(operator).printPrettyString());

                printRTPPM("Incident Messages:", false);
                for (String incidentMessage : String.valueOf(json.get("IncidentMessages")).split("\\n"))
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

        Map<String, Object> ppmMap = (Map<String, Object>) JSONParser.parseJSON(message).get("RTPPMDataMsgV1");
        String incidentMessages = String.valueOf(((Map) ((Map) ppmMap.get("RTPPMData")).get("NationalPage")).get("WebMsgOfMoment")).trim();

        Date timestamp = new Date(Long.parseLong(String.valueOf(ppmMap.get("timestamp"))));

        int newTotal = Integer.parseInt(String.valueOf(((Map) ((Map) ((Map) ppmMap.get("RTPPMData")).get("NationalPage")).get("NationalPPM")).get("Total")));

        if (newTotal*0.75 < lastTotal && lastTotal >= 0)
        {
            logFinalPPMs();

            operators.clear();
        }
        lastTotal = newTotal;

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
                    ((List) map.get("OprServiceGrp")).stream()
                            .forEach((serviceObj) -> operator.putService(String.valueOf(((Map) serviceObj).get("name")), ((Map) serviceObj))                );
            }
        }
        catch (Exception e) { NRODClient.printThrowable(e, "RTPPM"); }

        lastMessage = message;
        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));

        Map<String, Map<String, Object>> ppmData = new HashMap<>(getPPMData());

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
            File saveFile = new File(NRODClient.EASMStorageDir, "RTPPM.json");
            if (!saveFile.exists())
            {
                saveFile.getParentFile().mkdirs();
                saveFile.createNewFile();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\"RTPPMData\":{");

            ppmData.entrySet().stream().forEach((operator) ->
            {
                StringBuilder sb2 = new StringBuilder();
                operator.getValue().entrySet().stream().forEach(operatorInfo ->
                {
                    sb2.append('"').append(operatorInfo.getKey()).append("\":");
                    if (operatorInfo.getValue() instanceof Map)
                    {
                        sb2.append('{');
                        ((Map<String, Map<String, Object>>) operatorInfo.getValue()).entrySet().stream().forEach((serviceRoute) ->
                        {
                            sb2.append('"').append(serviceRoute.getKey()).append('"').append(':').append('{');
                            serviceRoute.getValue().entrySet().stream().forEach((breakdown) ->
                            {
                                sb2.append('"').append(breakdown.getKey()).append('"').append(':');
                                if (breakdown.getValue() instanceof Integer)
                                    sb2.append(breakdown.getValue());
                                else if (breakdown.getValue() instanceof Map)
                                {
                                    sb2.append('{');
                                    ((Map<String, String>) breakdown.getValue()).entrySet().stream().forEach((ppmDisplayInfo) ->
                                    {
                                        sb2.append('"').append(ppmDisplayInfo.getKey()).append('"').append(':');
                                        sb2.append('"').append(ppmDisplayInfo.getValue()).append('"').append(',');
                                    });
                                    if (sb2.charAt(sb2.length()-1) == ',')
                                        sb2.deleteCharAt(sb2.length()-1);
                                    sb2.append('}');
                                }
                                else
                                    sb2.append('"').append(breakdown.getValue()).append('"');

                                sb2.append(',');
                            });
                            if (sb2.charAt(sb2.length()-1) == ',')
                                sb2.deleteCharAt(sb2.length()-1);
                            sb2.append('}').append(',');
                        });

                        if (sb2.charAt(sb2.length()-1) == ',')
                            sb2.deleteCharAt(sb2.length()-1);
                        sb2.append('}');
                    }
                    else if (operatorInfo.getValue() instanceof String)
                        sb2.append('"').append(operatorInfo.getValue()).append('"');
                    else if (operatorInfo.getValue() instanceof Integer)
                        sb2.append(operatorInfo.getValue());

                    sb2.append(',');
                });
                sb.append('"').append(operator.getKey()).append("\":{").append(sb2.toString().substring(0, sb2.length()-1)).append("},");
            });
            if (sb.charAt(sb.length()-1) == ',')
                sb.deleteCharAt(sb.length()-1);
            sb.append('}');

            if (incidentMessages.trim().equals("null") || incidentMessages.trim().equals(""))
                incidentMessages = "No messages";

            sb.append(",\"IncidentMessages\":\"").append(incidentMessages).append("\",");
            sb.append("\"timestamp\":").append(timestamp.getTime());

            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(saveFile))))
            {
                bw.write(sb.toString());
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
                .append(NRODClient.sdfDateTimeShort.format(new Date(timestamp.getTime() - (TimeZone.getDefault().inDaylightTime(new Date()) ? 3600000 : 0)))) // Time fix
                .append(")</span></p>");

        String[] keys = operators.keySet().toArray(new String[0]);
        Arrays.sort(keys, String.CASE_INSENSITIVE_ORDER);
        for (String key : keys)
            html.append(operators.get(key).htmlString()).append("");

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
        html.append("google_ad_type = \"image\";");
        html.append("document.write('<p class=\"panelContainer\"><script src=\"http://en.ad.altervista.org/js2.ad/size=728X90/?ref='+encodeURIComponent(location.hostname+location.pathname)+'&r='+Date.now()+'\"><\\/script><\\/p>');");
      //html.append("$('html,body').animate({ scrollTop: 0 }, 500);");
        html.append("setInterval(function() { location.reload(true) }, 120000);");
        html.append("</script>");
        html.append("</body>");
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

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true)))
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
        private       Map<String, Map<String, Object>> serviceMap = new HashMap<>();
        private       String keySymbol = "";

        public Operator(String name, int code)
        {
            NAME = name;
            CODE = code;
        }

        public void setKeySymbol(String keySymbol) { this.keySymbol = keySymbol; }

        public String getKeySymbol() { return keySymbol; }

        public void putService(String serviceName, Map<String, Object> fullIndividualServiceMap)
        {
            Map<String, Object> trimmedServiceMap = new HashMap<>();
            if (serviceMap.containsKey(serviceName))
                trimmedServiceMap = serviceMap.get(serviceName);

            keySymbol = String.valueOf(fullIndividualServiceMap.getOrDefault("keySymbol", ""));

            int casl = Integer.parseInt((String) fullIndividualServiceMap.get("CancelVeryLate"));
            int late = Integer.parseInt((String) fullIndividualServiceMap.get("Late")) - casl;

            trimmedServiceMap.put("CancelVeryLate", Integer.toString(casl));
            trimmedServiceMap.put("Late",           Integer.toString(late));
            trimmedServiceMap.put("OnTime",         (String) fullIndividualServiceMap.get("OnTime"));
            trimmedServiceMap.put("Total",          (String) fullIndividualServiceMap.get("Total"));
            trimmedServiceMap.put("PPM",            (Map)    fullIndividualServiceMap.get("PPM"));
            trimmedServiceMap.put("RollingPPM",     (Map)    fullIndividualServiceMap.get("RollingPPM"));
            trimmedServiceMap.put("Timeband",       (String) fullIndividualServiceMap.getOrDefault("timeband", keySymbol.replace("*", "10").replace("^", "5")));
            trimmedServiceMap.put("SectorCode",     (String) fullIndividualServiceMap.get("sectorCode"));

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
                        try { sb.append(lengthen((map.get("Total").equals("0") ? "0" : (100 * ((Integer) Integer.parseInt((String) map.get("OnTime")) / Integer.parseInt((String) map.get("Total"))))) + "?, ", 6)); }
                        catch (NumberFormatException | ArithmeticException e) { sb.append("N/A,  ");  }
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
                else if (!map.get("Total").equals("0"))
                {
                    try { sb.append(lengthen((100 * ((Integer) Integer.parseInt((String) map.get("OnTime")) / Integer.parseInt((String) map.get("Total")))) + "?, ", 6)); }
                    catch (NumberFormatException | ArithmeticException e) { sb.append("N/A,  ");  }
                }
                else
                    sb.append("0?,   ");

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

                Map<String, Object> map = serviceMap.get(key);
                if (map != null)
                {
                    sb.append("<td class=\"ppmTable\">").append(key.replace("&", "&amp;")).append("</td>");

                    String ppm = String.valueOf(((Map) map.get("PPM")).get("text"));
                    if (!ppm.equals("-1"))
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("PPM")).get("rag")))).append("\">").append(ppm).append("%</td>");
                    else
                    {
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("PPM")).get("rag")))).append("\">");
                        try { sb.append("<abbr title=\"guess\">").append(100 * ((Integer) Integer.parseInt((String) map.get("OnTime")) / Integer.parseInt((String) map.get("Total")))).append("%</abbr>"); }
                        catch (NumberFormatException | ArithmeticException e) { sb.append("N/A"); }
                        sb.append("</td>");
                    }

                    sb.append("<td class=\"ppmTable\">").append(map.get("Total")).append("</td>");
                    sb.append("<td class=\"ppmTable\">").append(map.get("OnTime")).append("</td>");
                    sb.append("<td class=\"ppmTable\">").append(map.get("Late")).append("</td>");
                    sb.append("<td class=\"ppmTable\">").append(map.get("CancelVeryLate")).append("</td>");

                    String rollPPM = String.valueOf(((Map) map.get("RollingPPM")).get("text"));
                    if (!rollPPM.equals("-1"))
                    {
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("RollingPPM")).get("rag")))).append("\">").append(rollPPM).append("%").append("</td>");
                        sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd"))))).append("\">").append(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd")))).append("</td>");
                    }
                    else
                    {
                        sb.append("<td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                        sb.append("<td class=\"ppmTable\" style=\"color:black\">N/A</td>");
                    }

                }

                sb.append("</tr>");
            }

            Map<String, Object> map = serviceMap.get("Total");
            if (map != null)
            {
                sb.append("<tr>");

                sb.append("<td class=\"ppmTable\">Total</td>");
                sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("PPM")).get("rag")))).append("\">").append(((Map) map.get("PPM")).get("text")).append("%").append("</td>");

                sb.append("<td class=\"ppmTable\">").append(map.get("Total")).append("</td>");
                sb.append("<td class=\"ppmTable\">").append(map.get("OnTime")).append("</td>");
                sb.append("<td class=\"ppmTable\">").append(map.get("Late")).append("</td>");
                sb.append("<td class=\"ppmTable\">").append(map.get("CancelVeryLate")).append("</td>");

                String rollPPM = String.valueOf(((Map) map.get("RollingPPM")).get("text"));
                if (!rollPPM.equals("-1"))
                {
                    sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("RollingPPM")).get("rag")))).append("\">").append(rollPPM).append("%").append("</td>");
                    sb.append("<td class=\"ppmTable\" style=\"color:").append(getColour(String.valueOf(((Map) map.get("RollingPPM")).get("rag")))).append("\">").append(getTrendArrow(String.valueOf(((Map) map.get("RollingPPM")).get("trendInd")))).append("</td>");
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

        public Map<String, Object> getMap()
        {
            Map<String, Object> map = new HashMap<>();

            map.put("name",       NAME);
            map.put("code",       CODE);
            map.put("keySymbol",  keySymbol);
            map.put("serviceMap", serviceMap);

            return map;
        }

        public void readMap(Map<String, Object> map)
        {
            if (map.containsKey("keySymbol")  && map.get("keySymbol")  != null) keySymbol  = (String) map.get("keySymbol");
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

    /*public static enum OperatorType
    {
        LONG_DISTANCE  ("*", "Long distance"),
        SHORT_DISTANCE ("^", "Short distance"),
        MIXED          ("",  "Mixed distances");

        private final String keySymbol;
        private final String description;

        private OperatorType(String keySymbol, String description)
        {
            this.keySymbol   = keySymbol;
            this.description = description;
        }

        public String getKeySymbol() { return keySymbol; }
        public String getDescription() { return description; }

        public static OperatorType getType(Object type)
        {
            if (type instanceof OperatorType)
                return (OperatorType) type;
            else
                for (OperatorType typeEnum : values())
                    if (typeEnum.equals(type) || type.equals(typeEnum.getDescription()) || type.equals(typeEnum.getKeySymbol()))
                        return typeEnum;

            return null;
        }
    }

    public static enum SectorType
    {
        LSE ("LSE", "London and South East"),
        LD  ("LD",  "Long Distance"),
        REG ("REG", "Regional"),
        SCO ("SCO", "Scotland");

        private final String sectorCode;
        private final String description;

        private SectorType(String sectorCode, String description)
        {
            this.sectorCode  = sectorCode;
            this.description = description;
        }

        public String getSectorCode()  { return sectorCode; }
        public String getDescription() { return description; }

        public static SectorType getType(Object type)
        {
            if (type instanceof SectorType)
                return (SectorType) type;
            else
                for (SectorType typeEnum : values())
                    if (typeEnum.equals(type) || type.equals(typeEnum.getDescription()) || type.equals(typeEnum.getSectorCode()))
                        return typeEnum;

            return null;
        }
    }*/

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
                operators.put(pairs.getKey(), new Operator((String) pairs.getValue().get("name"), (int) ((long) pairs.getValue().get("code"))));

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