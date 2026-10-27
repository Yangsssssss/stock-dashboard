# QuantBoard · 多市场股票看板

A 股 / 港股 / 美股 / ETF / 场外基金（含债基）实时看板 + 技术面分析。前端原生 JS（未改动），后端 **Java 21 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.14**，持久层 MySQL 8。

## 启动

```bash
cp .env.example .env        # 首次：填入 MySQL 口令（.env 不入库）
./run.sh                    # 需先构建一次；Docker Desktop 需在运行（MySQL）
./run.sh 8849               # 指定端口
```

`run.sh` 会自动加载 `.env` 中的 `DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME / PORT`。

首次构建：

```bash
cd backend-java && ./mvnw package -DskipTests
```

浏览器打开 http://127.0.0.1:8848 （默认端口 8848，可通过参数覆盖）

## 后端结构

```
backend-java/
  pom.xml                                   Spring Boot 3.3.5 + MyBatis-Plus 3.5.14 + mysql-connector-j
  src/main/java/com/quantboard/
    QuantBoardApp.java                      启动类
    feed/HttpFetch.java                     HTTP 抓取（走系统 curl 子进程，规避 TLS 指纹风控）
    feed/Universe.java                      内置标的池 / secid 映射 / 代码分类
    feed/FeedService.java                   报价、多源 K 线、板块、搜索、交易时段、内存缓存+负缓存
    indicator/Indicators.java               SMA/EMA/RSI/MACD/ATR/BOLL
    indicator/AnalysisService.java          多因子评分引擎 + 相关性矩阵
    db/DbService.java                       MySQL 持久层（建库、迁移、批量 upsert、自选/自定义标的）
    service/BoardService.java               看板组装（并行抓取、缓存、异步落库）
    web/ApiController.java                  全部 REST 端点
    web/StaticConfig.java                   静态资源直接服务 web/ 目录（前端即时生效）
  src/main/resources/application.yml        端口 / 数据源 / 连接池
legacy/                                     原 Python 版后端（保留备查）
```

## API

| 端点 | 说明 |
|---|---|
| `GET /api/board?force=1` | 看板全量数据（分组 / 排名 / 相关性 / 板块 / 自选 / 会话状态） |
| `GET /api/kline?code=&days=` | 日线 + MA5/10/20/60 |
| `GET/POST /api/watchlist` | 自选清单（add / remove） |
| `GET /api/history?code=&days=` | 评分历史 |
| `GET /api/movers` | 最近两个交易日评分变化榜 |
| `GET /api/db` | 库状态与行数统计 |
| `GET /api/search?q=` | 标的搜索联想 |
| `POST /api/instruments` | 标的增删（add / remove / restore） |
| `GET/POST /api/positions` | 持仓（按券商分组；add 为 broker+code upsert，remove 按 id） |
| `POST /api/positions/ocr` | 券商截图 OCR → 候选持仓行（multipart `file`，只解析不入库） |

## MySQL 持久层

依赖本机 Docker 的 `mysql:8.0` 容器（连接参数来自 `.env` / 环境变量 `DB_HOST` 等，默认 `root@127.0.0.1:3306/quantboard`）。首次启动自动建库、跑迁移（`db/migration/V1~V4`），表结构：

| 表 | 内容 |
|---|---|
| `instrument` | 标的主数据（代码 / 名称 / 市场 / 类型 / custom 自定义 / hidden 隐藏） |
| `kline_daily` | 日线（增量 upsert，只补最新日期之后） |
| `quote_snapshot` | 盘中快照（同标的 180 秒节流） |
| `daily_score` | 每日多因子评分 + 各因子分项 |
| `watchlist` | 自选清单（跨会话保留） |
| `holding` | 持仓（券商 / 账户 + 代码 + 数量 + 成本，broker+code 唯一） |
| `schema_version` | 迁移版本 |

- 每轮 `/api/board` 组装后异步落库，失败不影响页面；MySQL 不可达时自动降级为纯内存模式
- 评分历史回填：用 legacy 版 `python3 -m quant.backfill 30`（Python 版保留此用途）

## 功能

- **总览**：四大市场温度卡（涨跌家数 / 20 日均值 / 交易状态）、核心指数 45 日走势、A 股行业板块热力、信号速览
- **市场页**（A 股 / 港股 / 美股 / ETF）：可排序成分表（涨跌幅 / 成交额 / 20 日 / RSI / 评分 / 信号 / 迷你走势），点击行打开详情抽屉
- **详情抽屉**：K 线 + MA5/10/20/60 + 成交量（120 / 250 日切换）、多因子评分、因子拆解（趋势 / 动量 / 量能 / 风险）、15 项关键指标、60 日支撑 / 压力位
- **技术信号榜**：全部标的按 0–100 评分排序，含技术面结论一句话
- **自选股**：任意表格点 ★ 收藏，存 MySQL，跨会话/跨设备保留
- **持仓**：按券商 / 账户分组的持仓页——数量 + 成本入 MySQL，现价 / 市值 / 持仓盈亏 / 今日盈亏 / 技术评分实时合并；跨币种分列汇总并按静态汇率（`quantboard.fx-hkd/fx-usd`）折算约合人民币。支持「手动添加」与「图片识别」：上传券商 App 持仓页截图，本地 OCR（macOS Vision 框架，首次调用自动用 clang 编译 CLI；失败回退 tesseract）解析出候选行，核对/编辑后一键导入。同标的可分属不同券商各记一条
- **标的自由增删**：市场页右上"＋ 添加标的"打开管理弹窗——搜索名称/拼音/代码，点击加入；详情抽屉"移出看板"或行悬停 ✕ 移除（内置标的为隐藏）
- **相关性矩阵**：沪深300 / 创业板 / 恒生科技 / 纳指 / 黄金 ETF 近 60 日收益相关性 + 配置解读

## 评分模型

综合评分 = 0.34×趋势 + 0.30×动量 + 0.16×量能 + 0.20×风险（各因子 0–100）

- 趋势：价格与 MA20/MA60 结构、均线排列、60 日区间位置
- 动量：5/20 日涨幅、RSI(14)、相对基准的 20 日超额
- 量能：5/20 日量比与方向配合、MACD 柱
- 风险：60 日年化波动率、距 60 日高点回撤、ATR%

信号：≥72 强势 / 58 偏强 / 44 中性 / 30 偏弱 / <30 弱势。

## 数据源（多源容灾）

| 数据 | 主源 | 备源 |
|---|---|---|
| 实时报价 | 腾讯 qt.gtimg.cn | — |
| A股/ETF 日线 | 新浪 CN_MarketDataService | 东财 → 腾讯 fqkline |
| 港股日线 | 腾讯 hkfqkline | 东财 |
| 美股日线 | 腾讯 usfqkline（个股带 .OQ/.N 后缀） | 东财 |
| 行业板块 | 新浪 newSinaHy.php | 东财 |
| 标的搜索 | 东财 suggest | — |

注意：东财会对高频客户端做 TLS 指纹/IP 风控，因此 HTTP 层统一走系统 curl；K 线缓存 15 分钟（带随机抖动避免集中过期触发限流），远端失败写 60 秒负缓存并回退旧数据。

仅供研究参考，不构成投资建议。
