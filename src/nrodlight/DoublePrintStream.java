package nrodlight;

import java.io.PrintStream;

public class DoublePrintStream extends PrintStream
{
    private PrintStream fout;

    public DoublePrintStream(PrintStream stdout, PrintStream fout)
    {
        super(stdout);
        this.fout = fout;
    }

    @Override
    public void write(byte[] buf, int off, int len)
    {
        try
        {
            super.write(buf, off, len);
            fout.write(buf, off, len);
        }
        catch (Exception ignored) {}
    }

    @Override
    public void write(int b)
    {
        try
        {
            super.write(b);
            fout.write(b);
        }
        catch (Exception ignored) {}
    }

    @Override
    public void flush()
    {
        super.flush();
        fout.flush();
    }

    public PrintStream newFOut(PrintStream foutNew)
    {
        PrintStream foutOld = this.fout;
        this.fout = foutNew;
        return foutOld;
    }
}
