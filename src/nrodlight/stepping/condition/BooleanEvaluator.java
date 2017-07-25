package nrodlight.stepping.condition;

import java.io.ByteArrayInputStream;

public class BooleanEvaluator {
	public static void _main(String[] args) throws InterruptedException {
		String expression = "";
		
        Lexer lexer = new Lexer(new ByteArrayInputStream(expression.getBytes()));
		RecursiveDescentParser parser = new RecursiveDescentParser(lexer);
	    BooleanExpression ast = parser.build();
		
        System.out.println(String.format("AST: %s", ast));
		System.out.println(String.format("RES: %s", ast.interpret()));
	}
}
