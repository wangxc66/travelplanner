# TripCanvas — 使用说明

一个城市旅行路线规划器。搜索景点 → 拖到某一天 → 让规划器算出真正排得进一天的顺序。

前后端是两个独立的仓库：

- [travelplanner](https://github.com/wangxc66/travelplanner) —— 后端（Java / Spring Boot / Gradle），也就是本仓库
- [travelplanner-frontend](https://github.com/wangxc66/travelplanner-frontend) —— 前端（React / CRA / antd）

---

## 0. 切换语言

界面右上角（登录页在卡片右上角）有 **EN / 中文** 开关，点一下立即切换，不刷新页面。选择记在 `localStorage.tp_lang` 里，下次打开沿用；第一次访问会读浏览器语言，中文环境自动进中文。

跟着切的东西包括：所有界面文案、antd 组件自带文字（日期选择器、弹窗按钮、空状态）、星期（周四 / Thu）、时长单位（1 小时 30 分 / 1h 30m），**以及后端产生的规划提示和报错**。

后端不再返回英文句子，只返回**语义码 + 参数**：

```json
{"code":"warning.closesEarly","params":{"closesAt":"18:00"}}
```

文案全部在前端仓库的 `src/i18n/en.js` 和 `zh.js` 里，两份键一一对应。改文案不需要重启后端。

**没有翻译的部分**：景点名称和描述是数据库里的数据（`Ghibli Museum`、城市名 `Tokyo`），不是界面文案。要中文景点名需要给 `poi` 表加 `name_zh` / `description_zh` 列并按语言返回。

## 1. 第一次：把两个仓库拉下来

前后端是两个仓库。先 `cd` 到你想放代码的地方（比如 `cd ~/projects`），**在同一个父目录下**克隆两个：

```bash
git clone https://github.com/wangxc66/travelplanner.git
```

```bash
git clone https://github.com/wangxc66/travelplanner-frontend.git
```

`git clone` 会在**你当前所在的目录**下建一个和仓库同名的文件夹。不确定当前在哪就先敲 `pwd`（PowerShell 里是 `Get-Location`）。想指定位置就在后面加路径：

```bash
git clone https://github.com/wangxc66/travelplanner.git D:/code/travelplanner
```

前端依赖没有进仓库（600MB），先装一次：

```bash
cd travelplanner-frontend && npm install
```

后端不用装 —— Gradle wrapper 第一次运行会自己下载依赖。

## 2. 启动

开两个终端，各自 `cd` 进对应的目录。**后端先起**（前端要连它）。

终端 1，在 `travelplanner/` 里：

```bash
./gradlew bootRun
```

看到 `Started TravelPlannerApplication in X seconds` 就是好了。

终端 2，在 `travelplanner-frontend/` 里：

```bash
npm start
```

看到 `Compiled successfully!`，浏览器会自动打开 http://localhost:3000。首次 webpack 编译要 20 秒左右。

> PowerShell 里后端那条命令是 `.\gradlew.bat bootRun`。
> macOS / Linux 上如果报 `Permission denied`，执行 `chmod +x gradlew`。
> IntelliJ 里直接点 `TravelPlannerApplication.main()` 旁边的绿色 ▶ 更方便（重启快、能断点、红色 ■ 能干净地停掉）。

两个文件夹放哪、叫什么名字都无所谓 —— 前端通过 `http://localhost:8080` 找后端，不依赖目录结构。

## 3. 结束

每个终端里 `Ctrl+C`。后端偶尔会留下 java 进程不放端口，报 `Port 8080 was already in use` 时：

```bash
powershell -Command "Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }"
```

## 4. 数据会在重启后清空

数据库是 **H2 内存库**，后端进程一停，你注册的账号和建的行程全部消失，下次启动重新只播种 POI 目录（3 个城市 84 个景点）。

这是故意的：每次都从干净状态开始，演示可重复。**账号也会一起消失** —— 所以重启后端后，前端会自动检测到 token 失效、提示"session expired"并退回登录页，重新注册一个即可。

要让数据留下来，改 `travelplanner/src/main/resources/application.yml`：

```yaml
url: jdbc:h2:file:./data/travelplanner;MODE=PostgreSQL   # 原来是 jdbc:h2:mem:...
...
    hibernate:
      ddl-auto: update                                   # 原来是 create-drop
```

数据会落到 `travelplanner/data/`。想上真 PostgreSQL：`./gradlew bootRun --args='--spring.profiles.active=postgres'`（需要本地跑着 `travelplanner` 库）。

### 看数据库里的内容

后端跑着的时候，有两条路。

**① 内置网页控制台**（谁都能用，最省事）

打开 http://localhost:8080/h2-console，填：

- JDBC URL：`jdbc:h2:mem:travelplanner`
- User Name：`sa`
- Password：留空

**② IntelliJ / DataGrip / DBeaver 等外部工具**

嵌入式内存库活在后端 JVM 里，外部进程本来连不上，所以后端额外在 **9092** 端口开了一个 H2 TCP 服务把它暴露出来（只监听 loopback）。启动日志里会打印连接串。IntelliJ 里 `Database` 面板 → `+` → `Data Source` → `H2`：

- Connection type：**Remote**
- URL：`jdbc:h2:tcp://localhost:9092/mem:travelplanner`
- User：`sa`，密码留空
- 首次会提示下载 H2 driver，点 Download

> IntelliJ 的 Database 面板是 **Ultimate 版专属**，Community Edition 没有（可装 Database Navigator 插件代替）。
> 不需要这个端口就设 `travelplanner.h2.tcp.enabled: false`。

五张表：`APP_USER`、`CITY`、`POI`、`TRIP`、`ITINERARY_ITEM`。刚启动时是 3 个城市 / 84 个 POI / 0 用户 / 0 行程。

```sql
select id, username, display_name, password from app_user;   -- 密码是 BCrypt 哈希，不是明文
select day_index, seq, poi_id from itinerary_item where trip_id = 1 order by day_index, seq;
```

---

## 5. 第一次用，按这个顺序走

### ① 注册

打开 http://localhost:3000，默认就停在 **Create account**。用户名密码随便填（只存在你自己的本地库里）。

### ② 建行程

没有任何行程时，**New trip** 对话框会自己弹出来：

- 选城市：🗼 Tokyo(30) / 🌉 San Francisco(28) / 🥐 Paris(26)
- 行程名可以留空，会自动命名成 "3 days in Tokyo"
- 天数拖 1–15
- 选出发日期

### ③ Explore 标签：找景点

- 搜索框输关键词（名称、分类、描述都能命中，比如 `museum`、`temple`、`sushi`）
- 下面的下拉框按分类筛（Landmark / Museum / Park / Food / Shopping / Nightlife / Temple / Viewpoint）
- 每张卡片显示：分类、评分、**建议游玩时长**、**营业时间**
- 点卡片 → 地图飞到那个点
- 点 **+ Day N** → 加进当前选中的那一天（按钮上的 N 就是当前天）
- 已经加过的景点，按钮变成灰色的 `Day 2`，告诉你它在第几天

> **故意排乱一点**。随便按想到的顺序加 5、6 个点 —— 后面 Optimize 的效果才明显。

### ④ Itinerary 标签：排一天

顶部是 **Day 药丸**，每个下面有一条负载条（蓝色=正常，橙色=超过一天的容量）。点药丸切换当前天。

每个景点行显示 **到达–离开时刻** 和游玩时长，行与行之间那条虚线就是路程：`↓ 23 min · 4.9 km`，还挂着 **Uber / Lyft** 深链（点开是那一段的叫车页面）。

能做的操作：

| 操作 | 怎么做 |
|---|---|
| 调整一天内的顺序 | 拖左边的 `⠿` 手柄 |
| 挪到别的一天 | 把 `⠿` 拖到上面别的 Day 药丸上，或点 `⋯` → Move to day N |
| 钉住某个点 | 点 `📎` 变成 `📌`。Optimize 不会动被钉住的位置 |
| 删掉 | 点 `✕` |
| 换出行方式 | 🚶 Walk / 🚇 Transit / 🚗 Ride |

### ⑤ ⚡ Optimize day

重排当前这一天，目标是**在不违反营业时间的前提下，尽早结束这一天**。

它不是单纯的"最短路径"。纯按通行时间最优会把 18:00 才开门的酒吧排在早上第一个 —— 几何上漂亮，实际上废掉。所以目标函数是字典序的 **(闭馆违规分钟数, 当天结束时刻)**，等待时间自然算进结束时刻里。

点完看这几处变化：黄色的时间冲突警告消失、`on the move` 的时间下降、地图上的橙线从乱绕变成顺路。

### ⑥ ⚖ Rebalance trip

把超载那天末尾的景点挪到还有空的那天，然后把受影响的每一天重新 Optimize 一遍。

### ⑦ 提示栏

顶部的黄色/蓝色提示是主动给的，不只是报错：

- `Day 1: This day runs until 22:28` —— 这天排太满
- `Day 1 is 3h heavier than day 3. Move "X" to day 3?` —— 点 **Do it** 一键执行
- `Day 3 has nothing planned yet` —— 点 **Fill it** 跳到那天

计划正常时提示栏是空的（只有某天真的超过 100% 才会提醒你挪，不会没事唠叨）。

### ⑧ 右上角

- 行程下拉框切换多个行程
- `3 days · start 9:00 ⌄` 点开可以改总天数和每天的出发时间。**缩短天数不会删景点** —— 超出的会折进新的最后一天
- 头像 → Sign out

---

## 6. 地图和真实路线

地图右上角的 badge 会明确告诉你当前是什么状态，例如 `Open basemap · real routes`。两件事是**独立**的：

**底图** —— 配了浏览器 key 用 Google Maps，没配用 OpenStreetMap。两者的覆盖层完全一样（编号水滴 pin、橙色路线、分类着色的候选点）。

```bash
# 在前端仓库里
cp .env.example .env      # 然后填 REACT_APP_GOOGLE_MAPS_API_KEY=...，重启 npm start
```

**路线几何** —— 由后端提供，分三档，启动日志会打印用了哪档：

| 档位 | 条件 | 几何 | 时长 |
|---|---|---|---|
| Google Routes API | 配了服务端 key | 真实 | 真实（含公交、路况） |
| **OSRM** | **默认，无需 key** | **真实街道** | 驾车真实；步行/公交按真实路网距离换算 |
| 直线估算 | OSRM 关掉或不可达 | 直线 | 直线距离估算 |

想用 Google（Cloud 项目里要启用 **Routes API**，注意矩阵按元素计费）：

```bash
# 在后端仓库里
GOOGLE_MAPS_API_KEY=你的key ./gradlew bootRun
```

要知道的两点：默认这档的公交路线画的是**驾车路网的形状**（OSRM 完全不知道轨道交通的存在）；`router.project-osrm.org` 是公益 demo 服务器，没有 SLA、不能用于生产，正经用要自己起一个（`OSRM_BASE_URL=http://localhost:5000`）。

---

## 7. 跑测试

```bash
./gradlew test
```

9 个测试。`RoutePlannerTest` 覆盖规划器最关键的行为（乱序共线点变单向扫描、夜场推到最后、不排到闭馆之后、优化后通行时间不增、钉住的首站不动），`PolylineCodecTest` 用 Google 官方规范的参考样例验证折线编解码。

---

## 8. 出问题先看这里

| 现象 | 原因 / 处理 |
|---|---|
| 页面提示 `session expired`，退回登录页 | 后端重启了，内存库里的账号没了。重新注册一个 |
| 登录后一直转圈 / 请求 401 | 后端没起来或还没起完。等 `Started TravelPlannerApplication` |
| `Port 8080 was already in use` | 上一个后端进程没退，用第 3 节那条命令杀掉 |
| 地图空白 | 网络到 OSM 瓦片服务不通；换 Google key 或检查网络 |
| 路线是直线 | 后端启动日志看第 6 节那张表是哪一档；OSRM 不可达会降级，会打 WARN 日志 |
| 搜不到任何景点 | 确认后端 `GET /api/cities` 返回 3 个城市且 poiCount 不是 0 |
| 加景点报 `That place is not in X` | 那个景点属于别的城市。一个行程只能规划一个城市（MVP 范围） |

更细的架构和 API 说明见两个仓库各自的 `README.md`。
