| # | 提问 | 期望 | 实际 | 命中 |
|---|---|---|---|---|
| 1 | 萝卜有哪些原神角色 | getPlayerCharacters(萝卜) | getPlayerCharacters(萝卜) | OK |
| 2 | 萝卜的雷电将军练得怎么样 | getCharacterDetail(雷电将军) | getAbyssReport(萝卜) & getCharacterDetail(雷电将军) & getDailyNote(萝卜) & getPlayerCharacters(萝卜) | MISS |
| 3 | 萝卜今天树脂还剩多少 | getDailyNote(萝卜) | getDailyNote(萝卜) | OK |
| 4 | 萝卜这期深渊打到哪了 | getAbyssReport(萝卜) | getAbyssReport(萝卜) | OK |
| 5 | 小明树脂满了吗 | getDailyNote(小明) | getDailyNote(小明) | OK |
| 6 | 查查小明的深渊 | getAbyssReport(小明) | getAbyssReport(小明) | OK |
| 7 | 萝卜的胡桃多少命 | getCharacterDetail(胡桃) | getCharacterDetail(胡桃) & getPlayerCharacters(萝卜) | MISS |
| 8 | 今天天气怎么样 | (none) | (none) | OK |
| 9 | 萝卜昨天签到成功了吗 | (none) | getDailyNote(萝卜) | MISS |
| 10 | 小明和萝卜谁的角色多 | getPlayerCharacters(小明) & getPlayerCharacters(萝卜) | getPlayerCharacters(萝卜) & getPlayerCharacters(小明) | OK |
