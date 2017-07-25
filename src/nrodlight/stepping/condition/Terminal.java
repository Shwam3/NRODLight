package nrodlight.stepping.condition;

public abstract class Terminal implements BooleanExpression
{
	protected boolean value;

	public Terminal(boolean value)
    {
		this.value = value;
	}

	public String toString()
    {
		return String.format("%s", value);
	}
}
