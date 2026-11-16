<!-- Modified by Tadolio contributors (2026) -->
# Tadolio modifications

## T47 one-level friend invitations (2026-11-15)

- Add explicit, immutable referrer binding with number-age, official-number and governance checks, seven-day defaults and source-network snapshots.
- Recognize UTC+8 usage days from forwarded mining requests and server-stored T06 self-reported tasks through a proven public identity link.
- Issue configurable friend and miner rewards through the shared pending ledger, global daily scaling, administrator corrections and public source aggregates.
- Add optional onboarding and number-page entry, a settings-risk agent tool requiring a trusted first-person user statement, and a scheme-link confirmation screen.
- Add invitation counts and invited numbers, Chinese and English copy, incremental storage and regression coverage.

## T46 official account labels (2026-11-15)

- Annotate post reads, lookup, inbox senders, provider users, cosign signers, transfer lookups and coin counterparties with platform-owned official metadata.
- Display localized official prefixes on post cards, cosign notifications, inbox senders and conversation headers using one formatter.
- Carry official metadata through agent tools, document its authority and prevent sender plaintext and legacy message caches from setting the badge.
- Update the four tool prompt resources and their description/hash/size snapshots for the official-field contract.
- Add hub and Android regression coverage.

## T45 miner compute and daily issuance (2026-11-14)

- Meter first-delivered request ciphertext bytes, bind compute/operator node recipients with number-owner confirmation, and enforce shared payer/node daily limits.
- Add a shared attached SQLite issuance ledger for points and hub, proportional daily settlement, seven-day confirmation, failed-audit invalidation and anonymous daily totals.
- Keep self-use task scoring and set production defaults to the requested 10 coins per task, 20 tasks per day and seven-day pending period.
- Credit confirmed miner issuance idempotently and use confirmed earnings for jury qualification without requiring a prior wallet read.
- Add a local encrypted metered-API miner configuration, explicit daily budget and cost consent, signed E2E node worker, power/network conditions and a foreground stop notification.
- Add English, simplified and traditional Chinese miner copy and three cosign-page entry links.
- Retain earlier vision-review notes below as history; this implementation follows the current user message's explicit November 14 model scope.

## T43 general post cosigning (2026-11-14)

- Add opt-in post cosigning, independently counted agent sign/decline and human signatures, with additive hub migrations.
- Preserve signed device request evidence for offline P-256 verification and expose only number suffixes publicly.
- Add independent agent sign, decline and withdrawal tools, using the actual runtime model name without owner approval.
- Extend tool-free standby decisions, retain per-item parsing and retries, and skip pure-community standby cosigning.
- Send one quiet notice per post with withdrawal and persist notice reservations across restarts.
- Provide English, Simplified Chinese and Traditional Chinese notice text, with English fallback for other languages.
- Add a framework-free public web page, local seeded preview and preparation-only nginx configuration.
- Add regression coverage.

Tadolio builds on Mika under Apache-2.0. Original copyright notices are retained.
Changes cover branding, community-compute clients and safety boundaries, Tadolio identity,
agent inbox, coins, payment proposals, family assistance, onboarding and distribution.
Product documentation is in README.md and README.en.md. Attribution is in NOTICE.
The upstream documentation archive is in README.upstream.md.

## T40 number transfer and recovery (2026-11-12)

- Normalize JVM file-adapter paths and support basic Windows file attributes for full regression
  runs. Exercise screenshot directory escapes with a Windows junction when symbolic links need
  unavailable privileges, retaining the escape and outside-file assertions.
- Atomically replace the delivery ledger with NIO so committed batches can be updated on Windows.
- Add one-use, ten-minute transfer codes with old-phone signature confirmation and QR entry.
- Validate transfer and recovery checksums before persisting a handoff or using server attempts;
  bind pinned-number continuity checks to that number's signed movement certificates.
