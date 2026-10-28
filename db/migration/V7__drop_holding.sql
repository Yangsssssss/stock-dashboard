-- 废弃 holding 表：静态持仓模式已由 trade 流水取代
-- 存量数据在 V5 中已迁移为 INIT 期初建仓流水（qty / cost 保持一致），此处删除旧表
DROP TABLE IF EXISTS holding;
