<!--
Google Play release notes for the Android app. Write it in the change that bumps the version:
a `## <version>` section with one `### <locale>` per Play language (zh-Hant, published as zh-TW;
en-US), listing what Android users notice, each under 500 characters (Play's limit;
tools/play-submit.py cuts longer notes at the last whole line). tools/play-submit.py reads the
released version's section; a version without one gets the PRs' "What's New: Android" bullets,
else a generic "Bug fixes and improvements" line. Delete old sections.
-->

## 2.34.2
### zh-Hant
- 有很多 Docker 或虛擬機網路的電腦，現在也能配對與連線，不會再顯示配對碼已損毀。

### en-US
- Computers with many Docker or virtual machine networks now pair and connect, instead of reporting a damaged pairing code.
