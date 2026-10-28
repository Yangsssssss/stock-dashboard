-- 家庭资产管理：非证券科目台账（证券市值由 trade 流水实时推导，不在这里重复记账）
CREATE TABLE IF NOT EXISTS family_asset (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  atype      VARCHAR(16)   NOT NULL COMMENT 'cash 现金 / deposit 存款 / fund 场外基金 / other 其他 / liability 负债',
  name       VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '科目名称，如 招商银行活期',
  amount     DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '余额（负债记正数）',
  currency   VARCHAR(8)    NOT NULL DEFAULT 'CNY',
  note       VARCHAR(255)  NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_atype (atype)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭资产科目（非证券部分）';

-- 目标配置占比：五大类 bucket = a / hk / us / etf_fund / cash_other
CREATE TABLE IF NOT EXISTS family_target (
  bucket VARCHAR(16) PRIMARY KEY,
  pct    DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT '目标占比 %'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭目标配置占比';
