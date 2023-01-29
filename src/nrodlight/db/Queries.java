package nrodlight.db;

public class Queries
{
    public static final String TRUST_START_SMART = "SELECT l.tiploc,scheduled_arrival,scheduled_departure," +
            "scheduled_pass FROM schedule_locations l INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON " +
            "c.stanox=s.stanox WHERE schedule_key=? AND s.reports=1 ORDER BY loc_index ASC LIMIT 1";
    public static final String TRUST_START_ANY = "SELECT tiploc,scheduled_departure FROM schedule_locations WHERE " +
            "schedule_key=? AND loc_index=0";
    public static final String TRUST_1 = "INSERT INTO activations (train_id,train_id_current,start_timestamp," +
            "schedule_key,schedule_uid,schedule_date_from,schedule_date_to,stp_indicator,schedule_source," +
            "creation_timestamp,next_expected_update,next_expected_tiploc,last_update) VALUES (?,?,?,?,?,?,?,?,?,?,?," +
            "?,?) ON DUPLICATE KEY UPDATE next_expected_update=VALUE(next_expected_update),next_expected_tiploc=VALUE" +
            "(next_expected_tiploc),start_timestamp=VALUE(start_timestamp),last_update=VALUE(last_update),inferred=0";
    public static final String TRUST_UPDATE = "UPDATE activations SET cancelled=?, last_update=? WHERE train_id=?";
    public static final String TRUST_3_UPDATE = "UPDATE activations SET current_delay=?,last_update=?," +
            "last_update_tiploc=COALESCE((SELECT tiploc FROM corpus WHERE stanox=? AND ''!=? LIMIT 1),?)," +
            "next_expected_update=?,next_expected_tiploc=COALESCE((SELECT tiploc FROM corpus WHERE stanox=? AND ''!=?" +
            " LIMIT 1),?),finished=?,off_route=0 WHERE train_id=? AND (last_update<=? OR 'AUTOMATIC'=?)";
    public static final String TRUST_3_INSERT = "INSERT INTO trust_reports VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE " +
            "KEY UPDATE actual_time=VALUE(actual_time),source=VALUE(source),line=VALUE(line),platform=VALUE(platform)" +
            ",off_route=VALUE(off_route),train_terminated=VALUE(train_terminated)";
    public static final String TRUST_3_ARR = "SELECT l.tiploc,c.stanox,scheduled_arrival,scheduled_departure," +
            "scheduled_pass,s.max_offset FROM schedule_locations l INNER JOIN activations a ON l.schedule_key=a" +
            ".schedule_key INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE a" +
            ".train_id=? AND s.reports=1 AND loc_index>=(SELECT loc_index FROM schedule_locations l2 INNER JOIN " +
            "activations a2 ON l2.schedule_key=a2.schedule_key WHERE a2.train_id=? AND (l2.scheduled_arrival=? OR l2" +
            ".scheduled_pass=?) ORDER BY loc_index ASC LIMIT 1) ORDER BY loc_index ASC LIMIT 1";
    public static final String TRUST_3_DEP = "SELECT l.tiploc,c.stanox,scheduled_arrival,scheduled_departure," +
            "scheduled_pass,s.max_offset FROM schedule_locations l INNER JOIN activations a ON l.schedule_key=a" +
            ".schedule_key INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE a" +
            ".train_id=? AND s.reports=1 AND loc_index>(SELECT loc_index FROM schedule_locations l2 INNER JOIN " +
            "activations a2 ON l2.schedule_key=a2.schedule_key WHERE a2.train_id=? AND (l2.scheduled_departure=? OR " +
            "l2.scheduled_pass=?) ORDER BY loc_index ASC LIMIT 1) ORDER BY loc_index ASC LIMIT 1";
    public static final String TRUST_3_FIND_ACTIVATION = "SELECT train_id FROM activations WHERE train_id=?";
    public static final String TRUST_3_INFER_SCHEDULE = "SELECT l.schedule_key,date_to FROM schedule_locations l " +
            "INNER JOIN schedules s ON s.schedule_key=l.schedule_key INNER JOIN corpus c ON l.tiploc=c.tiploc WHERE c" +
            ".stanox=? AND (scheduled_arrival=? AND 1=? OR scheduled_departure=? AND 1=? OR scheduled_pass=?) AND " +
            "(SUBSTR(days_run, ?, 1)='1' OR SUBSTR(days_run, ?, 1)='1' AND over_midnight=1) AND (CAST(s.date_from AS " +
            "INT)<=? OR (CAST(s.date_from AS INT)<=? AND over_midnight=1)) AND (CAST(s.date_to AS INT)>=? OR (CAST(s" +
            ".date_to AS INT)>=? AND over_midnight=1)) AND (SELECT COUNT(schedule_key) FROM schedules x WHERE x" +
            ".schedule_uid=s.schedule_uid AND x.stp_indicator='C' AND (CAST(x.date_from AS INT)<=? OR (CAST(x" +
            ".date_from AS INT)<=? AND x.over_midnight=1)))=0 AND (s.identity = '' OR ?=0 OR s.identity=?) GROUP BY s" +
            ".schedule_uid ORDER BY s.schedule_source DESC, s.stp_indicator";
    public static final String TRUST_3_ADD_INFERRED = "INSERT INTO activations (train_id,train_id_current," +
            "start_timestamp,schedule_key,schedule_uid,schedule_date_from,schedule_date_to,stp_indicator," +
            "schedule_source,creation_timestamp,last_update,inferred) VALUES (?,?,?,?,?,?,?,?,?,?,?,1)";
    public static final String TRUST_OFFROUTE = "UPDATE activations SET off_route=1,last_update=?,finished=? WHERE " +
            "train_id=? AND last_update<=?";
    public static final String TRUST_6 = "UPDATE activations SET next_expected_update=?,next_expected_tiploc=COALESCE" +
            "((SELECT tiploc FROM corpus WHERE stanox=? AND ''!=? LIMIT 1),?),last_update=? WHERE train_id=? AND " +
            "next_expected_update<=? AND last_update<=?";
    public static final String TRUST_7 = "UPDATE activations SET train_id_current=?,last_update=? WHERE train_id=?";
    public static final String TRUST_8 = "UPDATE activations SET last_update=? WHERE train_id=?";

