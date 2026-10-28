-- 大佬持仓中文化：机构中文名 + 标的中文名映射
-- 1) guru.name_cn：机构中文名（如 Berkshire Hathaway → 伯克希尔·哈撒韦），列表主名用中文，英文原名降为小字副行
-- 2) guru.note 改为只存「掌门人」，避免与 name_cn 重复
-- 3) security_cn：本项目代码（usAAPL 等）→ 中文简称，持仓明细 / 共识榜 JOIN 取用
--    由 NameCnService 从腾讯行情批量灌入，手工词典优先（src=manual）

SET @has := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'guru' AND column_name = 'name_cn');
SET @ddl := IF(@has = 0,
  'ALTER TABLE guru ADD COLUMN name_cn VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''机构中文名'' AFTER name',
  'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 预置跟踪机构的机构中文名
UPDATE guru SET name_cn = '伯克希尔·哈撒韦' WHERE name = 'Berkshire Hathaway' AND name_cn = '';
UPDATE guru SET name_cn = '阿帕卢萨管理'     WHERE name = 'Appaloosa Management' AND name_cn = '';
UPDATE guru SET name_cn = '潘兴广场资本'     WHERE name = 'Pershing Square' AND name_cn = '';
UPDATE guru SET name_cn = '赛恩资产管理'     WHERE name = 'Scion Asset Management' AND name_cn = '';
UPDATE guru SET name_cn = '桥水基金'         WHERE name = 'Bridgewater Associates' AND name_cn = '';
UPDATE guru SET name_cn = '文艺复兴科技'     WHERE name = 'Renaissance Technologies' AND name_cn = '';
UPDATE guru SET name_cn = '老虎环球基金'     WHERE name = 'Tiger Global Management' AND name_cn = '';
UPDATE guru SET name_cn = '挪威银行投资管理' WHERE name = 'Norges Bank' AND name_cn = '';
UPDATE guru SET name_cn = '穆巴达拉投资'     WHERE name = 'Mubadala Investment' AND name_cn = '';

-- note 只留掌门人（原格式为「掌门人 · 中文机构名」，中文机构名已迁到 name_cn）
UPDATE guru SET note = '巴菲特' WHERE name = 'Berkshire Hathaway';
UPDATE guru SET note = '大卫·泰珀' WHERE name = 'Appaloosa Management';
UPDATE guru SET note = '比尔·阿克曼' WHERE name = 'Pershing Square';
UPDATE guru SET note = '迈克尔·伯里（《大空头》原型）' WHERE name = 'Scion Asset Management';
UPDATE guru SET note = '瑞·达利欧' WHERE name = 'Bridgewater Associates';
UPDATE guru SET note = '吉姆·西蒙斯' WHERE name = 'Renaissance Technologies';
UPDATE guru SET note = '蔡斯·科尔曼' WHERE name = 'Tiger Global Management';
UPDATE guru SET note = '挪威主权财富基金' WHERE name = 'Norges Bank';
UPDATE guru SET note = '阿布扎比主权基金' WHERE name = 'Mubadala Investment';

CREATE TABLE IF NOT EXISTS security_cn (
  code VARCHAR(24) NOT NULL COMMENT '本项目代码，如 usAAPL（期权去掉 ~PUT 后缀）',
  name_cn VARCHAR(64) NOT NULL COMMENT '中文简称',
  src VARCHAR(16) NOT NULL DEFAULT 'tencent' COMMENT 'tencent 行情源 / manual 手工词典（优先，不被覆盖）',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标的中文名（13F 持仓等中文界面用）';
