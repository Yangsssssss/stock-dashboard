-- 自定义标的：用户可在页面自由添加 / 删除看板标的
ALTER TABLE instrument
  ADD COLUMN custom TINYINT NOT NULL DEFAULT 0 COMMENT '1=用户添加',
  ADD COLUMN hidden TINYINT NOT NULL DEFAULT 0 COMMENT '1=已从看板移除(内置标的)',
  ADD COLUMN grp    VARCHAR(8) NULL COMMENT '分组 a/hk/us/etf';
