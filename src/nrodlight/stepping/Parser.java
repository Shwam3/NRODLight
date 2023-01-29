package nrodlight.stepping;

public class Parser
{
    private Lexer lexer;
    private BooleanExpression root;

    public Parser(String condition)
    {
        this.lexer = new Lexer(condition);
    }

    public BooleanExpression build()
    {
        if (root != null)
            return root;

        expression();

        lexer = null;
        return root;
    }

    private void expression()
    {
        term();
        while (lexer.getSymbol() == Lexer.OR)
        {
            Operators.Or or = new Operators.Or();
            or.setLeft(root);
            term();
            or.setRight(root);
            root = or;
        }
    }

    private void term()
    {
        factor();
        while (lexer.getSymbol() == Lexer.AND)
        {
            Operators.And and = new Operators.And();
            and.setLeft(root);
            factor();
            and.setRight(root);
            root = and;
        }
    }

    private void factor()
    {
        int symbol = lexer.nextSymbol();
        if (symbol == Lexer.TRUE)
        {
            root = Operators.TRUE;
            lexer.nextSymbol();
        }
        else if (symbol == Lexer.FALSE)
        {
            root = Operators.FALSE;
            lexer.nextSymbol();
        }
        else if (symbol == Lexer.WORD)
        {
            root = new Word(lexer.getWord());
            lexer.nextSymbol();
        }
        else if (symbol == Lexer.NOT)
        {
            Operators.Not not = new Operators.Not();
            factor();
            not.setChild(root);
            root = not;
        }
        else if (symbol == Lexer.LEFT)
        {
            expression();
            lexer.nextSymbol(); // we don't care about ')'
        }
        else
        {
            throw new RuntimeException("Expression Malformed");
        }
    }
}