- Retry transfer-code collisions and cover device authentication, recovery holds across later
  transfers and explicit fresh-number allocation on a moved source phone.
- Add device-generated recovery codes, client-encrypted recovery archives, saved reminders,
  system credential verification for repeat viewing and revocation through regeneration.
- Persist the saved-reminder state per number so offline visits retain the backup reminder.
- Add additive hub migrations, atomic device reassignment, durable attempt limits, movement
  certificates, restricted audit metadata and a 72-hour outgoing-payment hold after recovery.
- Migrate inbox routing, contacts, block edges, family relationships, provider confirmations,
  community bindings and operator usage while preserving number-indexed balances and records.
- Keep hardware signing keys device-local. Restore historical agreement keys through encrypted
  archives so queued private mail remains decryptable, and verify signed identity continuity.
- Add English and Chinese copy with English fallback in other locales, hub regression tests
  and Android continuity, family-proof, navigation and copy tests.

## T41 upstream Mika 0.1.40 integration (2026-11-12)

- Integrate upstream features while retaining Tadolio identity, configurable product links,
  community/Hybrid privacy boundaries, notification/post triggers, coins, family assistance,
  onboarding, restart recovery, frozen provider factories and optional sandbox distribution.
- Combine multiple plan times and in-chat editing with old notification data and wake budgets.
  Preserve model cleanup in finally, task origins, token budgets and community retry handling.
- Gate automatic downloads and update UI on product configuration. Reject upstream Mika
  update APIs, cached releases and APK origins, retaining the Mika repository for attribution.
- Exclude native calls and external schedule broadcasts in community mode, including runtime
  mode changes. Preserve own-model calls and WeChat image import/export behind admission checks.
- Isolate hub transports from user-installed CA roots. Keep upstream user-CA support for model
  servers and browsers. Gate terminal entry and PTY sessions on the optional sandbox switch.
- Keep new runtime permission requests behind their corresponding explicit feature actions.
- Apply Tadolio branding to new UI, self tools, personal assistants and model prompts.
  Migrate both upstream default call greetings while preserving user-configured custom greetings.
- Preserve upstream README prose in README.upstream.md, synchronize translation fingerprints
  and include the xterm.js MIT notice. Keep upstream project and author attribution.
- Model-facing prompts, bundled knowledge and exact generated snapshots register modifications
  here to preserve their content format, following the existing T20/T29 convention. Imported
  vendor assets, binary assets and strict JSON/lock data also register integration attribution here.
- Generated test evidence and baseline failure lists register their attribution here as data artifacts.
- Add source headers to changed code, resources, scripts and documentation. Text files use LF.
- Complete one full merged test run, then targeted regressions for prefix positions, resource
  parity and self-check branding. Preserve the full XML alongside targeted overlays.
- Add explicit English fallbacks for local features in the new Persian locale and retain local
  search resource keys across languages. Align instrumented about-page tests with product links.
- Regenerate prompt/loop snapshots and record tool byte costs for own defaults, calls opt-in
  and community mode.

## T19 community compute failure recovery (2026)

- Convert all community interceptor failures to IOException, discard and asynchronously close
  failed sessions, and close initialized task models in finally even during cancellation.
- Reclaim idle compute/operator hub sessions after five minutes; protect pending relays and
  reset idle time on completion while retaining the two-hour lifetime limit.
- Pause miners through the existing authenticated heartbeat after upstream 401/403.
- Show community-specific failure copy using the community_ exception prefix in the cause chain;
  preserve own-model copy and provide English fallback for other supported languages.
- Add Android/core, hub and miner regressions.
- Permit release Kotlin compilation without signing credentials while retaining the signing
  configuration check for release packaging and signing tasks.

## Wireless-debugging pairing label (2026-11-12)

- The adb key label shown in the system's paired-devices list is now tadolio@<model> (was hivo@<model>);
  existing pairings keep working because devices trust the key itself, not the label.

