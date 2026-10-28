-- ============================================================
-- V17 汇率日表：全站估值口径（持仓 / 家庭 / 收益）的唯一来源
-- 早先 HKD/USD 汇率写死在 application.yml，等于假设汇率永不变化，
-- 多币种组合的市值与净值会系统性偏离。改为按日入库，接口带回取数日期。
-- ============================================================

CREATE TABLE IF NOT EXISTS fx_rate (
  ccy        VARCHAR(8)    NOT NULL COMMENT '外币代码：USD / HKD',
  rate_date  DATE          NOT NULL COMMENT '行情日期（取源站标注的交易日，非抓取时刻）',
  rate       DECIMAL(12,6) NOT NULL COMMENT '1 单位外币 = ? 人民币',
  src        VARCHAR(24)   NOT NULL DEFAULT '' COMMENT '取数源：sina / er-api / config',
  created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (ccy, rate_date),
  KEY idx_date (rate_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='汇率日表（1 外币 = ? CNY）';
