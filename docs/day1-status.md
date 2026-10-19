# 第一天执行记录 — 2026-10-15

权威计划：PLAN.md，第13节。项目为TradeRecon，不是订单簿/撮合引擎。
本地交付路径：<project-root>。

## 已完成并实际验证
- Java 21 / Spring Boot 3.5.16 / Maven Wrapper工程及计划要求的依赖。
- PostgreSQL 17.6原生本机环境，迁移和运行用户分离，Flyway从空库创建V1。
- ExecutionKey、MoneyFields、TradeEvent、枚举、基础字段校验、纯Java匹配接口。
- 20个独立手写答案场景，37个CSV，包含UTF-8、引号和换行；生成器可重跑校验。
- 6项领域测试、4项认证/CSRF测试、4项真实数据库集成测试：14项全部通过，0失败、0跳过。
- 在桌面最终目录执行Maven clean verify成功，见evidence/day1-verify-native.log。
- 应用实际监听127.0.0.1:8080，数据库健康UP；浏览器登录页已检查并在聊天中截图。
- 真正停止/启动应用并重启PostgreSQL，已提交数据仍在，迁移校验正常，见evidence/day1-restart-native.txt。
- 计划、README、领域/API/架构/运行/版本文档、设计决策和中文学习指南。
- Git初始提交在交付收尾阶段创建；无远程发布。自动生成提交使用Codex本地身份，避免冒用用户Git身份。

## 尚未通过的第一天验收
- 本机没有Docker，Testcontainers及Compose路径已配置但未执行；CI工作流已写但未在远程运行。
- 因而今天是“本机原生PostgreSQL验证通过，容器复现待验证”，不是所有原计划门槛无条件通过。
- 第一张运行截图保存在本次聊天输出，尚未存成项目内PNG。
- LIVE供应商、账户及模型未配置，已预留HTTP适配接口，AI为OFF，无付费请求。
- 用户需亲自完成手算、代码解释、算法练习及录音；不能由生成代码代替学习验收。

## 真实排错记录
1. 正确/错误密码测试最初报编码器缺失。根因：保存BCrypt哈希但未注册对应PasswordEncoder。修复：注册BCryptPasswordEncoder Bean；原测试重跑通过。
2. PowerShell传递未加引号的Maven版本参数导致包装配置版本截断。修复：核对并锁定distributionUrl为3.9.11，随后实际使用包装脚本完成构建。
3. Windows运行中的jar被锁导致repackage无法重命名。修复：演示脚本运行.local中的独立副本；停止旧进程后clean verify通过。以后前台spring-boot:run不直接占用构建jar。

## 第二天入口
先补容器/CI复现验证，再做CSV解析、整批原子导入、事件与版本去重、版本投影和并发上传测试。
目前没有导入API、实际对账引擎、案件处理、恢复worker或AI能力；20例答案是将来的验收依据，不是已跑通的业务结果。
