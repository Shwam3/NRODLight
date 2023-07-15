package nrodlight.stepping;

import nrodlight.NRODLight;
import nrodlight.stomp.handlers.TDHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class Stepping
{
    private static Map<String, List<JSONObject>> steps = Collections.emptyMap();

    public static void load()
    {
        try (BufferedReader br = new BufferedReader(new FileReader(new File(NRODLight.EASM_STORAGE_DIR, "steps.json"))))
        {
            JSONArray newSteps = new JSONArray(new JSONTokener(br));
            Map<String, List<JSONObject>> newStepsFlat = new HashMap<>();
            int count = 0;

            for (Object sObj : newSteps)
            {
                if (sObj instanceof JSONObject)
                {
                    JSONObject s = (JSONObject) sObj;

                    if (s.has("on") && s.get("on") instanceof String
                            && s.has("if") && s.get("if") instanceof String
                            && s.has("then") && s.get("then") instanceof String)
                    {
                        List<JSONObject> arr = newStepsFlat.getOrDefault(s.getString("on"), new ArrayList<>());
                        newStepsFlat.putIfAbsent(s.getString("on"), arr);
                        arr.add(s);
                        count++;
                    }
                }
            }

            steps = newStepsFlat;
            NRODLight.printOut("[Stepping] Loaded " + count + " stepping rule" + (count == 1 ? "" : "s"), true);
        }
        catch (IOException e) { NRODLight.printErr("[Stepping] Could not read steps.json: " + e); }
        catch (JSONException e) { NRODLight.printErr("[Stepping] Could not parse steps.json: " + e); }
    }

    public static Map<String, String> processEvent(JSONObject event)
    {
        Map<String, String> updateMap = new HashMap<>();

        List<JSONObject> triggeredSteps = steps.getOrDefault(event.getString("event"), Collections.emptyList());
        if (!triggeredSteps.isEmpty())
            triggeredSteps.stream().filter(Stepping::cond).forEach(s -> step(s, event, updateMap));

        return updateMap;
    }

    private static boolean cond(JSONObject step)
    {
        String cond = step.getString("if");
        return Condition.parse(cond);
    }

    private static void step(JSONObject step, JSONObject event, Map<String, String> updateMap)
    {
        String[] instructions = step.getString("then").split(" ");
        final long time = event.getLong("time") + 1;

        switch (instructions[0])
        {
            case "STEP":
                if (instructions.length >= 3 && instructions[1].length() == 6 && instructions[2].length() == 6)
                {
                    final String from = instructions[1];
                    final String to = instructions[2];
                    final String descr = TDHandler.DATA_MAP.getOrDefault(from, "");
                    final String oldVal = TDHandler.DATA_MAP.getOrDefault(to, "");

                    updateMap.put(from, "");
                    updateMap.put(to, descr);

                    TDHandler.printTD(String.format("CA %s %s %s%s", descr, from, to, oldVal.isEmpty() ? "" : (" " + oldVal)), time);
                }
                else
                    NRODLight.printErr("[Stepping] Failed to carry out step: " + step);
                break;

            case "CANCEL":
                if (instructions.length >= 2 && instructions[1].length() == 6)
                {
                    final String from = instructions[1];
                    final String oldVal = TDHandler.DATA_MAP.getOrDefault(from, "");

                    updateMap.put(from, "");

                    if (!oldVal.isEmpty())
                        TDHandler.printTD(String.format("CB %s %s", oldVal, from), time);
                }
                else
                    NRODLight.printErr("[Stepping] Failed to carry out step: " + step);
                break;

            case "INTERPOSE":
                if (instructions.length >= 3 && instructions[1].length() == 6 && instructions[2].length() == 4)
                {
                    final String to = instructions[1];
                    final String descr = instructions[2];
                    final String oldVal = TDHandler.DATA_MAP.getOrDefault(to, "");

                    updateMap.put(instructions[1], instructions[2]);

                    TDHandler.printTD(String.format("CC %s %s%s", descr, to, oldVal.isEmpty() ? "" : (" " + oldVal)), time);
                }
                else
                    NRODLight.printErr("[Stepping] Failed to carry out step: " + step);
                break;

            case "COPY":
                if (instructions.length >= 2 && event.getString("event").charAt(0) == 'C' && instructions[1].length() == 6)
                {
                    final String to = instructions[1];
                    final String descr = event.optString("descr");
                    final String oldVal = TDHandler.DATA_MAP.getOrDefault(to, "");

                    updateMap.put(to, descr);

                    TDHandler.printTD(String.format("CC %s %s%s", descr, to, oldVal.isEmpty() ? "" : (" " + oldVal)), time);
                }
                else
                    NRODLight.printErr("[Stepping] Failed to carry out step: " + step);
                break;

            case "SET":
            case "UNSET":
                if (instructions.length >= 2 && instructions[1].length() == 6)
                {
                    final String newVal = "SET".equals(instructions[0]) ? "1" : "0";
                    final String address = instructions[1];
                    final String oldVal = TDHandler.DATA_MAP.getOrDefault(address, "0");

                    updateMap.put(address, newVal);

                    if (!newVal.equals(oldVal))
                        TDHandler.printTD(String.format("SF %s %s %s", address, oldVal, newVal), time);
                }
                else
                    NRODLight.printErr("[Stepping] Failed to carry out step: " + step);
                break;

            case "PRECOPY": // todo maybe: copies desc before interpose,
            default:
                NRODLight.printErr("[Stepping] Step not supported: " + step);
                break;
        }
    }
}
