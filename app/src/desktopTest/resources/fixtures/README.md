# 解析 fixture（离线测试样本）

每个源一个子目录：`fixtures/komiic/`、`fixtures/goda/`、`fixtures/mangabz/`、`fixtures/copymanga/`。

**放什么**：能让解析纯函数跑通的**最小真实样本**（HTML 片段 / JSON 响应），文件名带来源与日期，例如
`search-测试-2026-10-25.html`、`chapters-mid31020.json`。

**为什么**：解析函数（`parseMangabzSearch` / `parseCopymangaDecryptedPages` 等）都设计成
「字符串进、结构出」，用 fixture 测就不用联网、不怕站点抖动，改版时把新样本丢进来再改正则即可。

**纪律**：
- 只放**结构**，不要放完整页面（几 MB 的 HTML 会把仓库撑大）；截到能覆盖断言的那几个节点就够；
- 敏感内容（cookie、ticket、登录态）**一律删掉**；
- 样本对不上时先确认是不是站点真的改了，再改代码——**别为了让测试过而放宽断言**。
