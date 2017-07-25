package nrodlight.stepping.condition;

import java.io.*;

public class Lexer
{
    private StreamTokenizer input;

    private int symbol = NONE;
    public static final int EOL = -3;
    public static final int EOF = -2;
    public static final int INVALID = -1;

    public static final int NONE = 0;

    public static final int OR = 1;
    public static final int AND = 2;
    public static final int NOT = 3;

  //public static final int TRUE = 4;
  //public static final int FALSE = 5;
    public static final int SIG_ELEM = 10;

    public static final int LEFT = 6;
    public static final int RIGHT = 7;

    public static final String TRUE_LITERAL = "true";
    public static final String FALSE_LITERAL = "false";
    public static       SigElement lastSigElem = null;

    public Lexer(InputStream in)
    {
        Reader r = new BufferedReader(new InputStreamReader(in));
        input = new StreamTokenizer(r);

        input.resetSyntax();
        input.wordChars('a', 'z');
        input.wordChars('A', 'Z');
        input.wordChars('$', '$');
        input.wordChars('0', ':'); // 0-9 + :
        input.whitespaceChars('\u0000', ' ');
      //input.whitespaceChars('\n', '\t');

        input.ordinaryChar('[');
        input.ordinaryChar(']');
        input.ordinaryChar('.');
        input.ordinaryChar('+');
        input.ordinaryChar('-');
    }

    public int nextSymbol()
    {
        try
        {
            switch (input.nextToken())
            {
                case StreamTokenizer.TT_EOL:
                    symbol = EOL;
                    break;
                case StreamTokenizer.TT_EOF:
                    symbol = EOF;
                    break;
                case StreamTokenizer.TT_WORD:
                {
                    if ("(\\$[A-Z0-9]{6}|[A-Z0-9]{2}[0-9A-F]{2}:[0-8])".matches(input.sval))
                    {
                        symbol = SIG_ELEM;
                        lastSigElem = new SigElement(input.sval);
                    }
                    break;
                }
                case '[':
                    symbol = LEFT;
                    break;
                case ']':
                    symbol = RIGHT;
                    break;
                case '.':
                    symbol = AND;
                    break;
                case '+':
                    symbol = OR;
                    break;
                case '-':
                    symbol = NOT;
                    break;
                default:
                    symbol = INVALID;
            }
        }
        catch (IOException e)
        {
            symbol = EOF;
        }

        return symbol;
    }

    public String toString()
    {
        return input.toString();
    }
}
