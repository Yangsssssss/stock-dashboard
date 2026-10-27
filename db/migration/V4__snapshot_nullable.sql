-- 基金（场外）没有成交额，行情快照字段需要允许为空；
-- 否则整批快照 INSERT 会因 amount_yi 为 NULL 而全部失败。
ALTER TABLE quote_snapshot
    MODIFY COLUMN amount_yi DECIMAL(20, 2) NULL DEFAULT NULL COMMENT '成交额（亿元），基金/部分指数为空';
