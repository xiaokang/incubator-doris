CREATE TABLE routine_load_uniq_tbl_basic
(
    k00 INT             NOT NULL,
    k01 DATE            NOT NULL,
    k02 BOOLEAN         NULL,
    k03 TINYINT         NULL,
    k04 SMALLINT        NULL,
    k05 INT             NULL,
    k06 BIGINT          NULL,
    k07 LARGEINT        NULL,
    k08 FLOAT           NULL,
    k09 DOUBLE          NULL,
    k10 DECIMAL(9,1)    NULL,
    k11 DECIMALV3(9,1)  NULL,
    k12 DATETIME        NULL,
    k13 DATEV2          NULL,
    k14 DATETIMEV2      NULL,
    k15 CHAR            NULL,
    k16 VARCHAR         NULL,
    k17 STRING          NULL,
    k18 JSON            NULL,
    kd01 BOOLEAN         NOT NULL DEFAULT "TRUE",
    kd02 TINYINT         NOT NULL DEFAULT "1",
    kd03 SMALLINT        NOT NULL DEFAULT "2",
    kd04 INT             NOT NULL DEFAULT "3",
    kd05 BIGINT          NOT NULL DEFAULT "4",
    kd06 LARGEINT        NOT NULL DEFAULT "5",
    kd07 FLOAT           NOT NULL DEFAULT "6.0",
    kd08 DOUBLE          NOT NULL DEFAULT "7.0",
    kd09 DECIMAL         NOT NULL DEFAULT "888888888",
    kd10 DECIMALV3       NOT NULL DEFAULT "999999999",
    kd11 DATE            NOT NULL DEFAULT "2023-08-24",
    kd12 DATETIME        NOT NULL DEFAULT "2023-08-24 12:00:00",
    kd13 DATEV2          NOT NULL DEFAULT "2023-08-24",
    kd14 DATETIMEV2      NOT NULL DEFAULT "2023-08-24 12:00:00",
    kd15 CHAR(255)       NOT NULL DEFAULT "我能吞下玻璃而不伤身体",
    kd16 VARCHAR(300)    NOT NULL DEFAULT "我能吞下玻璃而不伤身体",
    kd17 STRING          NOT NULL DEFAULT "我能吞下玻璃而不伤身体",
    kd18 JSON            NULL
)
UNIQUE KEY(k00,k01)
PARTITION BY RANGE(k01)
(
    PARTITION p1 VALUES [('2023-08-01'), ('2023-08-11')),
    PARTITION p2 VALUES [('2023-08-11'), ('2023-08-21')),
    PARTITION p3 VALUES [('2023-08-21'), ('2023-09-01'))
)
DISTRIBUTED BY HASH(k00) BUCKETS 32
PROPERTIES (
    "replication_num" = "1"
);