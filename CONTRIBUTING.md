# 参与开发

后端仓库。前端在 [travelplanner-frontend](https://github.com/wangxc66/travelplanner-frontend)，
两边的流程是一样。

## 第一次上手

```bash
git clone https://github.com/wangxc66/travelplanner.git
cd travelplanner
./gradlew bootRun
```

不用装 Java 之外的任何东西，也不用装数据库——默认跑的是内存里的 H2，重启就清空。
接口怎么调、怎么切换到 Postgres，看 [USAGE.md](USAGE.md)。

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

## 日常流程

1. **从最新的 main 开分支**，一人一分支，不要几个人共用一个：

   ```bash
   git checkout main && git pull
   git checkout -b feat/trip-search
   ```

   命名：`feat/` 新功能，`fix/` 修 bug，`docs/` 只改文档。

2. **小步提交。** 一个 commit 只做一件事，消息写「做了什么」而不是「改了哪个文件」：
   `Cache the travel matrix between POIs` 好过 `update RoutePlanner.java`。

3. **推上去，开 PR：**

   ```bash
   git push -u origin feat/trip-search
   ```

   PR 模板会自动带出来，照着填。CI 会自动跑 `./gradlew build`，绿了才能合。

4. **等一个 approve**，然后合进 main。合完删掉分支（GitHub 会给按钮）。

## 不要提交的东西

`.gitignore` 已经挡掉了大部分：`build/`、`.gradle/`、`.idea/`、`.env`、`*.mv.db`。
另外：**任何 API key、密码、真实的 JWT secret 都不要进仓库**，即使是在注释里。
本地要配就写到 `application-local.yml`（已 ignore）。

不小心提交了密钥的话，改掉它然后立刻在服务商那边吊销旧的——从 git 历史里删掉不等于它没泄露过，
仓库是 public 的。

## 冲突了怎么办

先在自己分支上把 main 同步进来，在本地解决完再推：

```bash
git checkout main && git pull
git checkout feat/trip-search
git merge main
```

解决冲突 → `git add` → `git commit` → `git push`。

不要用 `git push --force`，也不要在别人的分支上 rebase。

## 卡住了

在 issue 或者 PR 里直接 @wangxc66，把完整报错贴上来。
