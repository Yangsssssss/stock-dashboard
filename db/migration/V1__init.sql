-- ============================================================
-- V1 基础表结构：标的字典 / 日线 / 行情快照 / 评分 / 自选
-- 字符集 utf8mb4，金额与价格统一 DECIMAL，避免浮点误差
-- ============================================================

CREATE TABLE IF NOT EXISTS instrument (
  code        VARCHAR(16)  NOT NULL COMMENT '腾讯风格代码，如 sh600519 / hk00700 / usAAPL',
  name        VARCHAR(64)  NOT NULL,
  market      VARCHAR(8)   NOT NULL COMMENT 'a / hk / us / etf',
  kind        VARCHAR(8)   NOT NULL COMMENT 'index / stock / etf',
  currency    VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code),
  KEY idx_market (market, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标的字典';

CREATE TABLE IF NOT EXISTS kline_daily (
  code        VARCHAR(16)   NOT NULL,
  trade_date  DATE          NOT NULL,
  open        DECIMAL(18,4) NOT NULL DEFAULT 0,
  close       DECIMAL(18,4) NOT NULL DEFAULT 0,
  high        DECIMAL(18,4) NOT NULL DEFAULT 0,
  low         DECIMAL(18,4) NOT NULL DEFAULT 0,
  volume      DECIMAL(24,2) NOT NULL DEFAULT 0,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code, trade_date),
  KEY idx_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='前复权日线';

CREATE TABLE IF NOT EXISTS quote_snapshot (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  code        VARCHAR(16)   NOT NULL,
  snapshot_at DATETIME      NOT NULL,
  price       DECIMAL(18,4) NOT NULL DEFAULT 0,
  pct         DECIMAL(10,3) NOT NULL DEFAULT 0,
  amount_yi   DECIMAL(20,2) NOT NULL DEFAULT 0,
  turnover    DECIMAL(10,3) NULL,
  pe          DECIMAL(12,3) NULL,
  mktcap_yi   DECIMAL(20,2) NULL,
  PRIMARY KEY (id),
  KEY idx_code_time (code, snapshot_at),
  KEY idx_time (snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘中快照（3 分钟节流）';

CREATE TABLE IF NOT EXISTS daily_score (
  code        VARCHAR(16)  NOT NULL,
  trade_date  DATE         NOT NULL,
  score       DECIMAL(5,1) NOT NULL,
  sig         VARCHAR(8)   NOT NULL COMMENT 'signal 为 MySQL 保留字，故用 sig',
  trend       DECIMAL(5,1) NOT NULL DEFAULT 0,
  momentum    DECIMAL(5,1) NOT NULL DEFAULT 0,
  volume_f    DECIMAL(5,1) NOT NULL DEFAULT 0,
  risk        DECIMAL(5,1) NOT NULL DEFAULT 0,
  r5          DECIMAL(8,2) NULL,
  r20         DECIMAL(8,2) NULL,
  rsi         DECIMAL(6,2) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code, trade_date),
  KEY idx_date (trade_date),
  KEY idx_score (trade_date, score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日多因子评分（按交易日覆盖）';

CREATE TABLE IF NOT EXISTS watchlist (
  code        VARCHAR(16)  NOT NULL,
  note        VARCHAR(255) NULL,
  sort_no     INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股';

CREATE TABLE IF NOT EXISTS holding (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  code        VARCHAR(16)   NOT NULL,
  shares      DECIMAL(20,4) NOT NULL DEFAULT 0,
  cost        DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本价',
  buy_date    DATE          NULL,
  note        VARCHAR(255)  NULL,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓（预留）';
