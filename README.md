# QuantBoard · 多市场股票看板

A 股 / 港股 / 美股 / ETF / 场外基金（含债基）实时看板 + 技术面分析。前端原生 JS（未改动），后端 **Java 21 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.14**，持久层 MySQL 8。

## 启动

```bash
cp .env.example .env        # 首次：填入 MySQL 口令（.env 不入库）
./run.sh                    # 需先构建一次；Docker Desktop 需在运行（MySQL）
./run.sh 8849               # 指定端口
```

`run.sh` 会自动加载 `.env` 中的 `DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME / PORT`。

`run.sh` 带**端口守卫**：目标端口已有实例在监听时直接退出（退出码 0），不会去抢端口起第二个 JVM。
必须兜住这一点——两个 JVM 同时 `--server.port=8848` 时后启动的那个 bind 失败，
表现就是日志里「连开三个只有一个起来」。

### 常驻运行（推荐交给 launchd）

服务由谁启动，就归谁的生命周期管：在**终端**里起（`./start.command` 或 `./run.sh`），关掉终端就停；
在别的宿主进程（IDE、agent 会话、脚本沙箱）里起，宿主收尾会把它一起杀掉。
要长期稳定运行，交给 launchd 托管：

```bash
# 一次性安装（之后登录自动启动、被杀或崩溃 10 秒内自动重启）
bash bin/install-launchd.sh

# 状态 / 停止
launchctl print gui/$(id -u)/com.quantboard.dashboard | head -20
launchctl bootout gui/$(id -u)/com.quantboard.dashboard
```

launchd 配置见 `~/Library/LaunchAgents/com.quantboard.dashboard.plist`（`RunAtLoad` + `KeepAlive`、
`ThrottleInterval=10`，输出写 `logs/quantboard.log`）。

> **重启会清空行情缓存**，重启后第一个请求偏慢：`/api/board` 约 4s（要拉全部标的行情）、
> `/api/positions` 约 1s、`/api/guru` 约 3s。缓存热了以后都在毫秒级（board 2~5ms、family 7~20ms）。
> 所以「偶发很慢」往往意味着进程刚被重启过，而不是接口本身慢。

> 大佬持仓的 13F 拉取需要 `.env` 里的 `QUANTBOARD_SEC_UA`：SEC 要求 UA 为「应用名 + 真实邮箱」，
> 用假邮箱或浏览器 UA 会被 `www.sec.gov` 判为未申报的自动化工具返回 **403**。换成自己的邮箱即可。
> 值里含空格**必须加引号**（`QUANTBOARD_SEC_UA="QuantBoard Research you@mail.com"`），
> 否则 `run.sh` 里的 `. ./.env` 会把它当成「给 `Research` 这条命令设置临时变量」，变量实际不会被导出。
>
> 国内网络直连 `data.sec.gov` 不通（连接被重置）。HTTP 抓取层遇到连接失败会**自动探测本机
> 系统代理重试一次**（Clash/Surge 等写入的系统代理即可，无需配置）；也可在 `.env` 显式指定
> `QUANTBOARD_HTTP_PROXY=http://127.0.0.1:7890`。国内数据源（腾讯/东财）正常路径不经代理。

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

## 测试

```bash
cd backend-java
./mvnw test                              # 日常：46 个用例，约 1 秒，不连库不打网络
./mvnw test -Dtest=LiveFeedContractIT    # 手动：真打一次腾讯行情，验上游字段位没变
```

四道桩，按「改坏了会怎样」排序：

| 层 | 位置 | 防的是什么 |
|---|---|---|
| 数值底座 | `util/UTest` | `-`/`None`/空串解析、HALF_UP 舍入、除零；错一位小数全线跟着错 |
| 指标+评分**对拍** | `indicator/*GoldenTest` | Java 版 vs `legacy/quant/indicators.py` 同输入同输出。金标准由 `src/test/resources/fixtures/generate.py` 用 Python 版算出，连结论文案一起钉住 |
| 解析 | `feed/FeedParseTest` | 腾讯行情靠数字下标取字段，上游挪一位就静默错位（样本是真实 GBK 响应，A股/港股/美股各一条） |
| 钱 | `service/TradeServiceAggregateTest` | 移动加权成本、部分卖出、超量卖出截断、分红 TTM、`已实现 = -(净投入)` 恒等式 |
| 迁移 | `db/SplitSqlTest` | 手写 Flyway 的切句器；切错=启动时 DB 静默降级 |

