-- 家庭净值每日快照：口径与总览一致 = 科目余额 + 证券市值 − 负债（全部折人民币）
CREATE TABLE IF NOT EXISTS family_nav_daily (
  trade_date  DATE          NOT NULL,
  assets      DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '总资产（折人民币）',
  liabilities DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '总负债（折人民币）',
  net         DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '净值 = 资产 − 负债',
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭净值每日快照';
