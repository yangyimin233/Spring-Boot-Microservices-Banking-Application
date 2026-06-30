-- ============================================
-- Bank Microservices - Init SQL
-- 数据库名必须和 application.yml 中的 url 一致
-- ============================================

CREATE DATABASE IF NOT EXISTS user_service;
CREATE DATABASE IF NOT EXISTS account_service;
CREATE DATABASE IF NOT EXISTS fund_transfer_service;
CREATE DATABASE IF NOT EXISTS transaction_service;
CREATE DATABASE IF NOT EXISTS sequence_generator;

-- ============================================
-- XXL-JOB 调度中心数据库
-- ============================================
CREATE DATABASE IF NOT EXISTS xxl_job;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_info (
    id                        int(11) NOT NULL AUTO_INCREMENT,
    job_group                 int(11) NOT NULL COMMENT '执行器主键ID',
    job_desc                  varchar(255) NOT NULL,
    add_time                  datetime DEFAULT NULL,
    update_time               datetime DEFAULT NULL,
    author                    varchar(64) DEFAULT NULL,
    alarm_email               varchar(255) DEFAULT NULL,
    schedule_type             varchar(50) NOT NULL DEFAULT 'NONE',
    schedule_conf             varchar(128) DEFAULT NULL,
    misfire_strategy          varchar(50) NOT NULL DEFAULT 'DO_NOTHING',
    executor_route_strategy   varchar(50) DEFAULT NULL,
    executor_handler          varchar(255) DEFAULT NULL,
    executor_param            varchar(512) DEFAULT NULL,
    executor_block_strategy   varchar(50) DEFAULT NULL,
    executor_timeout          int(11) NOT NULL DEFAULT '0',
    executor_fail_retry_count int(11) NOT NULL DEFAULT '0',
    glue_type                 varchar(50) NOT NULL,
    glue_source               mediumtext,
    glue_remark               varchar(128) DEFAULT NULL,
    glue_updatetime           datetime DEFAULT NULL,
    child_jobid               varchar(255) DEFAULT NULL,
    trigger_status            tinyint(4) NOT NULL DEFAULT '0',
    trigger_last_time         bigint(13) NOT NULL DEFAULT '0',
    trigger_next_time         bigint(13) NOT NULL DEFAULT '0',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_log (
    id                        bigint(20) NOT NULL AUTO_INCREMENT,
    job_group                 int(11) NOT NULL,
    job_id                    int(11) NOT NULL,
    executor_address          varchar(255) DEFAULT NULL,
    executor_handler          varchar(255) DEFAULT NULL,
    executor_param            varchar(512) DEFAULT NULL,
    executor_sharding_param   varchar(20) DEFAULT NULL,
    executor_fail_retry_count int(11) NOT NULL DEFAULT '0',
    trigger_time              datetime DEFAULT NULL,
    trigger_code              int(11) NOT NULL,
    trigger_msg               text,
    handle_time               datetime DEFAULT NULL,
    handle_code               int(11) NOT NULL,
    handle_msg                text,
    alarm_status              tinyint(4) NOT NULL DEFAULT '0',
    PRIMARY KEY (id),
    KEY idx_trigger_time (trigger_time),
    KEY idx_handle_code (handle_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_log_report (
    id            int(11) NOT NULL AUTO_INCREMENT,
    trigger_day   datetime DEFAULT NULL,
    running_count int(11) NOT NULL DEFAULT '0',
    suc_count     int(11) NOT NULL DEFAULT '0',
    fail_count    int(11) NOT NULL DEFAULT '0',
    update_time   datetime DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY i_trigger_day (trigger_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_logglue (
    id          int(11) NOT NULL AUTO_INCREMENT,
    job_id      int(11) NOT NULL COMMENT '任务ID',
    glue_type   varchar(50) DEFAULT NULL,
    glue_source mediumtext,
    glue_remark varchar(128) NOT NULL,
    add_time    datetime DEFAULT NULL,
    update_time datetime DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_registry (
    id            int(11) NOT NULL AUTO_INCREMENT,
    registry_group varchar(50) NOT NULL,
    registry_key  varchar(255) NOT NULL,
    registry_value varchar(255) NOT NULL,
    update_time   datetime DEFAULT NULL,
    PRIMARY KEY (id),
    KEY i_g_k_v (registry_group, registry_key, registry_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_group (
    id           int(11) NOT NULL AUTO_INCREMENT,
    app_name     varchar(64) NOT NULL,
    title        varchar(12) NOT NULL,
    address_type tinyint(4) NOT NULL DEFAULT '0',
    address_list text,
    update_time  datetime DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS xxl_job.xxl_job_user (
    id           int(11) NOT NULL AUTO_INCREMENT,
    username     varchar(50) NOT NULL,
    password     varchar(50) NOT NULL,
    role         tinyint(4) NOT NULL,
    permission   varchar(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY i_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO xxl_job.xxl_job_user (username, password, role, permission)
VALUES ('admin', '123456', 1, NULL)
ON DUPLICATE KEY UPDATE username=username;

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list)
VALUES ('transaction-service-executor', '交易服务执行器', 0, NULL)
ON DUPLICATE KEY UPDATE app_name=app_name;

GRANT ALL PRIVILEGES ON *.* TO 'root'@'%';
FLUSH PRIVILEGES;

-- ============================================
-- Seata AT 模式所需的 undo_log 表
-- 每个业务库都需要建一张
-- ============================================

-- user_service 库的 undo_log
CREATE TABLE IF NOT EXISTS user_service.undo_log (
    id            BIGINT(20)   NOT NULL AUTO_INCREMENT,
    branch_id     BIGINT(20)   NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB     NOT NULL,
    log_status    INT(11)      NOT NULL,
    log_created   DATETIME     NOT NULL,
    log_modified  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- account_service 库的 undo_log
CREATE TABLE IF NOT EXISTS account_service.undo_log (
    id            BIGINT(20)   NOT NULL AUTO_INCREMENT,
    branch_id     BIGINT(20)   NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB     NOT NULL,
    log_status    INT(11)      NOT NULL,
    log_created   DATETIME     NOT NULL,
    log_modified  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- fund_transfer_service 库的 undo_log
CREATE TABLE IF NOT EXISTS fund_transfer_service.undo_log (
    id            BIGINT(20)   NOT NULL AUTO_INCREMENT,
    branch_id     BIGINT(20)   NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB     NOT NULL,
    log_status    INT(11)      NOT NULL,
    log_created   DATETIME     NOT NULL,
    log_modified  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================================
-- transaction_service 复式记账表（JPA ddl-auto=update 自动创建，此处仅作参考）
-- ============================================
-- CREATE TABLE transaction_service.transaction (
--     id               BIGINT AUTO_INCREMENT PRIMARY KEY,
--     voucher_no       VARCHAR(32)  NOT NULL UNIQUE COMMENT '凭证号 TX20260612000001',
--     transaction_type VARCHAR(32)  NOT NULL COMMENT 'DEPOSIT/WITHDRAWAL/INTERNAL_TRANSFER',
--     reference_id     VARCHAR(64)  COMMENT '全局唯一业务ID',
--     status           VARCHAR(16)  NOT NULL COMMENT 'COMPLETED/PENDING',
--     created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP
-- );
-- CREATE TABLE transaction_service.journal_entry (
--     id               BIGINT AUTO_INCREMENT PRIMARY KEY,
--     transaction_id   BIGINT       NOT NULL COMMENT '凭证ID',
--     account_id       VARCHAR(32)  NOT NULL COMMENT '账户编号',
--     direction        VARCHAR(8)   NOT NULL COMMENT 'DEBIT/CREDIT',
--     amount           DECIMAL(19,2) NOT NULL,
--     description      VARCHAR(255)
-- );

-- transaction_service 库的 undo_log
CREATE TABLE IF NOT EXISTS transaction_service.undo_log (
    id            BIGINT(20)   NOT NULL AUTO_INCREMENT,
    branch_id     BIGINT(20)   NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB     NOT NULL,
    log_status    INT(11)      NOT NULL,
    log_created   DATETIME     NOT NULL,
    log_modified  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
