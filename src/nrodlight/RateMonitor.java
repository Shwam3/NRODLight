package nrodlight;

import nrodlight.db.DBHandler;
import nrodlight.db.Queries;
import org.json.JSONObject;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RateMonitor
{
    private final Map<String, AtomicLong> countMap = new HashMap<>();
    private final Map<String, List<Double>> averageMap = new HashMap<>();
    private final Map<String, JSONObject> stringMap = new HashMap<>();
    private final String[] columns = {"StompMessages", "TDStompDelay", "TDEventDelay", "TDMessages", "WSConnections",
            "TRUSTStompDelay", "TRUSTEventDelay", "TRUSTMessages", "VSTPStompDelay", "VSTPEventDelay", "VSTPMessages",
            "TRUSTActivations", "TRUSTCancellations", "TRUSTMovements", "TRUSTReinstatements", "TRUSTChOrigins",
            "TRUSTChIdents", "TRUSTChLocations", "InferredActivations", "TDHeartbeats", "VSTPCreates", "VSTPDeletes",
            "TDMessagesIndividual"};
    private final boolean[] isCount = {true, false, false, true, true, false, false, true, false, false, true, true,
            true, true, true, true, true, true, true, true, true, true, false};
    private final boolean[] isString = {false, false, false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false, true};

    private final String[] countCols = {"StompMessages", "TDMessages", "WSConnections", "TRUSTMessages", "VSTPMessages",
            "TRUSTActivations", "TRUSTCancellations", "TRUSTMovements", "TRUSTReinstatements", "TRUSTChOrigins",
            "TRUSTChIdents", "TRUSTChLocations", "InferredActivations", "TDHeartbeats", "VSTPCreates", "VSTPDeletes"};
    private final String[] averageCols = {"TDStompDelay", "TDEventDelay", "TRUSTStompDelay", "TRUSTEventDelay",
            "VSTPStompDelay", "VSTPEventDelay"};
    private final String[] stringCols = {"TDMessagesIndividual"};

    private static RateMonitor instance = null;
    private RateMonitor()
    {
        for (int i = 0; i < columns.length; i++)
        {
            if (isCount[i])
                countMap.put(columns[i], new AtomicLong());
            else if (isString[i])
                stringMap.put(columns[i], new JSONObject());
            else
                averageMap.put(columns[i], new ArrayList<>());
        }

        long currTime = System.currentTimeMillis();
        Calendar wait = Calendar.getInstance();
        wait.setTimeInMillis(currTime);
        wait.set(Calendar.MILLISECOND, 0);
        wait.set(Calendar.SECOND, 0);
        wait.add(Calendar.MINUTE, 1);

        NRODLight.getExecutor().scheduleAtFixedRate(() ->
        {
            try
            {
                try (PreparedStatement ps = DBHandler.getConnection().prepareStatement(Queries.RATE_MONITOR))
                {
                    ps.setLong(1, (System.currentTimeMillis() / 60000L) * 60L);

                    for (int i = 0; i < columns.length; i++)
                    {
                        if (isCount[i])
                        {
                            if (i == 4) // WSConnections
                                ps.setInt(2 + i, (int) countMap.get(columns[i]).getAndSet(NRODLight.webSocket != null ? NRODLight.webSocket.getConnectionCount() : 0));
                            else
                                ps.setInt(2 + i, (int) countMap.get(columns[i]).getAndSet(0));
                        }
                        else if (isString[i])
                        {
                            String value = stringMap.get(columns[i]).toString();
                            stringMap.get(columns[i]).clear();
                            ps.setString(2 + i, value);
                        }
                        else
                        {
                            List<Double> values = new ArrayList<>(averageMap.get(columns[i]));
                            averageMap.get(columns[i]).clear();
                            if (values.isEmpty())
                                ps.setNull(2 + i, Types.INTEGER);
                            else
                                ps.setInt(2 + i, (int) Math.floor(values.stream().mapToLong(v -> (long) (v * 1000)).average().orElse(0)));
                        }
                    }

                    ps.executeUpdate();
                }
            }
            catch(Exception e) { NRODLight.printThrowable(e, "RateMonitor");}
        }, wait.getTimeInMillis() - currTime, 1000 * 60, TimeUnit.MILLISECONDS);
    }

    public static RateMonitor getInstance()
    {
        if (instance == null)
            instance = new RateMonitor();

        return instance;
    }

    public void onWSOpen()
    {
        countMap.get("WSConnections").incrementAndGet();
    }

    public void onTDMessage(Double delayStomp, Double delayEvent, long msgCount, long heartbeats,
                            Map<String, Long> counts)
    {
        averageMap.get("TDStompDelay").add(delayStomp);
        averageMap.get("TDEventDelay").add(delayEvent);
        countMap.get("StompMessages").incrementAndGet();
        countMap.get("TDMessages").addAndGet(msgCount);
        countMap.get("TDHeartbeats").addAndGet(heartbeats);
        final JSONObject a = stringMap.get("TDMessagesIndividual");
        counts.forEach((k, v) -> {
            if (a.has(k))
                ((AtomicLong) a.get(k)).addAndGet(v);
            else
                a.put(k, new AtomicLong(v));
        });
    }

    public void onVSTPMessage(Double delayStomp, Double delayEvent, boolean isDelete)
    {
        averageMap.get("VSTPStompDelay").add(delayStomp);
        averageMap.get("VSTPEventDelay").add(delayEvent);
        countMap.get("StompMessages").incrementAndGet();
        countMap.get("VSTPMessages").incrementAndGet();
        countMap.get(isDelete ? "VSTPDeletes" : "VSTPCreates").incrementAndGet();
    }

    public void onTRUSTMessage(Double delayStomp, Double delayEvent, long acts, long cans, long moves,
                               long reins, long coo, long coi, long col, long inf)
    {
        averageMap.get("TRUSTStompDelay").add(delayStomp);
        averageMap.get("TRUSTEventDelay").add(delayEvent);
        countMap.get("StompMessages").incrementAndGet();
        countMap.get("TRUSTMessages").addAndGet(acts + cans + moves + reins + coo + coi + col);
        countMap.get("TRUSTActivations").addAndGet(acts);
        countMap.get("TRUSTCancellations").addAndGet(cans);
        countMap.get("TRUSTMovements").addAndGet(moves);
        countMap.get("TRUSTReinstatements").addAndGet(reins);
        countMap.get("TRUSTChOrigins").addAndGet(coo);
        countMap.get("TRUSTChIdents").addAndGet(coi);
        countMap.get("TRUSTChLocations").addAndGet(col);
        countMap.get("InferredActivations").addAndGet(inf);
    }
}
