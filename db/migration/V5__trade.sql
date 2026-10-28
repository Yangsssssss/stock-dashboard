-- 交易流水：买入 / 卖出 / 分红 / 期初建仓逐笔记录，持仓与成本由流水聚合推导
CREATE TABLE IF NOT EXISTS trade (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  broker     VARCHAR(64)   NOT NULL COMMENT '券商 / 账户名',
  code       VARCHAR(32)   NOT NULL COMMENT '内部代码 sh600036/hk00700/usMSFT',
  name       VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '名称',
  side       VARCHAR(8)    NOT NULL COMMENT 'BUY 买入 / SELL 卖出 / DIV 分红 / INIT 期初建仓',
  trade_date DATE          NOT NULL COMMENT '成交或派息日',
  qty        DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '数量（DIV 为 0）',
  price      DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '成交单价（DIV 为 0）',
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '现金净额：卖出=成交额-费用；分红=税后到账',
  fee        DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '佣金 + 税费（不含股息税，股息税已在 amount 中扣除）',
  currency   VARCHAR(8)    NOT NULL DEFAULT 'CNY',
  note       VARCHAR(255)  NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_broker_code (broker, code),
  KEY idx_date (trade_date),
  KEY idx_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易流水（唯一真相）';

-- 把现有 holding 迁移为期初建仓流水（仅首次执行：trade 为空时）
INSERT INTO trade (broker, code, name, side, trade_date, qty, price, amount, fee, currency, note)
SELECT h.broker, h.code, h.name, 'INIT', DATE(h.created_at), h.qty, h.cost,
       ROUND(h.qty * h.cost, 2), 0, h.currency, '期初建仓（由原持仓迁移，成本为券商口径）'
FROM holding h
WHERE h.qty > 0
  AND NOT EXISTS (SELECT 1 FROM trade t WHERE t.broker = h.broker AND t.code = h.code)
  AND NOT EXISTS (SELECT 1 FROM (SELECT COUNT(*) AS n FROM trade) x WHERE x.n > 0);