**改指标/评分时的顺序**：先改 Python 或明确接受偏离 → 重跑 `python3 src/test/resources/fixtures/generate.py` → 再改 Java → 跑测试看差异。夹具是确定性合成数据（无随机数），换机器结果一致。

`*IT` 结尾的是集成/联网测试，surefire 默认不扫（只扫 `*Test`），所以不会拖慢日常构建。

## 弹窗提示组件（前端）

纯 DOM 实现，`web/dialog.js` + `web/dialog.css`，**不调用 `alert` / `confirm` / `prompt` 等系统组件**
（全站已无原生弹窗调用）。两个页面都已在 `common.js` 之后引入，脚本内可直接使用。

```js
QBToast.ok('保存成功');                                  // 成功（绿）
QBToast.err('加载失败', { desc: e.message });            // 错误（红，默认停留更久）
QBToast.warn('汇率是静态值');                            // 警告（黄）
QBToast.info('已刷新');                                  // 信息（蓝）

await QBConfirm({ title: '删除流水', text: '删除后不可恢复', okText: '删除', danger: true });
// → Promise<boolean>：确定 true / 取消 false

await QBAlert({ title: '提示', text: '已配置 DeepSeek Key' });
```

细节：右上角堆叠（最多 4 条自动裁剪）、底部进度条自动消失、鼠标悬停暂停计时、
点击 × 立即关闭；弹窗支持 Esc 关闭 / Enter 确认、点遮罩取消、自动聚焦确认键；
颜色走 `--up / --dn / --accent` 主题变量，明暗主题自适应，并遵循
`prefers-reduced-motion` 关闭动画。

## API

| 端点 | 说明 |
|---|---|
| `GET /api/board?force=1` | 看板全量数据（分组 / 排名 / 相关性 / 板块 / 自选 / 会话状态） |
| `GET /api/trends?code=` | 盘中分时（1 分钟价格 + 昨收基准 + 时间标签，东财 trends2 主源 + 腾讯 minute 备源） |
| `GET /api/kline?code=&days=&period=` | K 线 + MA：period=day（120/250/上市至今全量）/ month / quarter / year |
| `GET/POST /api/watchlist` | 自选清单（add / remove） |
| `GET /api/history?code=&days=` | 评分历史 |
| `GET /api/movers` | 最近两个交易日评分变化榜 |
| `GET /api/db` | 库状态与行数统计 |
| `GET /api/search?q=` | 标的搜索联想 |
| `POST /api/instruments` | 标的增删（add / remove / restore） |
| `GET/POST /api/positions` | 持仓（按券商分组；add 为 broker+code upsert，remove 按 id） |
| `GET/POST /api/family` | 家庭总览（证券+非证券+负债+净值+目标偏离+成员+收支）与科目台账 CRUD（add / update / remove） |
| `POST /api/family/target` | 保存五大类目标配置占比 `{"targets":{"a":40,...}}` |
| `POST /api/family/member` | 家庭成员增删改（add / update / remove；删除成员名下数据归入「共有」） |
| `POST /api/family/flow` | 家庭收支流水 CRUD（income / expense × 成员 × 分类，月度/年度/累计汇总） |
| `POST /api/positions/ocr` | 券商截图 OCR → 候选持仓行（multipart `file`，只解析不入库） |
| `GET /api/positions/ai` | DeepSeek 持仓分析（`refresh=1` 绕过 30 分钟缓存；需 `.env` 配 `DEEPSEEK_API_KEY`） |
| `GET /api/news?force=1` | 消息中心：新浪 7×24 快讯 5 页（按政策/企业/市场/国际归类）+ 持仓与自选标的的东财公告，3 分钟缓存 |
| `GET /api/trend?code=` | 当天分时（东财 trends2，1 分钟线 + 昨收），市场表格迷你走势按需拉取，服务端缓存 60 秒 |
| `GET /api/guru` | 大佬持仓总览：跟踪机构列表（最新报告期 / 持仓数 / 市值 / 第一大持仓）+ 报告期 + 共识榜（≥2 家共同持有） |
| `GET /api/guru/holdings?investor=&quarter=` | 持仓明细（investor=0 为全部机构；quarter 空 = 各机构最新一期） |
| `GET/POST /api/guru` | 机构与持仓 CRUD（add_investor / update_investor / remove_investor / add / remove） |
| `POST /api/guru/import` | 从 SEC EDGAR 拉取该机构最新 13F-HR 并入库（同步，5–20 秒） |
| `POST /api/guru/names` | 补齐持仓标的中文名（腾讯行情批量取名 + 手工词典，已收录的跳过，可反复调用） |
| `POST /api/guru/cusip-map?limit=100` | 补齐 13F 的 CUSIP → 美股代码映射（OpenFIGI 权威映射，已查过的跳过）。单个请求只处理 `limit` 个，返回的 `left` 归零即补完 |
| `POST /api/guru/cusip-search?limit=100` | OpenFIGI 查不到美国挂牌的 CUSIP，改用东方财富搜索接口按中文名 / 13F 发行名反查（名称逐级截短，命中率约七成） |
| `POST /api/guru/remap` | 把存量持仓里仍以 CUSIP 当代码的行改写成正常美股代码，并合并因此撞车的重复行、重算占比。可重复调用 |

