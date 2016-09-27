package nrodclient.stomp.handlers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import nrodclient.NRODClient;
import nrodclient.stomp.NRODListener;
import nrodclient.stomp.StompConnectionHandler;
import org.json.JSONArray;
import org.json.JSONObject;

public class MVTHandler implements NRODListener
{
    private static PrintWriter logStream;
    private static File        logFile;
    private static String      lastLogDate = "";
    private long lastMessageTime = 0;
    private final Map<String, String> da;

    private static NRODListener instance = null;
    private MVTHandler()
    {
        Date logDate = new Date(System.currentTimeMillis());
        logFile = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "Movement" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
        logFile.getParentFile().mkdirs();
        lastLogDate = NRODClient.sdfDate.format(logDate);
        
        da = new HashMap<>();
        //<editor-fold defaultstate="collapsed" desc="da map">
        da.put("AA","ACCEPTANCE");
        da.put("AC","TRAIN PREP");
        da.put("AD","WTG STAFF");
        da.put("AE","CONGESTION");
        da.put("AG","LOAD INCDT");
        da.put("AH","BREAKDOWN");
        da.put("AJ","TRAFFIC");
        da.put("AK","INF FIRE");
        da.put("AX","FOC INFRA");
        da.put("AZ","FTO OTHER");
        da.put("FA","DGI INCDT");
        da.put("FC","FCDRIVER");
        da.put("FE","NO T/CREW");
        da.put("FG","PRO DVR");
        da.put("FH","DIAG ERROR");
        da.put("FI","ETCS INPUT");
        da.put("FJ","RETIME REQ");
        da.put("FK","DIVERT REQ");
        da.put("FL","CANCEL REQ");
        da.put("FM","TAIL LAMP");
        da.put("FN","LATE CHUNL");
        da.put("FO","FOC UNEX");
        da.put("FP","FTO MISRTE");
        da.put("FS","ETCS OVRD");
        da.put("FT","LF NEUTRAL");
        da.put("FU","JOINT INQ");
        da.put("FW","LATE START");
        da.put("FX","LOW CLASS");
        da.put("FZ","FOC OTHER");
        da.put("I0","RADIO FLR");
        da.put("I1","OHL/3 RAIL");
        da.put("I2","AC/DC TRIP");
        da.put("I3","ON OHL");
        da.put("I4","SUPPLY FLR");
        da.put("I5","OVERRUN");
        da.put("I6","TRK PATROL");
        da.put("I7","ENGNRS TRN");
        da.put("I8","ANIMAL");
        da.put("I9","NR FIRE");
        da.put("IA","SIGNAL FLR");
        da.put("IB","POINTS FLR");
        da.put("IC","TC FAILURE");
        da.put("ID","LEVEL XING");
        da.put("IE","SIG FUNC PWR");
        da.put("IF","PANEL/TDM/FLR");
        da.put("IG","BLOCK FLR");
        da.put("IH","PWR SUP DIS");
        da.put("II","SIG CABL FLR");
        da.put("IJ","AWS/ATP");
        da.put("IK","PHONE/SPT");
        da.put("IL","TOKEN FLR");
        da.put("IM","BALISE");
        da.put("IN","HABD FAULT");
        da.put("IP","PNT HEATER");
        da.put("IQ","TRACK SIGN");
        da.put("IR","RAIL FLAW");
        da.put("IS","TRACK FLT");
        da.put("IT","BUMP RPRTD");
        da.put("IV","EARTHSLIP");
        da.put("IW","COLD");
        da.put("IZ","INF OTHER");
        da.put("J0","GSM-R FLR");
        da.put("J2","TRTS FLR");
        da.put("J3","AXLE FLR");
        da.put("J4","INF NFF");
        da.put("J6","LIGHTNING");
        da.put("J7","ETCS FLR");
        da.put("J8","ONTRK DMG");
        da.put("J9","RCM ALERT");
        da.put("JA","TSR O-ROTR");
        da.put("JB","PLND TSR");
        da.put("JD","STRUCTURES");
        da.put("JG","ESR/TSR");
        da.put("JH","HEAT SPEED");
        da.put("JK","FLOODING");
        da.put("JL","STAFF");
        da.put("JP","VEG STD");
        da.put("JS","COTTSR ORR");
        da.put("JT","NO PNT HTR");
        da.put("JX","MISC OBS");
        da.put("M0","CAB SYS");
        da.put("M1","PANTO/SHOE");
        da.put("M2","ADD");
        da.put("M7","DOORS");
        da.put("M8","ABOVE SB");
        da.put("M9","NFF");
        da.put("MB","ELEC LOCO");
        da.put("MC","DIESL LOCO");
        da.put("MD","BELOW SB");
        da.put("ME","STEAM LOCO");
        da.put("MF","CHUNL LOCO");
        da.put("ML","WAGONS");
        da.put("MN","BRAKES");
        da.put("MP","ADHESION");
        da.put("MR","WHEELS");
        da.put("MS","ALLOC STCK");
        da.put("MT","SS TB");
        da.put("MU","DEPOT");
        da.put("MV","ON-TRACK");
        da.put("MW","WEATHER");
        da.put("MY","COUPLER");
        da.put("NA","TASS/TILT");
        da.put("OB","REGULATION");
        da.put("OC","SIGNALLER");
        da.put("OD","NR CONTROL");
        da.put("OE","RHC PROG");
        da.put("OG","ICE");
        da.put("OH","ARS");
        da.put("OI","JOINT INQ");
        da.put("OJ","STN FIRE");
        da.put("OK","OPTG STAFF");
        da.put("OL","BOX CLOSED");
        da.put("OM","RHC FLR");
        da.put("ON","MIS-INVEST");
        da.put("OP","TRUST FLR");
        da.put("OQ","SIMP ERR");
        da.put("OS","RHC LATE");
        da.put("OU","UN-INVEST");
        da.put("OV","NR FIRE");
        da.put("OW","FOC CONN");
        da.put("OZ","OPTG OTHER");
        da.put("PA","PLANND TSR");
        da.put("PB","PLANND COT");
        da.put("PD","TPS CANC");
        da.put("PE","ENGNRG WRK");
        da.put("PF","DIVRSN/SLW");
        da.put("PG","PLAND CANC");
        da.put("PJ","DUPLICATE");
        da.put("PL","AGREED EXC");
        da.put("PN","VSTP DELAY");
        da.put("PT","OFFSET ERR");
        da.put("PZ","OTH EXC");
        da.put("QA","WTT SCHED");
        da.put("QB","DIVRSN/SLW");
        da.put("QH","LEAF SLIP");
        da.put("QI","RLHD CONT");
        da.put("QJ","LEAVES T/C");
        da.put("QM","STP SCHED");
        da.put("QN","TSI SCHED");
        da.put("QP","PLND LOP");
        da.put("QT","TAKEBACK");
        da.put("R1","DISPATCH");
        da.put("R2","LATE TRTS");
        da.put("R3","STAFF MSN");
        da.put("R4","STAFF DUTY");
        da.put("R5","STAFF ERR");
        da.put("R6","UNSTAFFED");
        da.put("R7","SPORTS");
        da.put("RB","PASSENGERS");
        da.put("RC","DISAB 1");
        da.put("RD","ATT/DETACH");
        da.put("RE","LIFT/ESC");
        da.put("RH","FIRE ALARM");
        da.put("RI","UNAUTH CON");
        da.put("RJ","UNAUTH SSO");
        da.put("RK","AUTH CON");
        da.put("RL","AUTH SSO");
        da.put("RM","XTNL CONN");
        da.put("RN","PASS CONN");
        da.put("RO","PASS ILL");
        da.put("RP","PASS DROP");
        da.put("RQ","DISAB 2");
        da.put("RR","BIKE 1");
        da.put("RS","BIKE 2");
        da.put("RT","LUGGAGE 1");
        da.put("RU","LUGGAGE 2");
        da.put("RV","PASS INFO");
        da.put("RW","STN FLOOD");
        da.put("RY","STN MISHAP");
        da.put("RZ","STN OTHER");
        da.put("T1","DOO STN");
        da.put("T2","NONDOO STN");
        da.put("T3","XTNL CONN");
        da.put("T4","SUPPLIES");
        da.put("TA","DIAG ERROR");
        da.put("TB","TOC REQST");
        da.put("TC","CREW USED");
        da.put("TD","STOCK USED");
        da.put("TE","PASS INJRY");
        da.put("TF","SEAT RESVN");
        da.put("TG","DRIVER");
        da.put("TH","(SNR) COND");
        da.put("TI","ROSTERING");
        da.put("TJ","TAIL LAMP");
        da.put("TK","CATERING");
        da.put("TL","DOOR OPEN");
        da.put("TM","AUTH CONN");
        da.put("TN","LATE CHUNL");
        da.put("TO","TOC UNEX");
        da.put("TP","AUTH SSO");
        da.put("TR","TOC DIRECT");
        da.put("TS","ETCS OVRD");
        da.put("TT","LF NEUTRAL");
        da.put("TU","JOINT INQ");
        da.put("TW","PRO DVR");
        da.put("TX","LUL CAUSES");
        da.put("TY","TOC MISHAP");
        da.put("TZ","TOC OTHER");
        da.put("V8","OTH BIRDS");
        da.put("VA","DISORDER");
        da.put("VB","VANDALS");
        da.put("VC","FATALITIES");
        da.put("VD","ILL PASS");
        da.put("VE","TICKET IRR");
        da.put("VF","VDL FIRE");
        da.put("VG","POLICE-TRN");
        da.put("VH","COM CORD");
        da.put("VI","SEC ALERT");
        da.put("VR","PRO DVR");
        da.put("VW","WEATHER");
        da.put("VX","LUL CAUSES");
        da.put("VZ","EXT OTHER");
        da.put("X1","SPL REGS");
        da.put("X2","SEV FLOOD");
        da.put("X3","LGHTNG");
        da.put("X4","BLNK REST");
        da.put("X8","EXT ANIMAL");
        da.put("X9","SEV SNOW");
        da.put("XA","TRESPASS");
        da.put("XB","VANDALS");
        da.put("XC","FATALITIES");
        da.put("XD","XING INCDT");
        da.put("XF","POLICE-RLY");
        da.put("XH","SEV HEAT");
        da.put("XI","SEC ALERT");
        da.put("XK","EXTL POWER");
        da.put("XL","EXTL FIRES");
        da.put("XM","GAS/WATER");
        da.put("XN","ROAD INCDT");
        da.put("XO","EXT OBJECT");
        da.put("XP","BDG STRIKE");
        da.put("XQ","BDGE OPEN");
        da.put("XR","CABLE CUT");
        da.put("XT","SEV COLD");
        da.put("XU","SUN OBSCUR");
        da.put("XV","NR FIRE");
        da.put("XW","WIND");
        da.put("XZ","EXT OTHER");
        da.put("YA","REG-ONTIME");
        da.put("YB","REG-LATE");
        da.put("YC","FOL-ONTIME");
        da.put("YD","FOL-LATE");
        da.put("YE","TO S/LINE");
        da.put("YG","REG INSTRC");
        da.put("YI","INWD STOCK");
        da.put("YJ","INWD CREW");
        da.put("YK","CNNCTN TFC");
        da.put("YL","AUTHSD CON");
        da.put("YM","AUTHSD SSO");
        da.put("YN","FIND CREW");
        da.put("YO","PLATFORM");
        da.put("YP","DIVERSION");
        da.put("YQ","SHRT FRMD");
        da.put("YR","SR CNCL");
        da.put("YT","NON NR INF");
        da.put("YX","OVER CRWD");
        da.put("ZW","UNATR CAN");
        da.put("ZX","UNEX L/S");
        da.put("ZY","UNEX O/T");
        da.put("ZZ","UNEX L/R");
        //</editor-fold>

        try { logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true); }
        catch (IOException e) { NRODClient.printThrowable(e, "Movement"); }