## T39 automatic numbers and provider contact (2026-11-12)

- Provision Tadolio numbers silently through the existing device identity, with persistent failure backoff and compute-identity repair for existing users.
- Require number-owner confirmation before a provider card exposes a number or receives provider revenue sharing.
- Add authenticated current-provider user lists, binding timestamps and operator usage statistics through additive SQLite migrations.
- Allow confirmed providers to contact their current bound users with a 500-message daily cap, block enforcement and trusted source labels in the inbox and agent context.
- Add provider-card confirmation, served-user conversations and bilingual binding disclosure to the Android UI.
- Add hub and Android regressions.

## T40 number transfer and recovery (2026-11-12)

- Add restart-safe hub migrations, atomic number/device migration, signed transfer approval, recovery verification, persistent attempt limits, and audit records. No coin hold after recovery (owner decision 2026-11-12).
- Preserve contacts, encrypted inboxes, family trust continuity and provider relationships across device changes.
- Add onboarding and number-page entries, transfer QR codes, recovery-code storage reminders, device-unlock checks and Chinese/English copy with English fallbacks.
- Add hub and Android regression coverage.
- Repair Windows JVM file-adapter path/attribute handling, reject directory junctions before writes, and use atomic outbox replacement for the required 0.1.38 baseline regression run. Screenshot escape coverage uses a directory junction when Windows symlink privileges are unavailable.

## T42 chat attachments, shared camera and number move pages (2026-11-13)

- Store sandbox-disabled chat attachments in conversation-scoped private attachment directories,
  using the existing file gate, import limits and legacy workspace fallback. Keep Linux execution disabled.
- Supply attached images directly to the model and text-file content as user input. Report unavailable vision.
- Add an on-composer, once-per-phone community photo notice, consumed after accepted image submission.
- Allow explicitly attached user images through community transport, retaining screenshot and sensitive-text restrictions.
  Preserve the existing WeChat community task policy and Hybrid promotion to the user's own image model.
- Add a shared Camera2 preview, ZXing QR scanning and full-resolution JPEG capture without dependencies.
- Provide three number-page entries and separate transfer and recovery inputs, retaining repeat-view device unlock.
  Reuse active outgoing transfer codes across page reopenings and retain pending confirmations.
- Add focused regression tests and baseline-version/output arguments to the existing comparison script.
- About page: data note describes own-key, community compute and Tadolio servers accurately; Mika attribution is plain text without upstream link; author line removed.


## T45 vision conflict review (2026-11-14)

- Pause implementation under the requested vision-conflict rule pending confirmation of the updated model scope.

## T44 cosign expansion (2026-11-14)

- Allow three public-body-only community standby cosign decisions per phone per day, keeping negative-topic preferences local and ordinary recommendations on own models.
- Use the assigned node's signed Descriptor.model after inference for community cosign model names, including UI conversations.
- Show a dedicated withdrawal result with success, failure reason and retry.
- Add independent Ed25519 web agent identities, signatures, replay clocks, pagination and counts without allocating numbers.
- Add persistent hashed IPv4 /24 and IPv6 /64 quotas for number reservations, claims and web cosigns, respecting TRUST_PROXY.
- Display the count of App signatures from numbers created before the post, retain full App numbers, and document the web API in Chinese and English.
- Add incremental database migrations and focused regression coverage.


## T45 miner issuance and App mining (2026-11-14)

- Meter forwarded request ciphertext at the hub, bind receiving numbers, share daily user/node limits across miners, and exclude exam and audit sessions.
- Add shared, incremental issuance storage for proportional daily settlement, seven-day pending, audit invalidation and public aggregate reporting.
- Add administrator corrections by node, number and time, required reasons, idempotency, private audit records, and future-credit recovery of spent fraudulent earnings.
- Count confirmed earnings for juries and deduct revoked earnings. Preserve existing buyer-funded provider revenue shares.
- Add encrypted metered-API App mining, budget reservations, two-million-token defaults shared with CLI, public/manual price estimates, configurable battery/network/charging conditions, boot restoration and a foreground stop notification.
- Unify coin explanations across regions and locales, and provide three cosign entry links.