    public static final String VSTP_CREATE_TIME = "SELECT creation_timestamp FROM schedules WHERE schedule_key = ?";
    public static final String VSTP_DEL_LOCS = "DELETE FROM schedule_locations WHERE schedule_key = ?";
    public static final String VSTP_DEL_SCHED = "DELETE FROM schedules WHERE schedule_key = ?";
    public static final String VSTP_DEL_COR = "DELETE FROM change_en_route WHERE schedule_key = ?";
    public static final String VSTP_INSERT_SCHED = "INSERT INTO schedules VALUES (?,?,?,?,?,'V',?,?,?,?,?,?,?,?,?,?," +
            "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    public static final String VSTP_INSERT_LOCS = "INSERT INTO schedule_locations VALUES (?,?,?,?,'V',?,?,?,?,?,?,?," +
            "?,?,?,?,?,?,?,?)";

    public static final String DELAY_DATA = "SELECT a.train_id,a.train_id_current,a.schedule_uid,a.start_timestamp,a" +
            ".current_delay,a.next_expected_update,a.off_route,a.finished,a.last_update,GROUP_CONCAT(DISTINCT c.td " +
            "ORDER BY c.td SEPARATOR ',') AS tds,TRIM(GROUP_CONCAT(COALESCE(c.tps_name,l.tiploc) ORDER BY l.loc_index" +
            " SEPARATOR ',' LIMIT 1)) AS loc_origin,TRIM(GROUP_CONCAT(COALESCE(c.tps_name,l.tiploc) ORDER BY l" +
            ".loc_index DESC SEPARATOR ',' LIMIT 1)) AS loc_dest,GROUP_CONCAT(l.scheduled_departure ORDER BY l" +
            ".loc_index SEPARATOR ',' LIMIT 1) AS origin_dep,COALESCE(NULLIF(s.toc_code,''),NULLIF(t.toc_code,'')) AS" +
            " toc_code FROM activations a INNER JOIN schedule_locations l ON a.schedule_key=l.schedule_key LEFT JOIN " +
            "corpus c ON l.tiploc=c.tiploc INNER JOIN schedules s ON a.schedule_key=s.schedule_key LEFT JOIN " +
            "train_service_codes t ON s.service_code=t.code WHERE (a.last_update > (UNIX_TIMESTAMP() - 43200) * 1000)" +
            " AND (a.finished=0 OR a.last_update > (UNIX_TIMESTAMP() - 2700) * 1000) AND a.cancelled=0 GROUP BY a" +
            ".train_id HAVING tds IS NOT NULL";
    /*
    public static final String DELAY_DATA = "SELECT a.train_id,a.train_id_current,a.schedule_uid,a.start_timestamp,a" +
            ".current_delay,a.next_expected_update,a.off_route,a.finished,a.last_update,GROUP_CONCAT(DISTINCT c.td " +
            "ORDER BY c.td SEPARATOR ',') AS tds,TRIM(GROUP_CONCAT(COALESCE(c.tps_name,l.tiploc) ORDER BY l.loc_index" +
            " SEPARATOR ',' LIMIT 1)) AS loc_origin,TRIM(GROUP_CONCAT(COALESCE(c.tps_name,l.tiploc) ORDER BY l" +
            ".loc_index DESC SEPARATOR ',' LIMIT 1)) AS loc_dest,GROUP_CONCAT(l.scheduled_departure ORDER BY l" +
            ".loc_index SEPARATOR ',' LIMIT 1) AS origin_dep FROM activations a INNER JOIN schedule_locations l ON a" +
            ".schedule_key=l.schedule_key LEFT JOIN corpus c ON l.tiploc=c.tiploc WHERE (a.last_update > " +
            "(UNIX_TIMESTAMP() - 43200) * 1000) AND (a.finished=0 OR a.last_update > (UNIX_TIMESTAMP() - 2700) * " +
            "1000) AND a.cancelled=0 GROUP BY a.train_id HAVING tds IS NOT NULL";
    */

    public static final String RATE_MONITOR = "INSERT INTO ratemonitor VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?," +
            "?,?,?,?,?)";
}
