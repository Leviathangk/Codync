<!--
Google Play release notes for the Android app. Write it in the change that bumps the version:
a `## <version>` section with one `### <locale>` per Play language (zh-Hant, published as zh-TW;
en-US), listing what Android users notice, each under 500 characters (Play's limit;
tools/play-submit.py cuts longer notes at the last whole line). tools/play-submit.py reads the
released version's section; a version without one gets the PRs' "What's New: Android" bullets,
else a generic "Bug fixes and improvements" line. Delete old sections.
-->

## 2.34.0
### zh-Hant
- 可以配對多台電腦：Bots 每台電腦一個標題，可收合、拖曳排序，頂端選單可篩選要顯示的電腦。
- Bot 詳情新增「記憶」頁，可查看關於你、這個 bot、筆記與做夢紀錄。
- 訊息框加入聽寫：裝置上辨識，或用你自己的 OpenAI／Gemini 金鑰轉錄。
- 新對話交接卡片可以保留原對話或編輯交接內容。
- 通話以來電樣式通知，可直接掛斷或靜音；新增 Claude Code、Codex 用量小工具。
- 設定加入「在 Google Play 評分」與「回報問題」；有新版本時會提醒。
- 掃描配對 QR code 更容易對焦。

### en-US
- Pair more than one computer, with a heading per computer in Bots and a filter at the top.
- Bot details gain a Memory page.
- Dictate into the message box, on device or with your own OpenAI or Gemini key.
- Keep or edit a fresh conversation's handoff.
- Calls show as an Android call with hang up and mute; new Claude Code and Codex usage widgets.
- Rate on Google Play and Report an issue in Settings, and a note when an update is out.
- The pairing camera focuses more easily.
