-- 13F 的 CUSIP → 美股 ticker 映射
-- 背景：SEC 13F 只申报 CUSIP（9 位库斯普编号），不报股票代码。原先靠发行人名称模糊匹配反查 ticker，
-- 名称被截断到 28 字符或写法特殊（TAIWAN SEMICONDUCTOR MANUFAC / APPLIED MATLS INC）时匹配失败，
-- 代码列就回落成 874039100 这种裸 CUSIP，界面很不友好。
-- 本表由 OpenFIGI（Bloomberg 公开接口）按 CUSIP 权威映射生成，导入时优先查表，命中即用 ticker。
CREATE TABLE IF NOT EXISTS cusip_ticker (
    cusip      VARCHAR(12) NOT NULL COMMENT '13F 申报的 CUSIP',
    ticker     VARCHAR(24) NOT NULL COMMENT '美股代码，不含 us 前缀；股份类别用短横线（BRK-B）',
    name       VARCHAR(96) NOT NULL DEFAULT '' COMMENT 'OpenFIGI 返回的发行人全称，便于核对',
    src        VARCHAR(12) NOT NULL DEFAULT 'openfigi' COMMENT 'openfigi / manual',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (cusip)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='13F CUSIP 到美股代码映射';