## MySQL 持久层

依赖本机 Docker 的 `mysql:8.0` 容器（连接参数来自 `.env` / 环境变量 `DB_HOST` 等，默认 `root@127.0.0.1:3306/quantboard`）。首次启动自动建库、跑迁移（`db/migration/V1~V11`），表结构：

| 表 | 内容 |
|---|---|
| `instrument` | 标的主数据（代码 / 名称 / 市场 / 类型 / custom 自定义 / hidden 隐藏） |
| `kline_daily` | 日线（增量 upsert，只补最新日期之后） |
| `quote_snapshot` | 盘中快照（同标的 180 秒节流） |
| `daily_score` | 每日多因子评分 + 各因子分项 |
| `watchlist` | 自选清单（跨会话保留） |
| `trade` | 交易流水（BUY / SELL / DIV / INIT 逐笔记录，**持仓与成本的唯一真相源**） |
| `nav_daily` | 每日净值快照（市值 / 净投入 / 已实现 / 股息 / 总收益，供收益曲线使用） |
| `family_asset` | 家庭非证券科目台账（现金 / 存款 / 场外基金 / 其他 / 负债，挂成员） |
| `family_target` | 家庭五大类目标配置占比（a / hk / us / etf_fund / cash_other） |
| `family_member` | 家庭成员（预置爷爷 / 奶奶 / 爸爸 / 妈妈 / 儿子） |
| `family_flow` | 家庭收支流水（收入 / 支出 × 成员 × 分类，只做现金流统计不并入净值） |
| `family_nav_daily` | 家庭净值每日快照（科目余额 + 证券市值 − 负债，打开总览页自动落当日快照） |
| `guru` | 跟踪的投资机构（13F 对冲基金 / 主权基金，含 SEC CIK，预置 9 家） |
| `guru_holding` | 机构持仓明细（按报告期，同一发行人多个 CUSIP 已按 ticker 聚合，占比自动重算） |
| `security_cn` | 标的中文名：`code`（本项目代码，如 usAAPL / CUSIP）→ 中文简称，持仓明细与共识榜 JOIN 取用；`src` = tencent / manual / none（回源也无中文名的占位） |
| `cusip_ticker` | 13F 的 CUSIP → 美股代码映射：`cusip` → `ticker`（不含 us 前缀，股份类别用短横线 BRK-B）。由 `CusipMapService` 调 OpenFIGI 生成，`ticker=''` 表示查过但确实没有美股代码（未上市公司等）的负缓存 |
| `schema_version` | 迁移版本 |

> `holding` 表已废弃：早期版本的静态持仓表，数据已在 V5 迁移中转入 `trade` 流水，V7 中删除。

- 每轮 `/api/board` 组装后异步落库，失败不影响页面；MySQL 不可达时自动降级为纯内存模式
- 评分历史回填：用 legacy 版 `python3 -m quant.backfill 30`（Python 版保留此用途）

## 功能

