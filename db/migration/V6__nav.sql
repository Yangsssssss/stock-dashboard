-- 组合每日净值快照：收益引擎的时间维度底表，一天一行，全部折算人民币
-- 注意：列名不能用 div（MySQL 保留字）
CREATE TABLE IF NOT EXISTS nav_daily (
  trade_date DATE          NOT NULL COMMENT '交易日',
  mv         DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '总市值（折人民币）',
  cost       DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '剩余持仓成本（折人民币）',
  realized   DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '累计已实现盈亏（折人民币）',
  div_total  DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '累计股息（折人民币）',
  invested   DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '净投入 = 累计买入含费 − 累计卖出净额（折人民币）',
  total_pl   DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '总收益 = 市值 − 净投入 + 累计股息',
  estimated  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = K线回溯估算，0 = 当日实盘快照',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组合每日净值快照（收益引擎）';
