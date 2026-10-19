# AI 配置与运行说明

## 已经写好的链路
案件详情 → 请求分析 → 数据库队列 → 只读模型适配器 → JSON 与引用校验 → 展示建议或回到模板。
先读 AiEvidence、AiOutputValidator，再读 AiService、LiveInvestigationProvider。匹配结果仍由原来的纯 Java 规则决定。

OFF 默认不调用；MOCK 是固定模拟响应；LIVE 才会请求 OpenAI。页面明确标模式。
模型没有 SQL、浏览器、命令执行或结案工具。它不知道 API key，也不会收到原始 notes。事件时间线由程序从冻结批次生成。
同一案件、证据哈希、提示版本、模式、模型命中缓存。新证据使旧分析显示 STALE；审核仍要验证当前版本。

## 如何本地配置真实模型
先停止已启动的后台应用，再在项目目录打开 PowerShell：
```powershell
.\scripts\stop-local-app.ps1
. .\scripts\local-env.ps1
. .\scripts\configure-ai.ps1
.\scripts\start-local.ps1
```
如果执行策略阻止脚本，可先打开仅本次会话允许执行的 PowerShell，勿修改整机策略。
配置脚本交互询问模式、准确模型 ID、USD 预算、经你核实的输入/输出单价、请求上限和付费确认。最后隐藏输入密钥。信息仅存在当前终端环境，不写入 Git，不在聊天里粘贴密钥。
需要支持 Chat Completions 和 json_schema 的模型；没有替你猜模型或价格。密钥未配置、未批准或价格/预算不完整时只会降级，不会付费。

## 预算边界
每项分析预留两次调用的保守成本，包含一次可能的瞬时错误重试；20 秒总调用时限，最多 20 项等待、2 项进行。失败且费用不确定时不返还预留金额，进程重启也不自动重新付费调用。
网页调用的账本在数据库；批量评估有独立累计文件账本。请把总预算分配给两个账本，不要误以为共用一个全局额度。还应设置供应商侧费用限制。预留额不是发票，价格输入错误会影响估算。

## 评估不是让模型给自己打分
```powershell
python scripts/evaluate_ai.py
# 配置并批准之后，才执行：
python scripts/evaluate_ai.py --live
```
默认只跑 MOCK。原始输入、输出、验证错误、延迟、实际返回模型标识与 usage 保存在 evidence/ai-mock 或 evidence/ai-live。
60 个冻结案例 + 10 个挑战逐个保留，不挑成功答案。人工审阅还需检查“引用虽存在，是否真的支持推理”。当前没有真实 AI 准确率或提速结果。
下一步用 20 组成对任务记录阅读、等待、修改和提交时间；不能把模型响应延迟当成人工处理时间。