## T48 simpler App miner setup (2026-11-15)

- Replace mandatory endpoint/model/price/consent setup with five platform cards, Key links, preset models and a two-million-token daily limit.
- Keep endpoint, model, optional price overrides and power/network/boot controls in collapsed advanced settings; keep node/coin information in collapsed details.
- Validate credentials directly with the selected platform before saving/enabling. Use authenticated OpenRouter /key because its /models catalogue is public; use /models elsewhere.
- Centralize dated built-in prices and source links in MinerPlatforms.kt, retain live OpenRouter pricing, and permit unknown prices with a platform-bill notice.
- Preserve local encrypted credentials, admission exams, budget reservations, default boot restoration and cloud-miner attribution. Prevent saved credentials following endpoint changes.
- Add Chinese/English copy, traditional Chinese copy and English fallback tests.

## T50 three-step miner setup and running status (2026-11-15)

- Add safe-area top navigation, a keyboard-aware fixed action area, six wrapping platform choices, inline custom API fields and explicit clipboard paste.
- Replace raw token entry with presets and decimal ten-thousand/million-unit input. Keep the balance-spending notice visible and reveal price estimates on demand.
- Show running state, usage progress, coin totals, boot control and node details, with live daily-limit updates that preserve encrypted credentials and identity.
- Record measured service pause reasons and T51 heartbeat examStatus locally, including exam failure and platform review. Preserve qualification gates and document unavailable ordinary-task/audit classification.
- Serialize configuration updates across store instances and atomically replace encrypted files. Add conversion, copy, state and live-budget regression coverage.


## T53 服务商自助入驻（2026-11-15）

- 新增服务商列表、邀请码直达与完整名片确认，展示 0–100% 分成、官方标记、有效用户粗档、平台/模型及满员状态。绑定前说明号码可见、服务商可发消息、可解绑与拉黑。
- 首次设置可填写服务商邀请码。复用 ReferralLink 的解析入口增加 operator 深链与 HTTPS 草稿落地页分享，不读取剪贴板。
- 已确认 operator 名片的号码主人在服务商用户页查看自己的邀请码、有效/原始用户数、列表资格与考试状态，并复制或分享。编辑名片和重置邀请码由电脑节点身份完成。
- 绑定提交当前看到的分成，Hub 比例变化后要求重新确认。名片页及服务商页解释调低立即生效、调高须重新同意。
- 显示服务商当日额度用尽、次日恢复的明确提示。新增中英文资源，其他语言使用英文回退。
- 新增离线目录映射、邀请码、同意绑定、举报、链接、额度原因码、文案回退测试。真机截图与线上验收由总指挥执行。

## T55 (2026-11-15)

App 服务商身份、独立本地加密配置、名片与邀请码、显式逐次社区回退；模型平台文案及 JVM 回归。

- `mika/app/src/main/AndroidManifest.xml`
- `mika/app/src/main/java/com/wangzan101/mika/community/CommunityClient.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/CommunityClients.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/MinerActivity.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/MinerService.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/MinerStore.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/MinerWorker.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/OperatorForm.kt`
- `mika/app/src/main/java/com/wangzan101/mika/community/OperatorOfflineActivity.kt`
- `mika/app/src/main/res/values-fa/strings_tadolio.xml`
- `mika/app/src/main/res/values-zh/strings.xml`
- `mika/app/src/main/res/values-zh/strings_operators.xml`
- `mika/app/src/main/res/values-zh/strings_phone_operator.xml`
- `mika/app/src/main/res/values/strings.xml`
- `mika/app/src/main/res/values/strings_operators.xml`
- `mika/app/src/main/res/values/strings_phone_operator.xml`
- `mika/app/src/test/java/com/wangzan101/mika/community/OperatorFormTest.kt`
- `mika/app/src/test/java/com/wangzan101/mika/community/OperatorHubTest.kt`
- `mika/app/src/test/java/com/wangzan101/mika/community/OperatorOfflineTest.kt`
- `mika/app/src/test/java/com/wangzan101/mika/i18n/StringResourcesTest.kt`
- `mika/core/src/main/kotlin/com/wangzan101/mika/core/agent/StopRules.kt`
- `mika/core/src/main/resources/i18n/user-zh.json`


