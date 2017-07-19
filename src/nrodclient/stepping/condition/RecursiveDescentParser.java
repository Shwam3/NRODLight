package nrodclient.stepping.condition;

public class RecursiveDescentParser
{
	private Lexer lexer;
	private int symbol;
	private BooleanExpression root;

	public RecursiveDescentParser(Lexer lexer)
    {
		this.lexer = lexer;
	}

	public BooleanExpression build()
    {
		expression();
		return root;
	}

	private void expression()
    {
		term();
		while (symbol == Lexer.OR)
        {
			Operator.Or or = new Operator.Or();
			or.setLeft(root);
			term();
			or.setRight(root);
			root = or;
		}
	}

	private void term()
    {
		factor();
		while (symbol == Lexer.AND)
        {
			Operator.And and = new Operator.And();
			and.setLeft(root);
			factor();
			and.setRight(root);
			root = and;
		}
	}

	private void factor()
    {
		symbol = lexer.nextSymbol();
		/*if (symbol == Lexer.TRUE)
        {
			root = t;
			symbol = lexer.nextSymbol();
		}
        else if (symbol == Lexer.FALSE)
        {
			root = f;
			symbol = lexer.nextSymbol();
		}
        else*/ if (symbol == Lexer.SIG_ELEM)
        {
            root = lexer.lastSigElem;
            lexer.lastSigElem = null;
        }
        else if (symbol == Lexer.NOT){
			Operator.Not not = new Operator.Not();
			factor();
			not.setChild(root);
			root = not;
		}
        else if (symbol == Lexer.LEFT)
        {
			expression();
			symbol = lexer.nextSymbol(); // we don't care about ')'
		}
        else
        {
			throw new RuntimeException("Expression Malformed");
		}
	}
}