        lastMessageTime = System.currentTimeMillis();
    }

    public static NRODListener getInstance()
    {
        if (instance == null)
            instance = new MVTHandler();

        return instance;
    }

    @Override
    public void message(Map<String, String> headers, String message)
    {
        StompConnectionHandler.printStompHeaders(headers);

        JSONArray messageList = new JSONArray(message);

        for (Object msgObj : messageList)
        {
            JSONObject map = (JSONObject) msgObj;
            JSONObject header = map.getJSONObject("header");
            JSONObject body   = map.getJSONObject("body");
            
            switch (header.getString("msg_type"))
            {
                case "0001": // Activation
                    printMovement(String.format("Train %s / %s (%s) activated %s at %s (%s schedule from %s, Starts at %s, sch id: %s, TOPS address: %s)",
                            body.getString("train_id"),
                            body.getString("train_uid").replace(" ", "O"),
                            body.getString("train_id").substring(2, 6),
                            body.getString("train_call_type").replace("AUTOMATIC", "automatically").replace("MANUAL", "manually"),
                            NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("creation_timestamp")))),
                            body.getString("schedule_type").replace("P", "Planned").replace("O", "Overlayed").replace("N", "Short Term").replace("C", "Cancelled"),
                            body.getString("schedule_source").replace("C", "CIF/ITPS").replace("V", "VSTP/TOPS"),
                            body.getString("origin_dep_timestamp"),
                            body.getString("schedule_wtt_id"),
                            body.optString("train_file_address", "N/A")
                        ), false);
                    break;

                case "0002": // Cancellation
                    printMovement(String.format("Train %s / %s (%s) cancelled %s at %s due to %s (%s) (toc: %s / %s, dep: %s @ %s, from: %s, at %s, tops file: %s)",
                            body.getString("train_id"),
                            body.getString("train_service_code"),
                            body.getString("train_id").substring(2, 6),
                            body.getString("canx_type").replace("ON CALL", "upon activation").replace("OUT OF PLAN", "spontaneously").toLowerCase(),
                            NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("canx_timestamp")))),
                            da.getOrDefault(body.getString("canx_reason_code"), "??"),
                            body.getString("canx_reason_code"),
                            body.getString("toc_id"),
                            body.getString("division_code"),
                            body.getString("orig_loc_stanox"),
                            body.getString("orig_loc_timestamp").isEmpty() ? "n/a" : NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("orig_loc_timestamp")))),
                            body.getString("loc_stanox"),
                            NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("dep_timestamp")))),
                            body.optString("train_file_address","N/A")
                        ), false);
                    break;

                case "0003": // Movement
                    printMovement(String.format("Train %s / %s (%s) %s %s %s%s at %s %s(plan %s, GBTT %s, plat: %s, line: %s, toc: %s / %s)",
                            body.getString("train_id"),
                            body.getString("train_service_code"),
                            body.getString("train_id").substring(2, 6),
                            body.getString("event_type").replace("ARRIVAL", "arrived at").replace("DEPARTURE", "departed from"),
                            body.getString("loc_stanox"),
                            body.getString("timetable_variation").equals("0") ? "" : body.getString("timetable_variation") + " mins ",
                            body.getString("variation_status").toLowerCase(),
                            body.getString("actual_timestamp").isEmpty() ? "N/A" : NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("actual_timestamp")))),
                            body.getString("train_terminated").replace("true", "and terminated ").replace("false", ""),
                            body.getString("planned_timestamp").isEmpty() ? "N/A" : NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("planned_timestamp")))),
                            body.getString("gbtt_timestamp").isEmpty() ? "N/A" : NRODClient.sdfTime.format(new Date(Long.parseLong(body.getString("gbtt_timestamp")))),
                            body.getString("platform").trim().isEmpty() ? "  " : body.getString("platform").trim(),
                            body.getString("line_ind").trim().isEmpty() ? "" : body.getString("line_ind").trim(),
                            body.getString("toc_id"),
                            body.getString("division_code")
                            ), false);
                    break;

                case "0005": // Reinstatement (De-Cancellation)
                    printMovement("Reinstatement: " + body.toString(), false);
                    break;

                case "0006": // Change Origin
                    printMovement("Origin Change: " + body.toString(), false);
                    break;

                case "0007": // Change Identity
                    printMovement("Identity Change: " + body.toString(), false);
                    break;

                case "0004": // UID Train
                case "0008": // Change Loaction
                default:     // Other
                    printMovement("Erronous message received (" + header.getString("msg_type") + ")", true);
                    break;
            }

        }

        lastMessageTime = System.currentTimeMillis();
        StompConnectionHandler.lastMessageTimeGeneral = lastMessageTime;
        StompConnectionHandler.ack(headers.get("ack"));
    }

    public long getTimeout() { return System.currentTimeMillis() - lastMessageTime; }
    public long getTimeoutThreshold() { return 30000; }

    private static synchronized void printMovement(String message, boolean toErr)
    {
        if (NRODClient.verbose)
        {
            if (toErr)
                NRODClient.printErr("[Movement] ".concat(message));
            else
                NRODClient.printOut("[Movement] ".concat(message));
        }

        if (!lastLogDate.equals(NRODClient.sdfDate.format(new Date())))
        {
            logStream.close();

            Date logDate = new Date();
            lastLogDate = NRODClient.sdfDate.format(logDate);

            logFile = new File(NRODClient.EASM_STORAGE_DIR, "Logs" + File.separator + "Movement" + File.separator + NRODClient.sdfDate.format(logDate).replace("/", "-") + ".log");
            logFile.getParentFile().mkdirs();

            try
            {
                logFile.createNewFile();
                logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
            }
            catch (IOException e) { NRODClient.printThrowable(e, "Movement"); }
        }

        logStream.println("[".concat(NRODClient.sdfDateTime.format(new Date())).concat("] ").concat(toErr ? "!!!> " : "").concat(message).concat(toErr ? " <!!!" : ""));
    }
}