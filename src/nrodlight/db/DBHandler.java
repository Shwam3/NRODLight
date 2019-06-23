package nrodlight.db;

import nrodlight.NRODLight;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBHandler
{
    private static Connection conn = null;

    public static Connection getConnection() throws SQLException
    {
        if (conn == null || !conn.isValid(0))
        {
            if (conn != null)
                conn.close();
            conn = DriverManager.getConnection("jdbc:mariadb://localhost:3306/sigmaps?autoReconnect=true",
                    NRODLight.config.getString("DBUser"), NRODLight.config.getString("DBPassword"));
        }

        return conn;
    }

    public static void closeConnection()
    {
        try
        {
            if (conn != null)
                conn.close();
        }
        catch (SQLException ex)
        {
            NRODLight.printThrowable(ex, "DBHandler");
        }
        conn = null;
    }
}
