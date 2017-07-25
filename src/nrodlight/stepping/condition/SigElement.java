package nrodlight.stepping.condition;

import nrodlight.stomp.handlers.TDHandler;

public class SigElement implements BooleanExpression
{
    private final String id;
    private final boolean isBerth;

    public SigElement(String id)
    {
        this.id = id;
        this.isBerth = id.charAt(0) == '$';
    }
    
    @Override
    public boolean interpret()
    {
        if (isBerth)
            return !TDHandler.DATA_MAP.getOrDefault(id.substring(1), "").equals("");
        else
            return TDHandler.DATA_MAP.getOrDefault(id, "0").equals("1");
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s)", id, interpret() ? "T" : "F");
    }
}
