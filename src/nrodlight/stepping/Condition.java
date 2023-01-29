package nrodlight.stepping;

import nrodlight.NRODLight;

import java.util.HashMap;
import java.util.Map;

public class Condition
{
    private static final Condition TRUE = new Condition("TRUE");
    private static final Condition FALSE = new Condition("FALSE");
    private static final Map<String, Condition> cache = new HashMap<>();
    static { cache.put("", TRUE); }

    public static boolean parse(String condStr)
    {
        condStr = condStr.trim();

        if (cache.containsKey(condStr))
            return cache.get(condStr).evaluate();

        try
        {
            Condition cond = new Condition(condStr);
            cache.put(condStr, cond);
            return cond.evaluate();
        }
        catch (Exception e)
        {
            NRODLight.printErr("[Stepping] Invalid expression '" + condStr + "', assuming always false");
            NRODLight.printThrowable(e, "Stepping");

            cache.put(condStr, FALSE);
            return false;
        }
    }

    private final BooleanExpression expression;

    private Condition(String expression)
    {
        this.expression = new Parser(expression).build();
    }

    private boolean evaluate()
    {
        return expression.evaluate();
    }

    @Override
    public String toString()
    {
        return expression.toString();
    }
}