- **总览**：四大市场温度卡（涨跌家数 / 20 日均值 / 交易状态）、核心指数 45 日走势、A 股行业板块热力、信号速览
- **市场页**（A 股 / 港股 / 美股 / ETF）：可排序成分表（涨跌幅 / 成交额 / 20 日 / RSI / 评分 / 信号 / 迷你走势），点击行打开详情抽屉
- **详情抽屉**：K 线 + MA5/10/20/60 + 成交量（120 / 250 日切换）、多因子评分、因子拆解（趋势 / 动量 / 量能 / 风险）、15 项关键指标、60 日支撑 / 压力位
- **技术信号榜**：全部标的按 0–100 评分排序，含技术面结论一句话
- **自选股**：任意表格点 ★ 收藏，存 MySQL，跨会话/跨设备保留
- **持仓**：按券商 / 账户分组的持仓页——数量 + 成本入 MySQL，现价 / 市值 / 持仓盈亏 / 今日盈亏 / 技术评分实时合并；跨币种分列汇总并按静态汇率（`quantboard.fx-hkd/fx-usd`）折算约合人民币。支持「手动添加」与「图片识别」：上传券商 App 持仓页截图，本地 OCR（macOS Vision 框架，首次调用自动用 clang 编译 CLI；失败回退 tesseract）解析出候选行，核对/编辑后一键导入。同标的可分属不同券商各记一条。另有「AI 分析」：把实时持仓快照（折人民币、含评分与信号）交给 DeepSeek（`quantboard.ai.*` / `DEEPSEEK_API_KEY`）生成组合结构、重点持仓、风险与建议的点评，结果缓存 30 分钟
- **家庭总览**：家庭资产负债一张表——证券市值由持仓流水 + 实时行情推导（折人民币，按 A股 / 港股 / 美股 / ETF·基金 / 现金·其他 五大类归集），非证券科目（现金 / 存款 / 场外基金 / 其他 / 负债）在台账手动维护并挂到成员（预置爷爷 / 奶奶 / 爸爸 / 妈妈 / 儿子 + 共有，可增删改）；负债单列，净值 = 总资产 − 总负债。环形图 + 大类占比 + 目标配置偏离度（偏离 ±5pp 标黄），目标占比可编辑。成员维度：各成员台账余额 + 本月收支；收支流水（收入 / 支出 × 成员 × 分类 × 币种）只做现金流月度/年度/累计汇总，不并入净值口径；支持按成员筛选台账与收支。分析图：近 12 个月收支双柱图、本月收/支分类构成 Top 8、家庭净值曲线（每日快照，打开总览页自动落当日）
- **标的自由增删**：市场页右上"＋ 添加标的"打开管理弹窗——搜索名称/拼音/代码，点击加入；详情抽屉"移出看板"或行悬停 ✕ 移除（内置标的为隐藏）
- **持仓即看板池**：`trade` 流水里净持仓 > 0 的标的自动并入看板（按代码自动归组 A 股 / 港股 / 美股 / ETF / 基金），拉行情 + 补历史日线 + 算多因子评分，表格里带「持仓」标记；买入即入池、不用手动添加，清仓后自动退出
- **相关性矩阵**：沪深300 / 创业板 / 恒生科技 / 纳指 / 黄金 ETF 近 60 日收益相关性 + 配置解读
- **股息率**：A 股由「实际入账的 DIV 流水 ÷ 成本」算得；美股/港股没有流水时，自动取该股近 12 个月每股股息（见下表三源）估算，界面用斜体 `~x.xx%` 与实收数据区分，且**不写入任何现金流**
- **大佬持仓**：跟踪知名投资人与主权基金的美股持仓。预置巴菲特 / Tepper / Ackman / Burry / 桥水 / 文艺复兴 / 老虎环球 + 挪威央行 / 穆巴达拉共 9 家，填了 SEC CIK 的点「拉取」即从 EDGAR 同步最新 13F-HR（报告期滞后一个季度属正常；`13F-NT` 为代报无明细，自动跳过取上一期 HR）。三张表：机构列表（最新报告期 / 持仓数 / 市值 / 第一大持仓）、持仓明细（可按机构与报告期筛选，点代码跳行情详情）、共识榜（被 ≥2 家同时持有的标的，按机构数排序）。简体中文化（V12~V15 迁移）：① 机构表加 `name_cn`，列表第一行显示机构中文名（伯克希尔·哈撒韦），第二行小字为「掌门人 · SEC 英文原名」（`guru.note` 只存掌门人）；② 新增 `security_cn` 标的中文名表，持仓明细 / 共识榜 / 第一大持仓都优先显示中文名，悬停看 13F 英文原名核对；数据来源为腾讯行情批量取名（一次 40 个代码，约四成标的有中文简称）+ `NameCnService.MANUAL` 手工词典（腾讯只给英文名的常见标的）+ CUSIP 兜底词条，回源也没有中文名的记一条空名占位（`src=none`），下次不再重复请求；页面首访若发现缺口会自动调一次 `POST /api/guru/names`；③ 报告期显示为「2026 年二季度 / 26年二季度」、期权备注译作「看跌期权 Put / 看涨期权 Call」、字段表头与三张表底部均带口径说明；④ **CUSIP → 美股代码**（V16 迁移 + `CusipMapService`，OpenFIGI 主路 + 东财搜索兜底）：13F 表单本身只申报 CUSIP、不报股票代码，原先靠发行人名称模糊匹配反查，而 13F 的发行名会被截断到 28 个字符（`TAIWAN SEMICONDUCTOR MANUFAC`、`APPLIED MATLS INC`），匹配不上时代码列就直接显示 `874039100` 这种库斯普编号；现在改为按 CUSIP 直查 Bloomberg 的 OpenFIGI 公开映射接口（免费无需 key，10 个/请求、25 请求/分钟），命中即用，名称匹配降级为兜底；确实没有美股代码的（SpaceX 等未上市公司）记一条 `ticker=''` 负缓存，界面显示 <code>—</code> 不再裸露编号，存量数据用 `POST /api/guru/remap` 回填。数据口径：13F 的 `value` 以**美元**申报（2009 年起，不是千美元）；`sshPrnamt` 藏在 `<shrsOrPrnAmt>` 下需递归取（持股数为空多半是取了旧 jar）；同一发行人的多个 CUSIP（股份类别 / 多管理人分别申报）按 ticker 聚合后入库；占比按该机构该期总市值重算；按机构筛选且未指定报告期时取**该机构自己**的最新一期（Scion、Pershing 的季报落后于全局最新一期）
- **市场页分时走势**：成分表最后一列迷你走势为**当天分时**（`/api/trend` 按需并发拉取，客户端缓存 60 秒、服务端缓存 60 秒，昨收基准决定红绿）；基金无分时仍显示净值 45 日。核心指数卡的迷你走势同样是当天分时

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
| 美股夜盘（盘前/盘后） | 新浪 gb_ 接口（21=夜盘价 22=涨跌幅% 23=涨跌额 24=美东成交时间，`ext_*` 字段并入报价；美股详情页头部 + 美股/信号榜/自选列表夜盘列；BRK.B 等 B 类股新浪无数据显示 —） | — |
| A股/ETF 日线 | 新浪 CN_MarketDataService | 东财 → 腾讯 fqkline |
| 港股日线 | 腾讯 hkfqkline | 东财 |
| 美股日线 | 腾讯 usfqkline（后缀 .OQ 纳斯达克 / .N 纽交所 / .AM 美交所ETF，未知标的按此顺序探测并缓存） | 东财（secid 105/106/107 依次试） |
| 行业板块 | 新浪 newSinaHy.php | 东财 |
| 标的搜索 | 东财 suggest | — |
| A 股分红方案 | 东财 RPT_SHAREBONUS_DET | — |
| 美股股息（每股） | 纳斯达克官方 `/api/quote/{sym}/dividends`（只覆盖纳市上市标的） | dividendhistory.org → 东财复权因子反推 |

注意：东财会对高频客户端做 TLS 指纹/IP 风控，因此 HTTP 层统一走系统 curl；K 线缓存 15 分钟（带随机抖动避免集中过期触发限流），远端失败写 60 秒负缓存并回退旧数据。

行情缓存口径：报价按 per-code TTL 缓存（交易时段 15 秒 / 休市 5 分钟），并发走 **single-flight**——同一批代码同一时刻只真正打一次腾讯，其余请求等同一个结果（避免看板后台刷新、收益页、家庭页各拉一次）；`/api/family` 的证券估值是 **stale-while-revalidate**：过期先返回上一份快照、后台线程补算（响应里的 `sec_ts` 即快照时间），台账/成员/收支等 DB 部分每次现算，交易流水变动会主动失效快照。

仅供研究参考，不构成投资建议。
