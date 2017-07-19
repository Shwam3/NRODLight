package nrodclient.stepping.condition;

import java.io.ByteArrayInputStream;

public class Condition
{
    private BooleanExpression condition;
    
    public Condition(String condition)
    {
	    this.condition =
            new RecursiveDescentParser(
                new Lexer(
                    new ByteArrayInputStream(condition.getBytes())))
            .build();
    }
    
    public boolean test()
    {
        return this.condition.interpret();
    }
}
