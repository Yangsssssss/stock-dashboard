-- 阿斯麦：13F 用 CUSIP（N07059210）申报，未能匹配到 ASML 代码，单独补中文名
INSERT INTO security_cn (code, name_cn, src) VALUES
  ('N07059210', '阿斯麦（ASML）', 'manual')
ON DUPLICATE KEY UPDATE name_cn = VALUES(name_cn), src = 'manual';
