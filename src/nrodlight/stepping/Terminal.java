package nrodlight.stepping;

public abstract class Terminal implements BooleanExpression
{
    protected final String value;

    public Terminal(String value)
    {
        this.value = value;
    }

    public String toString()
    {
        return String.format("%s=%s", value, evaluate());
    }
}
