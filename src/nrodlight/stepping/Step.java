package nrodlight.stepping;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import nrodlight.NRODClient;
import nrodlight.stepping.condition.Lexer;
import nrodlight.stepping.condition.RecursiveDescentParser;
import nrodlight.stepping.condition.BooleanExpression;
import nrodlight.stomp.handlers.TDHandler;
import org.json.JSONObject;

public class Step
{
    private static List<Step> steps = new ArrayList<>();
    
    public void initialise()
    {
        File stepFile = new File(NRODClient.EASM_STORAGE_DIR, "stepping.json");
        if (stepFile.exists() && stepFile.canRead())
        {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(stepFile)))
            {
                String line;
                while ((line = br.readLine()) != null)
                    sb.append(line);
            }
            catch (IOException ex) { NRODClient.printThrowable(ex, "Stepping"); }
            
        }
    }
    
    private final boolean isCopy;
    private final String from;
    private final String to;
    private final String trigger;
    private final BooleanExpression condition;
    
    public Step(JSONObject object)
    {
        this.isCopy = object.optBoolean("isCopy", true);
        this.from = object.getString("from");
        this.to = object.getString("to");
        this.trigger = object.getString("trigger");
        this.condition = object.has("condition") ? 
            new RecursiveDescentParser(
                new Lexer(
                    new ByteArrayInputStream(object.getString("condition").getBytes())))
            .build() : null;
    }
    
    public String getTrigger()
    {
        return trigger;
    }
    
    public boolean checkCond()
    {
        return condition.interpret();
    }
    
    public void doStep()
    {
        String desc = TDHandler.DATA_MAP.getOrDefault(from, "****");
        if (!isCopy)
            TDHandler.DATA_MAP.put(from, "");
        TDHandler.DATA_MAP.put(to, desc);
    }
}
