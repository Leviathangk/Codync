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
- 自訂圖示現在只用在 bot 上，代理程式一律顯示自己的標誌。
- 只用 Cursor CLI 登入時，也能看到 Cursor 的用量。

### en-US
- Custom icons are now for bots only; agents always show their own logo.
- Cursor usage now shows when you sign in with the Cursor CLI only.