## T56 — 2026-11-15

当矿工文案、填写与运行布局、侧栏入口及状态、模型平台术语、长辈模式首页与开启确认。

- `app/src/main/java/com/wangzan101/mika/community/MinerActivity.kt`
- `app/src/main/java/com/wangzan101/mika/community/MinerPresentation.kt`
- `app/src/main/java/com/wangzan101/mika/ui/ChatDrawer.kt`
- `app/src/main/java/com/wangzan101/mika/ui/CommunityControls.kt`
- `app/src/main/java/com/wangzan101/mika/ui/ElderControls.kt`
- `app/src/main/java/com/wangzan101/mika/ui/ElderHome.kt`
- `app/src/main/java/com/wangzan101/mika/ui/MainActivity.kt`
- `app/src/main/java/com/wangzan101/mika/ui/SettingsScreen.kt`
- `app/src/main/res/values-ar/community.xml`
- `app/src/main/res/values-b+zh+Hant/community.xml`
- `app/src/main/res/values-b+zh+Hant/strings.xml`
- `app/src/main/res/values-b+zh+Hant/strings_about.xml`
- `app/src/main/res/values-b+zh+Hant/strings_hivo_t09.xml`
- `app/src/main/res/values-b+zh+Hant/strings_miner.xml`
- `app/src/main/res/values-b+zh+Hant/strings_models.xml`
- `app/src/main/res/values-b+zh+Hant/strings_settings.xml`
- `app/src/main/res/values-b+zh+Hant/strings_setup.xml`
- `app/src/main/res/values-b+zh+Hant/strings_status.xml`
- `app/src/main/res/values-b+zh+Hant/strings_usage.xml`
- `app/src/main/res/values-b+zh+Hant/strings_voice.xml`
- `app/src/main/res/values-es/community.xml`
- `app/src/main/res/values-in/community.xml`
- `app/src/main/res/values-ja/community.xml`
- `app/src/main/res/values-ko/community.xml`
- `app/src/main/res/values-pt/community.xml`
- `app/src/main/res/values-ru/community.xml`
- `app/src/main/res/values-zh/community.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values-zh/strings_about.xml`
- `app/src/main/res/values-zh/strings_hivo_t09.xml`
- `app/src/main/res/values-zh/strings_miner.xml`
- `app/src/main/res/values-zh/strings_models.xml`
- `app/src/main/res/values-zh/strings_operators.xml`
- `app/src/main/res/values-zh/strings_phone_operator.xml`
- `app/src/main/res/values-zh/strings_settings.xml`
- `app/src/main/res/values-zh/strings_setup.xml`
- `app/src/main/res/values-zh/strings_status.xml`
- `app/src/main/res/values-zh/strings_t56.xml`
- `app/src/main/res/values-zh/strings_usage.xml`
- `app/src/main/res/values-zh/strings_voice.xml`
- `app/src/main/res/values/community.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/strings_about.xml`
- `app/src/main/res/values/strings_hivo_t09.xml`
- `app/src/main/res/values/strings_miner.xml`
- `app/src/main/res/values/strings_models.xml`
- `app/src/main/res/values/strings_operators.xml`
- `app/src/main/res/values/strings_phone_operator.xml`
- `app/src/main/res/values/strings_settings.xml`
- `app/src/main/res/values/strings_setup.xml`
- `app/src/main/res/values/strings_status.xml`
- `app/src/main/res/values/strings_t56.xml`
- `app/src/main/res/values/strings_usage.xml`
- `app/src/main/res/values/strings_voice.xml`
- `app/src/test/java/com/wangzan101/mika/community/MinerRedesignTest.kt`
- `app/src/test/java/com/wangzan101/mika/community/MinerSetupTest.kt`
- `app/src/test/java/com/wangzan101/mika/community/T56ClarityTest.kt`
- `app/src/test/java/com/wangzan101/mika/community/MinerTest.kt`
- `app/src/test/java/com/wangzan101/mika/i18n/StringResourcesTest.kt`
- `app/src/test/java/com/wangzan101/mika/ui/ModelSetupNetworkTest.kt`
- `app/src/test/java/com/wangzan101/mika/ui/ModelSetupViewModelTest.kt`

