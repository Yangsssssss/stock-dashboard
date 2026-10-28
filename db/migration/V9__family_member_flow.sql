-- 家庭成员：爷爷 / 奶奶 / 爸爸 / 妈妈 / 儿子 五个账户（可增删改）
CREATE TABLE IF NOT EXISTS family_member (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  name     VARCHAR(32) NOT NULL COMMENT '成员名',
  sort_no  INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭成员';

INSERT INTO family_member (name, sort_no) VALUES
  ('爷爷', 1), ('奶奶', 2), ('爸爸', 3), ('妈妈', 4), ('儿子', 5);

-- 家庭收支流水：只做现金流台账与月度汇总，不并入资产净值口径
CREATE TABLE IF NOT EXISTS family_flow (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id  BIGINT        NOT NULL DEFAULT 0 COMMENT '成员 id，0 = 家庭共有',
  ftype      VARCHAR(8)    NOT NULL COMMENT 'income 收入 / expense 支出',
  category   VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '分类，如 工资 / 餐饮 / 房贷还款',
  amount     DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '金额（正数）',
  currency   VARCHAR(8)    NOT NULL DEFAULT 'CNY',
  flow_date  DATE          NOT NULL,
  note       VARCHAR(255)  NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_date (flow_date),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭收支流水';

-- 科目挂成员：0 = 家庭共有
ALTER TABLE family_asset ADD COLUMN member_id BIGINT NOT NULL DEFAULT 0 COMMENT '成员 id，0 = 共有' AFTER atype;
