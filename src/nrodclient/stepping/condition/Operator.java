package nrodclient.stepping.condition;

public class Operator
{
    public static class And extends NonTerminal
    {
        public boolean interpret()
        {
            return left.interpret() && right.interpret();
        }

        public String toString()
        {
            return String.format("[%s . %s]", left, right);
        }
    }
    
    public static class Or extends NonTerminal
    {
        public boolean interpret()
        {
            return left.interpret() || right.interpret();
        }

        public String toString()
        {
            return String.format("[%s + %s]", left, right);
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

        public boolean interpret()
        {
            return !left.interpret();
        }

        public String toString()
        {
            return String.format("-%s", left);
        }
    }
}
