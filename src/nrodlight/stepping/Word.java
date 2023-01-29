package nrodlight.stepping;

import nrodlight.stomp.handlers.TDHandler;

public class Word extends Terminal
{
    private final boolean isExpression;

    public Word(String value)
    {
        super(value);

        if (value.contains("="))
        {
            String[] values = value.split("=");
            if (values.length != 2 || values[0].length() != 6 || values[1].length() != 6)
                throw new IllegalArgumentException("'" + value + "' is not a valid Word expression");
            isExpression = true;
        }
        else if (value.length() != 6)
            throw new IllegalArgumentException("'" + value + "' is not a valid Word");
        else
            isExpression = false;
    }

    @Override
    public boolean evaluate()
    {
        if (isExpression)
        {
            String[] values = value.split("=");
            return evaluateOne(values[0]).equals(evaluateOne(values[1]));
        }
        else
        {
            String state = TDHandler.DATA_MAP.get(value);
            if (state == null)
                return false;
            else if ("0".equals(state))
                return false;
            else if ("1".equals(state))
                return true;
            else
                return !state.isEmpty();
        }
    }

    private String evaluateOne(String value)
    {
        if (value.charAt(0) == '\'' && value.charAt(5) == '\'')
            return value.substring(1, 5);

        String state = TDHandler.DATA_MAP.get(value);
        if (state == null)
            return "0";
        else if ("0".equals(state))
            return "0";
        else if ("1".equals(state))
            return "1";
        else
            return state;
    }
}
