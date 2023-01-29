package nrodlight.stepping;

public class Operators
{
    public static class And extends NonTerminal
    {
        public boolean evaluate()
        {
            return left.evaluate() && right.evaluate();
        }

        public String toString()
        {
            return String.format("(%s . %s)", left, right);
        }
    }

    public static class Or extends NonTerminal
    {
        public boolean evaluate()
        {
            return left.evaluate() || right.evaluate();
        }

        public String toString()
        {
            return String.format("(%s + %s)", left, right);
        }
    }

    public static class Not extends NonTerminal
    {
        public void setChild(BooleanExpression child)
        {
            setLeft(child);
        }

        public void setRight(BooleanExpression right)
        {
            throw new UnsupportedOperationException();
        }

        public boolean evaluate()
        {
            return !left.evaluate();
        }

        public String toString()
        {
            return String.format("!%s", left);
        }
    }

    public static final True TRUE = new True();
    public static class True extends Terminal
    {
        private True()
        {
            super("TRUE");
        }

        public boolean evaluate()
        {
            return true;
        }
    }

    public static final False FALSE = new False();
    public static class False extends Terminal
    {
        private False()
        {
            super("FALSE");
        }

        public boolean evaluate()
        {
            return false;
        }
    }
}