## T59 多 Key 与备用模型

- 模型平台支持按顺序保存多个 Android Keystore 加密 Key，兼容旧单 Key 数据，提供末四位、可用状态、删除和排序。
- 共用 HTTP 错误分类，仅凭据失效、额度用完及限流触发同请求换 Key。限流冷却 60 秒，失效与额度状态在编辑或本地次日恢复，网络、服务端及内容错误沿用原处理。
- 主模型支持选择用户自带备用模型，任务内只提示一次。混合模式中的敏感 App 继续使用用户自带模型，备用模型选择拒绝社区算力。
- 系统切换提示在聊天显示，金币上报继续按操作步数计算。
- 手机矿工与服务商支持同平台同模型最多五个 Key，全部不可用时暂停并说明原因，显示当前 Key 序号，沿用合计日用量和原登记身份。
- 新增各语言文案以及分类、冷却、迁移、备用切换、隐私边界和矿工回归测试。

## T60 首次设置简化

- 欢迎、社区算力和默认模型接入合并为一屏，默认显示任务可见性告知，完整说明与服务商邀请码可展开。服务商确认继续显示同意与号码可见性说明。
- 共用 SetupPage 展示简短说明、一个主按钮及右上角朗读开关。模型账号选择合并，上手填 Key 表单隐藏社区控件与用途卡，设置阶段取消按住说话。
- 手机操作由任务触发。无障碍被拦后先引导解除限制，再提供先教学、后配对的通知填码路径。配对后等待助手自动开启无障碍，超时提供手动入口。
- 自动连接保留密码与允许弹窗的人工操作，对无线调试允许项显示高亮。保留旧清单、USB 启动、家人协助和诊断复制。
- 电池提示推迟到首次定时计划或后台连接丢失，由用户通过系统弹窗允许。推荐人提示在任务有结果后依据号码服务的七天绑定窗口显示，可永久关闭。设置完成直接回对话，首页示例与关于页社交入口保留。
- 补齐简中、繁中和英文文案，其他语言回退英文，覆盖路由、欢迎默认接入、权限边界和推迟条件回归测试。

## T61 社区模式告知与混合模式移除

- 社区模式读屏、截图、看图、点按、输入与打开 App 沿用普通手机端口，不再按隐私名单或前台归属拦截、隐藏屏幕或剥离图片。图片使用继续受模型能力约束。
- 删除混合算力路由、模型包装、屏幕边界、设置入口、升级记录与多语言说明。旧混合选择字段在读取时忽略，保留已选社区算力。备用 Key 与自带备用模型沿用原有机制。
- 删除隐私 App 名单。只在开始使用免费模式时告知一次，任务进行中不再额外提示。
- 首次设置、展开说明、社区设置、官网与公开说明明确告知矿工可见任务和屏幕。相关文案提供简中、繁中、英文，其他语言使用英文回退。
- 用户手动禁用的 App、不可逆操作确认与现有脱敏继续生效。更新相关单元测试与提示词快照。
