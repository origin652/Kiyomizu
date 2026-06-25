const STRINGS = {
  zh: {
    title: 'Kiyomizu 配置控制台',
    presetLabel: '代理模式',
    upstreamLabel: '上游地址 (Upstream URL)',
    presetAnthropic: 'Claude Cache Mode',
    presetCustom: 'Custom Upstream (Pass-through)',
    cacheNotApplicable: '此模式为纯透传：路径、头与请求体都原样转发',
    ttlLabel: '缓存 TTL',
    ttl5m: '5 分钟',
    ttl1h: '1 小时',
    ttlNone: '无缓存',
    modeLabel: '缓存模式',
    modeExplicit: 'explicit（手动打断点）',
    modeAutomatic: 'automatic（自动）',
    strategyLabel: '缓存策略',
    strategyPrefix: 'stable-prefix（均匀分布）',
    strategyLast: 'last（仅最后一条）',
    bpLabel: '断点数量（0-4）',
    cacheNote: '⚠ automatic 模式仅在 Anthropic 直連时生效',
    cacheDiagnosticsTitle: '缓存诊断',
    cacheDiagnosticsRefresh: '刷新',
    cacheDiagnosticsNote: '显示请求侧缓存 patch 信息，以及上游返回里能解析到的 usage 字段。unknown 表示响应里没有可解析的 usage。',
    cacheStatRequests: '请求数',
    cacheStatInput: '输入 Token',
    cacheStatRead: '缓存命中',
    cacheStatCreate: '缓存创建',
    cacheThTime: '时间',
    cacheThModel: '模型',
    cacheThPatch: 'Patch',
    cacheThBreakpoints: '断点',
    cacheThUsage: 'Usage',
    cacheThCache: '缓存 Token',
    noCacheDiagnostics: '暂无请求诊断。',
    save: '保存配置',
    saved: '已保存：',
    error: '错误：',
    dashboardTitle: '代理流量图',
    statusTitle: '系统状态',
    statusActive: '正常运行',
    statusConnected: '已连接',
    statusUpstream: '活动上游',
    statusCache: '智能缓存',
    statusCacheActive: '已启用',
    statusCacheInactive: '直接穿透',
    statusThinking: '思维链优化',
    statusThinkingActive: '已启用 (过滤老历史以稳定缓存)',
    diagramClient: 'AI 客户端',
    diagramProxy: 'Kiyomizu 代理',
    diagramUpstream: 'LLM 上游',
    diagramCache: 'Prompt 缓存',
    visualizerTitle: '缓存断点可视化 (Prompt Caching)',
    visualizerStable: '稳定前缀 (缓存范围)',
    visualizerTail: '动态尾部 (实时更新)',
    companionTitle: 'AI 伴侣核心设置（记忆与情感）',
    compEnableLabel: '启用记忆与亲密度功能',
    compSummaryUrlLabel: '摘要 API 端点',
    upstreamHint: '这里只填基础 URL。示例：Anthropic 用 https://api.anthropic.com，OpenAI 用 https://api.openai.com，OpenRouter 用 https://openrouter.ai/api。',
    compSummaryKeyLabel: '摘要 API 密钥',
    compSummaryModelLabel: '摘要模型',
    compSummaryUrlHint: 'Gemini 建议填基础 URL，例如 https://generativelanguage.googleapis.com。OpenAI / OpenRouter 兼容接口只填基础 URL，系统会自动补上 /v1/chat/completions。',
    compModelRecallToggleLabel: '启用模型参与召回',
    compRecallModelModelLabel: '召回模型',
    compRecallModelUrlLabel: '召回模型 API 端点',
    compRecallModelUrlHint: '留空则继承摘要端点。召回模型会读取物化记忆索引，并返回 JSON 检索计划。',
    compRecallModelKeyLabel: '召回模型 API 密钥',
    compRecallClearKeyLabel: '清除已保存召回密钥',
    compModelRecallFailureLabel: '召回失败熔断阈值 (1-20)',
    compModelRecallCooldownLabel: '召回熔断冷却秒数 (0-86400)',
    compModelRecallRetentionLabel: '召回 trace 保留条数 (1-5000)',
    compDecayHoursLabel: '遗忘（衰减）检查间隔 (小时)',
    compDecayRateLabel: '记忆衰减速度 (0-1)',
    compThresholdLabel: '遗忘阈值 (0-1)',
    compRecoveryLabel: '回忆恢复量',
    compInitialStrengthLabel: '初始记忆强度 (0-1)',
    compMaxStrengthLabel: '最大记忆强度 (0-1)',
    compIntimacyDecayLabel: '亲密度下降速度 / 24h',
    compTrustDownScaleLabel: '信任下跌放大系数 (0.1-5)',
    compTrustUpScaleLabel: '信任上涨缩放系数 (0.1-5)',
    compRecallMaxLabel: '普通召回节点上限 (0-20)',
    compTauLabel: '艾宾浩斯遗忘 τ（小时）',
    compSalienceKLabel: '情感显著度系数 k (0-10)',
    compDeepToggleLabel: '启用深度回忆',
    compDeepCandidatesLabel: '深度回忆候选 (1-100)',
    compDeepCluesLabel: '深度回忆线索 (1-20)',
    compPersonCluesLabel: '人物上下文线索 (0-10)',
    compBufferedToggleLabel: '写入前先进入观察缓冲区',
    compPromoteRepeatLabel: '重复几次后晋升 (1-20)',
    compSelfToggleLabel: '启用 self 记忆',
    compSelfDirectToggleLabel: '允许对话直接修改 self',
    compSelfRecallLabel: 'self 召回节点上限 (0-20)',
    compSelfRepeatLabel: 'self 重复晋升阈值 (1-20)',
    compProjectRepeatLabel: '项目事实重复晋升阈值 (1-20)',
    compWorkingSlotsLabel: '每项目工作记忆槽位 (1-20)',
    compObservationRetentionLabel: '观察保留天数 (1-365)',
    compLowConfidenceRetentionLabel: '低置信观察保留天数 (1-365)',
    compObservationCapLabel: '每日观察上限 (1-5000)',
    compPromotedCapLabel: '每日晋升节点上限 (0-500)',
    compDreamToggleLabel: '启用做梦',
    compMaintenanceToggleLabel: '启用自动整理',
    compMaintenanceAggressivenessLabel: '整理激进度',
    compMaintenanceAggressiveOption: '激进',
    compMaintenanceStandardOption: '标准',
    compDreamIdleLabel: '做梦空闲小时 (1-720)',
    compDreamDailyLabel: '每日做梦次数 (0-24)',
    compLongIdleLabel: '长期空闲暂停天数 (1-365)',
    compDreamTracesLabel: '召回注入梦痕迹数 (0-10)',
    compDreamBatchLabel: '做梦处理材料上限 (1-200)',
    compDreamDryLimitLabel: '每日 dry run 次数 (0-24)',
    compRecycleRetentionLabel: '回收区保留天数 (1-3650)',
    compSummaryPromptLabel: '摘要与评估系统提示词',
    compKeyStored: '密钥已保存；留空则保持不变',
    compKeyEmpty: '尚未保存密钥',
    compClearKeyLabel: '清除已保存密钥',
    compKeyPlaceholderStored: '如需替换，请输入新密钥',
    compKeyPlaceholderEmpty: 'API Key...',
    compRecallKeyStored: '召回密钥已保存；留空则保持不变',
    compRecallKeyEmpty: '未保存召回密钥；留空继承摘要密钥',
    compRecallKeyPlaceholderStored: '如需替换召回密钥，请输入新密钥',
    compRecallKeyPlaceholderEmpty: '留空继承摘要密钥',
    authTitle: '配置已上锁',
    authBody: '请输入配置密码后再读取或修改设置。',
    authPlaceholder: '配置密码',
    authUnlock: '解锁',
    authInvalid: '密码错误或缺失。',
    authSetupTitle: '设置配置密码',
    authSetupBody: '首次使用前请先设置配置密码。设置完成前无法读取或修改配置。',
    authSetupPlaceholder: '新配置密码',
    authSetupConfirmPlaceholder: '再次输入密码',
    authSetupSave: '设置密码',
    authSetupMismatch: '两次输入的密码不一致。',
    authSetupRequired: '请先设置配置密码。',
    authSetupSaved: '配置密码已设置。',
    authRemoteSetupDisabled: '远程首次设置已被禁用。请在启动时设置 KIYOMIZU_CONFIG_PASSWORD，或通过 localhost 完成首次设置。',
    secretShow: '显示密码',
    secretHide: '隐藏密码',
    passwordChangeTitle: '配置密码',
    passwordChangeBody: '修改用于解锁此设置面板的密码。',
    passwordChangeDisabledBody: '当前密码来自环境变量或系统属性，不能在网页中修改。',
    passwordCurrentLabel: '当前密码',
    passwordNewLabel: '新密码',
    passwordConfirmLabel: '确认新密码',
    passwordChangeButton: '修改密码',
    passwordChangeSaved: '配置密码已修改。',
    autosaveTitle: 'AutoSave',
    autosaveBody: '普通设置会在修改后自动保存。密钥、清除密钥和密码修改仍需手动操作。',
    autosaveOn: '开启',
    autosaveOff: '关闭',
    autosaveSaving: '保存中',
    autosaveSaved: '已保存',
    autosaveError: '保存失败',
    autosaveToggleLabel: '切换自动保存',
    exportBtn: '导出配置',
    importBtn: '导入配置',
    importSuccess: '配置导入成功。',
    importError: '导入失败：',
    companionPanelTitle: '伴侣状态',
    companionRefresh: '刷新',
    dreamRun: '立即做梦',
    dreamDryRun: '做梦 dry run',
    modelRecallLabel: '模型召回调试',
    modelRecallRefresh: '刷新召回',
    memoryIndexRebuild: '重建索引',
    modelRecallStatusLabel: '召回状态',
    modelRecallTracesLabel: '最近召回 trace',
    memoryIndexLabel: '记忆索引分段',
    noModelRecallStatus: '尚未加载召回诊断。',
    noModelRecallTraces: '暂无召回 trace。',
    noMemoryIndex: '尚未加载记忆索引。',
    selfMemoryLabel: 'Self 记忆',
    selfMemoryRefresh: '刷新 self',
    selfMemoryCreate: '创建 self 记忆',
    stableSelfLabel: '稳定 self',
    bufferedSelfLabel: '缓冲 self',
    dreamSelfLabel: '梦来源 self',
    conflictSelfLabel: 'self 冲突',
    selfEventsLabel: 'self 事件',
    noStableSelf: '暂无稳定 self 记忆。',
    noBufferedSelf: '暂无缓冲 self 观察。',
    noDreamSelf: '暂无梦来源 self 观察。',
    noConflictSelf: '暂无 self 冲突。',
    noSelfEvents: '暂无 self 事件。',
    archiveSelf: '归档',
    confirmSelf: '确认',
    revertSelf: '撤销',
    memoryQueuesLabel: '记忆队列',
    memoryQueuesRefresh: '刷新队列',
    observationsLabel: '观察缓冲区',
    recycleLabel: '回收区',
    noObservations: '暂无缓冲观察。',
    noRecycle: '回收区为空。',
    intimacyLabel: '亲密度',
    trustLabel: '信任度',
    memoryCountLabel: '图节点数：',
    relatedEdgesLabel: '图边数：',
    searchTermsLabel: '搜索词数：',
    workingMemoryLabel: '工作记忆：',
    affectLabel: '情感分布：',
    consolidationLabel: '最近深度回忆：',
    reflectionsLabel: '最近的日记',
    noReflections: '暂无日记。',
    logsPanelTitle: '最近请求',
    logsRefresh: '刷新',
    logThTime: '时间',
    logThMethod: '方法',
    logThPath: '路径',
    logThPatched: '已处理',
    logThModel: '模型',
    logThMsgs: '消息数',
    logThCache: '缓存♦',
    noLogs: '暂无请求记录。',
    navStatus: '系统状态',
    navCache: '缓存诊断',
    navCompanion: '伴侣',
    navLogs: '请求日志',
    navConfig: '配置',
    navMemories: '记忆管理',
    navMenu: '菜单',
    secBasicTitle: '基本设置',
    secClaudeTitle: 'Claude 缓存',
    secCompanionTitle: 'AI 伴侣核心',
    secSecurityTitle: '安全',
  },
  en: {
    title: 'Kiyomizu Configuration Console',
    presetLabel: 'Proxy Mode',
    upstreamLabel: 'Upstream URL',
    upstreamHint: 'Use the base URL only. Anthropic is https://api.anthropic.com, OpenAI is https://api.openai.com, OpenRouter is https://openrouter.ai/api.',
    presetAnthropic: 'Claude Cache Mode',
    presetCustom: 'Custom Upstream (Pass-through)',
    cacheNotApplicable: 'Pure pass-through mode: path, headers, and body are forwarded untouched',
    ttlLabel: 'Cache TTL',
    ttl5m: '5 minutes',
    ttl1h: '1 hour',
    ttlNone: 'No Cache',
    modeLabel: 'Cache Mode',
    modeExplicit: 'explicit (manual breakpoints)',
    modeAutomatic: 'automatic',
    strategyLabel: 'Cache Strategy',
    strategyPrefix: 'stable-prefix (evenly distributed)',
    strategyLast: 'last (last message only)',
    bpLabel: 'Breakpoints (0-4)',
    cacheNote: '⚠ automatic cache mode only works with Anthropic direct',
    cacheDiagnosticsTitle: 'Cache Diagnostics',
    cacheDiagnosticsRefresh: 'Refresh',
    cacheDiagnosticsNote: 'Shows request-side cache patching plus upstream usage fields when providers expose them. Unknown means the response did not include parseable usage.',
    cacheStatRequests: 'Requests',
    cacheStatInput: 'Input Tokens',
    cacheStatRead: 'Cache Read',
    cacheStatCreate: 'Cache Create',
    cacheThTime: 'Time',
    cacheThModel: 'Model',
    cacheThPatch: 'Patch',
    cacheThBreakpoints: 'Breakpoints',
    cacheThUsage: 'Usage',
    cacheThCache: 'Cache Tokens',
    noCacheDiagnostics: 'No request diagnostics yet.',
    save: 'Save Settings',
    saved: 'Saved: ',
    error: 'Error: ',
    dashboardTitle: 'Proxy Traffic Flow',
    statusTitle: 'System Status',
    statusActive: 'Running',
    statusConnected: 'Connected',
    statusUpstream: 'Active Upstream',
    statusCache: 'Smart Caching',
    statusCacheActive: 'Active',
    statusCacheInactive: 'Bypassed',
    statusThinking: 'Reasoning Optimization',
    statusThinkingActive: 'Active (Stripping thinking blocks)',
    diagramClient: 'AI Client',
    diagramProxy: 'Kiyomizu Proxy',
    diagramUpstream: 'LLM Provider',
    diagramCache: 'Prompt Cache',
    visualizerTitle: 'Cache Breakpoints Visualizer',
    visualizerStable: 'Stable Prefix (Cached)',
    visualizerTail: 'Dynamic Tail (Uncached)',
    companionTitle: 'AI Companion Core Settings',
    compEnableLabel: 'Enable Memory & Intimacy',
    compSummaryUrlLabel: 'Summarization Endpoint',
    compSummaryUrlHint: 'For Gemini, use the base URL like https://generativelanguage.googleapis.com. OpenAI and OpenRouter style providers can use a base URL; /v1/chat/completions is appended automatically.',
    compSummaryKeyLabel: 'Summarization API Key',
    compSummaryModelLabel: 'Summarization Model',
    compModelRecallToggleLabel: 'Enable Model Recall',
    compRecallModelModelLabel: 'Recall Model',
    compRecallModelUrlLabel: 'Recall Model Endpoint',
    compRecallModelUrlHint: 'Leave blank to inherit the summarization endpoint. The recall model reads the materialized memory index and returns a JSON retrieval plan.',
    compRecallModelKeyLabel: 'Recall Model API Key',
    compRecallClearKeyLabel: 'Clear stored recall key',
    compModelRecallFailureLabel: 'Recall Failure Threshold (1-20)',
    compModelRecallCooldownLabel: 'Recall Cooldown Seconds (0-86400)',
    compModelRecallRetentionLabel: 'Recall Trace Retention (1-5000)',
    compDecayHoursLabel: 'Decay Interval (Hours)',
    compDecayRateLabel: 'Memory Decay Rate (0-1)',
    compThresholdLabel: 'Forget Threshold (0-1)',
    compRecoveryLabel: 'Recall Recovery delta',
    compInitialStrengthLabel: 'Initial Strength (0-1)',
    compMaxStrengthLabel: 'Max Strength (0-1)',
    compIntimacyDecayLabel: 'Intimacy Decay Rate / 24h',
    compTrustDownScaleLabel: 'Trust Drop Scale (0.1-5)',
    compTrustUpScaleLabel: 'Trust Rise Scale (0.1-5)',
    compRecallMaxLabel: 'Recall Max Nodes (0-20)',
    compTauLabel: 'Ebbinghaus Decay τ (hours)',
    compSalienceKLabel: 'Affect Salience k (0-10)',
    compDeepToggleLabel: 'Enable Deep Recall',
    compDeepCandidatesLabel: 'Deep Recall Candidates (1-100)',
    compDeepCluesLabel: 'Deep Recall Clues (1-20)',
    compPersonCluesLabel: 'Person Context Clues (0-10)',
    compBufferedToggleLabel: 'Buffer memory before promotion',
    compPromoteRepeatLabel: 'Promote After Repeats (1-20)',
    compSelfToggleLabel: 'Enable Self Memory',
    compSelfDirectToggleLabel: 'Allow Direct Self Updates',
    compSelfRecallLabel: 'Self Recall Max Nodes (0-20)',
    compSelfRepeatLabel: 'Self Promote Repeats (1-20)',
    compProjectRepeatLabel: 'Project Fact Repeats (1-20)',
    compWorkingSlotsLabel: 'Working Slots / Project (1-20)',
    compObservationRetentionLabel: 'Observation Retention Days (1-365)',
    compLowConfidenceRetentionLabel: 'Low Confidence Retention Days (1-365)',
    compObservationCapLabel: 'Observations Per Day (1-5000)',
    compPromotedCapLabel: 'Promoted Nodes Per Day (0-500)',
    compDreamToggleLabel: 'Enable Dreaming',
    compMaintenanceToggleLabel: 'Enable Auto Maintenance',
    compMaintenanceAggressivenessLabel: 'Maintenance Aggressiveness',
    compMaintenanceAggressiveOption: 'Aggressive',
    compMaintenanceStandardOption: 'Standard',
    compDreamIdleLabel: 'Dream Idle Hours (1-720)',
    compDreamDailyLabel: 'Dreams Per Day (0-24)',
    compLongIdleLabel: 'Pause After Idle Days (1-365)',
    compDreamTracesLabel: 'Dream Traces in Recall (0-10)',
    compDreamBatchLabel: 'Dream Batch Nodes (1-200)',
    compDreamDryLimitLabel: 'Dream Dry Runs Per Day (0-24)',
    compRecycleRetentionLabel: 'Recycle Retention Days (1-3650)',
    compSummaryPromptLabel: 'Summarization System Prompt',
    compKeyStored: 'Key stored; leave blank to keep it',
    compKeyEmpty: 'No key stored yet',
    compClearKeyLabel: 'Clear stored key',
    compKeyPlaceholderStored: 'Enter a new key to replace it',
    compKeyPlaceholderEmpty: 'API Key...',
    compRecallKeyStored: 'Recall key stored; leave blank to keep it',
    compRecallKeyEmpty: 'No recall key stored; blank inherits summary key',
    compRecallKeyPlaceholderStored: 'Enter a new recall key to replace it',
    compRecallKeyPlaceholderEmpty: 'Blank inherits summary key',
    authTitle: 'Configuration Locked',
    authBody: 'Enter the config password before reading or changing settings.',
    authPlaceholder: 'Config password',
    authUnlock: 'Unlock',
    authInvalid: 'The config password is missing or incorrect.',
    authSetupTitle: 'Set Config Password',
    authSetupBody: 'Set a config password before first use. Settings stay locked until this is done.',
    authSetupPlaceholder: 'New config password',
    authSetupConfirmPlaceholder: 'Confirm password',
    authSetupSave: 'Set Password',
    authSetupMismatch: 'The two passwords do not match.',
    authSetupRequired: 'Set a config password first.',
    authSetupSaved: 'Config password set.',
    authRemoteSetupDisabled: 'Remote first-run password setup is disabled. Set KIYOMIZU_CONFIG_PASSWORD before startup, or complete first-run setup from localhost.',
    secretShow: 'Show password',
    secretHide: 'Hide password',
    passwordChangeTitle: 'Config Password',
    passwordChangeBody: 'Change the password used to unlock this settings panel.',
    passwordChangeDisabledBody: 'This password is controlled by an environment variable or system property, so it cannot be changed here.',
    passwordCurrentLabel: 'Current password',
    passwordNewLabel: 'New password',
    passwordConfirmLabel: 'Confirm new password',
    passwordChangeButton: 'Change password',
    passwordChangeSaved: 'Config password changed.',
    autosaveTitle: 'AutoSave',
    autosaveBody: 'Ordinary settings save automatically after changes. API keys, key clearing, and password changes still require an explicit action.',
    autosaveOn: 'On',
    autosaveOff: 'Off',
    autosaveSaving: 'Saving',
    autosaveSaved: 'Saved',
    autosaveError: 'Save failed',
    autosaveToggleLabel: 'Toggle autosave',
    exportBtn: 'Export Config',
    importBtn: 'Import Config',
    importSuccess: 'Config imported successfully.',
    importError: 'Import failed: ',
    companionPanelTitle: 'Companion State',
    companionRefresh: 'Refresh',
    dreamRun: 'Dream now',
    dreamDryRun: 'Dream dry run',
    modelRecallLabel: 'Model Recall Debug',
    modelRecallRefresh: 'Refresh recall',
    memoryIndexRebuild: 'Rebuild index',
    modelRecallStatusLabel: 'Recall Status',
    modelRecallTracesLabel: 'Recent Recall Traces',
    memoryIndexLabel: 'Memory Index Segments',
    noModelRecallStatus: 'No recall diagnostics loaded.',
    noModelRecallTraces: 'No recall traces yet.',
    noMemoryIndex: 'No memory index loaded.',
    selfMemoryLabel: 'Self Memory',
    selfMemoryRefresh: 'Refresh self',
    selfMemoryCreate: 'Create self memory',
    stableSelfLabel: 'Stable Self',
    bufferedSelfLabel: 'Buffered Self',
    dreamSelfLabel: 'Dream-source Self',
    conflictSelfLabel: 'Self Conflicts',
    selfEventsLabel: 'Self Events',
    noStableSelf: 'No stable self memory.',
    noBufferedSelf: 'No buffered self observations.',
    noDreamSelf: 'No dream-source self observations.',
    noConflictSelf: 'No self conflicts.',
    noSelfEvents: 'No self events.',
    archiveSelf: 'Archive',
    confirmSelf: 'Confirm',
    revertSelf: 'Revert',
    memoryQueuesLabel: 'Memory Queues',
    memoryQueuesRefresh: 'Refresh queues',
    observationsLabel: 'Buffered Observations',
    recycleLabel: 'Recycle Bin',
    noObservations: 'No buffered observations.',
    noRecycle: 'Recycle bin is empty.',
    intimacyLabel: 'Intimacy',
    trustLabel: 'Trust',
    memoryCountLabel: 'Graph nodes: ',
    relatedEdgesLabel: 'Graph edges: ',
    searchTermsLabel: 'Search terms: ',
    workingMemoryLabel: 'Working memory: ',
    affectLabel: 'Affect: ',
    consolidationLabel: 'Last deep recall: ',
    reflectionsLabel: 'Recent Reflections',
    noReflections: 'No reflections yet.',
    logsPanelTitle: 'Recent Requests',
    logsRefresh: 'Refresh',
    logThTime: 'Time',
    logThMethod: 'Method',
    logThPath: 'Path',
    logThPatched: 'Patched',
    logThModel: 'Model',
    logThMsgs: 'Msgs',
    logThCache: 'Cache♦',
    noLogs: 'No requests logged yet.',
    navStatus: 'System Status',
    navCache: 'Cache Diagnostics',
    navCompanion: 'Companion',
    navLogs: 'Request Logs',
    navConfig: 'Configuration',
    navMemories: 'Memories',
    navMenu: 'Menu',
    secBasicTitle: 'Basic Settings',
    secClaudeTitle: 'Claude Cache',
    secCompanionTitle: 'AI Companion Core',
    secSecurityTitle: 'Security',
  },
  ja: {
    title: 'Kiyomizu 設定コントロールパネル',
    presetLabel: 'プロキシモード',
    upstreamLabel: '上流URL',
    upstreamHint: 'ここにはベースURLだけを入れます。例: Anthropic は https://api.anthropic.com、OpenAI は https://api.openai.com、OpenRouter は https://openrouter.ai/api です。',
    presetAnthropic: 'Claude キャッシュモード',
    presetCustom: 'カスタム上流 (透過転送)',
    cacheNotApplicable: '完全な透過モードです。パス、ヘッダ、ボディをそのまま転送します',
    ttlLabel: 'キャッシュ TTL',
    ttl5m: '5 分',
    ttl1h: '1 時間',
    ttlNone: 'キャッシュなし',
    modeLabel: 'キャッシュモード',
    modeExplicit: 'explicit（手动ブレークポイント）',
    modeAutomatic: 'automatic（自動）',
    strategyLabel: 'キャッシュ戦略',
    strategyPrefix: 'stable-prefix（均等分布）',
    strategyLast: 'last（最後のメッセージのみ）',
    bpLabel: 'ブレークポイント数（0-4）',
    cacheNote: '⚠ automatic モードは Anthropic 直接接続時のみ有効',
    cacheDiagnosticsTitle: 'キャッシュ診断',
    cacheDiagnosticsRefresh: '更新',
    cacheDiagnosticsNote: 'リクエスト側の cache patch 情報と、上流レスポンスから解析できた usage を表示します。unknown は解析可能な usage が無かったことを示します。',
    cacheStatRequests: 'リクエスト数',
    cacheStatInput: '入力 Token',
    cacheStatRead: 'キャッシュ読込',
    cacheStatCreate: 'キャッシュ作成',
    cacheThTime: '日時',
    cacheThModel: 'モデル',
    cacheThPatch: 'Patch',
    cacheThBreakpoints: 'ブレークポイント',
    cacheThUsage: 'Usage',
    cacheThCache: 'キャッシュ Token',
    noCacheDiagnostics: 'リクエスト診断はまだありません。',
    save: '設定を保存',
    saved: '保存しました：',
    error: 'エラー：',
    dashboardTitle: 'プロキシトラフィックフロー',
    statusTitle: 'システムステータス',
    statusActive: '稼働中',
    statusConnected: '接続済み',
    statusUpstream: 'アクティブ上流',
    statusCache: 'スマートキャッシュ',
    statusCacheActive: '有効',
    statusCacheInactive: 'バイパス中',
    statusThinking: '思考ブロック最適化',
    statusThinkingActive: '有効 (思考タグを除去しキャッシュを安定化)',
    diagramClient: 'AI クライアント',
    diagramProxy: 'Kiyomizu プロキシ',
    diagramUpstream: 'LLM プロバイダー',
    diagramCache: 'プロンプトキャッシュ',
    visualizerTitle: 'キャッシュブレークポイント可視化',
    visualizerStable: '安定プレフィックス (キャッシュ対象)',
    visualizerTail: '動的テール (キャッシュ対象外)',
    companionTitle: 'AIコンパニオン設定 (記憶・感情)',
    compEnableLabel: '記憶＆親密度機能を有効化',
    compSummaryUrlLabel: '要約用 APIエンドポイント',
    compSummaryUrlHint: 'Gemini は https://generativelanguage.googleapis.com のようなベースURLを入れます。OpenAI / OpenRouter 互換はベースURLだけでよく、/v1/chat/completions は自動で足されます。',
    compSummaryKeyLabel: '要約用 APIキー',
    compSummaryModelLabel: '要約用 モデル名',
    compModelRecallToggleLabel: 'モデル参加の想起を有効化',
    compRecallModelModelLabel: '想起モデル',
    compRecallModelUrlLabel: '想起モデル API エンドポイント',
    compRecallModelUrlHint: '空欄の場合は要約エンドポイントを継承します。想起モデルは物化メモリ索引を読み、JSON の検索計画を返します。',
    compRecallModelKeyLabel: '想起モデル APIキー',
    compRecallClearKeyLabel: '保存済み想起キーを削除',
    compModelRecallFailureLabel: '想起失敗しきい値 (1-20)',
    compModelRecallCooldownLabel: '想起クールダウン秒数 (0-86400)',
    compModelRecallRetentionLabel: '想起 trace 保持数 (1-5000)',
    compDecayHoursLabel: '忘却（減衰）チェック間隔 (時間)',
    compDecayRateLabel: '記憶減衰スピード (0〜1)',
    compThresholdLabel: '忘却しきい値 (0〜1)',
    compRecoveryLabel: '想起時の記憶回復量 (0〜1)',
    compInitialStrengthLabel: '初期記憶強度 (0〜1)',
    compMaxStrengthLabel: '最大記憶強度 (0〜1)',
    compIntimacyDecayLabel: '疎遠化スピード (親密度低下/24h)',
    compTrustDownScaleLabel: '信頼低下倍率 (0.1-5)',
    compTrustUpScaleLabel: '信頼上昇倍率 (0.1-5)',
    compRecallMaxLabel: '通常想起ノード上限 (0-20)',
    compTauLabel: 'エビングハウス忘却 τ（時間）',
    compSalienceKLabel: '感情顕著度係数 k (0-10)',
    compDeepToggleLabel: '深い想起を有効化',
    compDeepCandidatesLabel: '深い想起候補 (1-100)',
    compDeepCluesLabel: '深い想起手がかり (1-20)',
    compPersonCluesLabel: '人物コンテキスト手がかり (0-10)',
    compBufferedToggleLabel: '昇格前に観察バッファへ保存',
    compPromoteRepeatLabel: '昇格する反復回数 (1-20)',
    compSelfToggleLabel: 'self 記憶を有効化',
    compSelfDirectToggleLabel: '会話から self を直接更新',
    compSelfRecallLabel: 'self 想起ノード上限 (0-20)',
    compSelfRepeatLabel: 'self 昇格反復閾値 (1-20)',
    compProjectRepeatLabel: 'プロジェクト事実の反復閾値 (1-20)',
    compWorkingSlotsLabel: 'プロジェクトごとの作業記憶枠 (1-20)',
    compObservationRetentionLabel: '観察保持日数 (1-365)',
    compLowConfidenceRetentionLabel: '低信頼観察の保持日数 (1-365)',
    compObservationCapLabel: '1日あたり観察上限 (1-5000)',
    compPromotedCapLabel: '1日あたり昇格ノード上限 (0-500)',
    compDreamToggleLabel: '夢を見る機能を有効化',
    compMaintenanceToggleLabel: '自動整理を有効化',
    compMaintenanceAggressivenessLabel: '整理の積極度',
    compMaintenanceAggressiveOption: '積極的',
    compMaintenanceStandardOption: '標準',
    compDreamIdleLabel: '夢を見る空き時間 (1-720)',
    compDreamDailyLabel: '1日あたり夢の回数 (0-24)',
    compLongIdleLabel: '長期未使用時の停止日数 (1-365)',
    compDreamTracesLabel: '想起に入れる夢の痕跡 (0-10)',
    compDreamBatchLabel: '夢で扱う材料上限 (1-200)',
    compDreamDryLimitLabel: '1日あたり dry run 回数 (0-24)',
    compRecycleRetentionLabel: 'リサイクル保持日数 (1-3650)',
    compSummaryPromptLabel: '要約＆評価用システムプロンプト',
    compKeyStored: 'キー保存済み。空欄ならそのまま維持します',
    compKeyEmpty: 'キーはまだ保存されていません',
    compClearKeyLabel: '保存済みキーを削除',
    compKeyPlaceholderStored: '置き換える場合だけ新しいキーを入力',
    compKeyPlaceholderEmpty: 'API Key...',
    compRecallKeyStored: '想起キー保存済み。空欄なら維持します',
    compRecallKeyEmpty: '想起キーは未保存です。空欄なら要約キーを継承します',
    compRecallKeyPlaceholderStored: '置き換える場合だけ新しい想起キーを入力',
    compRecallKeyPlaceholderEmpty: '空欄なら要約キーを継承',
    authTitle: '設定はロックされています',
    authBody: '設定の参照や更新を行う前に、設定用パスワードを入力してください。',
    authPlaceholder: '設定パスワード',
    authUnlock: '解除',
    authInvalid: '設定パスワードが違うか、送信されていません。',
    authSetupTitle: '設定パスワードを作成',
    authSetupBody: '初回利用の前に設定パスワードを作成してください。作成するまで設定の参照や更新はできません。',
    authSetupPlaceholder: '新しい設定パスワード',
    authSetupConfirmPlaceholder: 'もう一度入力',
    authSetupSave: 'パスワードを設定',
    authSetupMismatch: '2つのパスワードが一致しません。',
    authSetupRequired: '先に設定パスワードを作成してください。',
    authSetupSaved: '設定パスワードを作成しました。',
    authRemoteSetupDisabled: 'リモートからの初回パスワード設定は無効です。起動時に KIYOMIZU_CONFIG_PASSWORD を設定するか、localhost から初回設定してください。',
    secretShow: 'パスワードを表示',
    secretHide: 'パスワードを隠す',
    passwordChangeTitle: '設定パスワード',
    passwordChangeBody: 'この設定画面を解除するためのパスワードを変更します。',
    passwordChangeDisabledBody: 'このパスワードは環境変数またはシステムプロパティで固定されているため、画面からは変更できません。',
    passwordCurrentLabel: '現在のパスワード',
    passwordNewLabel: '新しいパスワード',
    passwordConfirmLabel: '新しいパスワードを再入力',
    passwordChangeButton: 'パスワードを変更',
    passwordChangeSaved: '設定パスワードを変更しました。',
    autosaveTitle: 'AutoSave',
    autosaveBody: '通常設定は変更後に自動保存します。APIキー、キー削除、パスワード変更は明示操作のままです。',
    autosaveOn: '有効',
    autosaveOff: '無効',
    autosaveSaving: '保存中',
    autosaveSaved: '保存済み',
    autosaveError: '保存失敗',
    autosaveToggleLabel: 'AutoSaveを切り替え',
    exportBtn: '設定をエクスポート',
    importBtn: '設定をインポート',
    importSuccess: '設定のインポートが完了しました。',
    importError: 'インポート失敗：',
    companionPanelTitle: 'コンパニオン状態',
    companionRefresh: '更新',
    dreamRun: '今すぐ夢を見る',
    dreamDryRun: '夢 dry run',
    modelRecallLabel: 'モデル想起デバッグ',
    modelRecallRefresh: '想起を更新',
    memoryIndexRebuild: '索引を再構築',
    modelRecallStatusLabel: '想起ステータス',
    modelRecallTracesLabel: '最近の想起 trace',
    memoryIndexLabel: 'メモリ索引セグメント',
    noModelRecallStatus: '想起診断はまだ読み込まれていません。',
    noModelRecallTraces: '想起 trace はまだありません。',
    noMemoryIndex: 'メモリ索引はまだ読み込まれていません。',
    selfMemoryLabel: 'Self 記憶',
    selfMemoryRefresh: 'self を更新',
    selfMemoryCreate: 'self 記憶を作成',
    stableSelfLabel: '安定 self',
    bufferedSelfLabel: 'バッファ self',
    dreamSelfLabel: '夢由来 self',
    conflictSelfLabel: 'self 衝突',
    selfEventsLabel: 'self イベント',
    noStableSelf: '安定 self 記憶はありません。',
    noBufferedSelf: 'バッファ中の self 観察はありません。',
    noDreamSelf: '夢由来 self 観察はありません。',
    noConflictSelf: 'self 衝突はありません。',
    noSelfEvents: 'self イベントはありません。',
    archiveSelf: 'アーカイブ',
    confirmSelf: '確認',
    revertSelf: '取り消し',
    memoryQueuesLabel: '記憶キュー',
    memoryQueuesRefresh: 'キューを更新',
    observationsLabel: '観察バッファ',
    recycleLabel: 'リサイクル',
    noObservations: 'バッファ中の観察はありません。',
    noRecycle: 'リサイクルは空です。',
    intimacyLabel: '親密度',
    trustLabel: '信頼度',
    memoryCountLabel: 'グラフノード数：',
    relatedEdgesLabel: 'グラフエッジ数：',
    searchTermsLabel: '検索語数：',
    workingMemoryLabel: '作業記憶：',
    affectLabel: '感情分布：',
    consolidationLabel: '最近の深い想起：',
    reflectionsLabel: '最近の日記',
    noReflections: 'まだ日記がありません。',
    logsPanelTitle: '最近のリクエスト',
    logsRefresh: '更新',
    logThTime: '日時',
    logThMethod: 'メソッド',
    logThPath: 'パス',
    logThPatched: '変換済み',
    logThModel: 'モデル',
    logThMsgs: 'メッセージ数',
    logThCache: 'キャッシュ♦',
    noLogs: 'まだリクエストがありません。',
    navStatus: 'システム状態',
    navCache: 'キャッシュ診断',
    navCompanion: 'コンパニオン',
    navLogs: 'リクエストログ',
    navConfig: '設定',
    navMemories: '記憶管理',
    navMenu: 'メニュー',
    secBasicTitle: '基本設定',
    secClaudeTitle: 'Claude キャッシュ',
    secCompanionTitle: 'AIコンパニオン',
    secSecurityTitle: 'セキュリティ',
  },
};


function detectLang() {
  const saved = localStorage.getItem('proxy-lang');
  if (saved && STRINGS[saved]) return saved;
  const nav = (navigator.languages || [navigator.language || 'en'])
    .map(l => l.toLowerCase().slice(0, 2));
  for (const l of nav) {
    if (STRINGS[l]) return l;
  }
  return 'en';
}
function applyLang(lang) {
  const s = STRINGS[lang];
  document.documentElement.lang = lang;
  document.getElementById('t-title').textContent = s.title;
  document.title = s.title;
  applyAuthPanelCopy();

  // Sidebar widget texts
  document.getElementById('lbl-status-title').textContent = s.statusTitle;
  document.getElementById('lbl-status-active').textContent = s.statusActive;
  document.getElementById('lbl-status-upstream-label').textContent = s.statusUpstream;
  document.getElementById('lbl-status-cache-label').textContent = s.statusCache;
  document.getElementById('lbl-status-thinking-label').textContent = s.statusThinking;
  document.getElementById('lbl-status-thinking-label').nextElementSibling.textContent = s.statusThinkingActive;

  // Legacy diagram texts are optional because the cache pane is now diagnostics.
  const dashboardTitle = document.getElementById('lbl-dashboard-title');
  if (dashboardTitle) dashboardTitle.textContent = s.dashboardTitle;
  const flowClient = document.getElementById('flow-lbl-client');
  if (flowClient) flowClient.textContent = s.diagramClient;
  const flowProxy = document.getElementById('flow-lbl-proxy');
  if (flowProxy) flowProxy.textContent = s.diagramProxy;
  const flowCache = document.getElementById('flow-lbl-cache');
  if (flowCache) flowCache.textContent = s.diagramCache;
  const visualizerTitle = document.getElementById('lbl-visualizer-title');
  if (visualizerTitle) visualizerTitle.textContent = s.visualizerTitle;
  const visualizerStable = document.getElementById('lbl-visualizer-stable');
  if (visualizerStable) visualizerStable.textContent = s.visualizerStable;
  const visualizerTail = document.getElementById('lbl-visualizer-tail');
  if (visualizerTail) visualizerTail.textContent = s.visualizerTail;

  const cacheDiagnosticsTitle = document.getElementById('t-cache-diagnostics-title');
  if (cacheDiagnosticsTitle) cacheDiagnosticsTitle.textContent = s.cacheDiagnosticsTitle || 'Cache Diagnostics';
  const cacheDiagnosticsRefresh = document.getElementById('t-cache-diagnostics-refresh');
  if (cacheDiagnosticsRefresh) cacheDiagnosticsRefresh.textContent = s.cacheDiagnosticsRefresh || 'Refresh';
  const cacheDiagnosticsNote = document.getElementById('t-cache-diagnostics-note');
  if (cacheDiagnosticsNote) cacheDiagnosticsNote.textContent = s.cacheDiagnosticsNote || 'Shows request-side cache patching plus upstream usage fields when providers expose them. Unknown means the response did not include parseable usage.';
  const cacheLabelMap = {
    't-cache-stat-requests': s.cacheStatRequests || 'Requests',
    't-cache-stat-input': s.cacheStatInput || 'Input Tokens',
    't-cache-stat-read': s.cacheStatRead || 'Cache Read',
    't-cache-stat-create': s.cacheStatCreate || 'Cache Create',
    't-cache-th-time': s.cacheThTime || 'Time',
    't-cache-th-model': s.cacheThModel || 'Model',
    't-cache-th-patch': s.cacheThPatch || 'Patch',
    't-cache-th-breakpoints': s.cacheThBreakpoints || 'Breakpoints',
    't-cache-th-usage': s.cacheThUsage || 'Usage',
    't-cache-th-cache': s.cacheThCache || 'Cache Tokens',
    't-no-cache-diagnostics': s.noCacheDiagnostics || 'No request diagnostics yet.'
  };
  Object.entries(cacheLabelMap).forEach(([id, text]) => {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
  });

  setText('t-preset-label', s.presetLabel, 'preset');
  document.getElementById('t-preset-anthropic').textContent = s.presetAnthropic;
  document.getElementById('t-preset-custom').textContent = s.presetCustom;

  setText('t-upstream-label', s.upstreamLabel, 'upstream');
  document.getElementById('t-upstream-hint').textContent = s.upstreamHint;
  document.getElementById('msg-not-applicable').textContent = s.cacheNotApplicable;

  setText('t-ttl-label', s.ttlLabel, 'cache_ttl');
  document.getElementById('t-ttl-5m').textContent = s.ttl5m;
  document.getElementById('t-ttl-1h').textContent = s.ttl1h;
  document.getElementById('t-ttl-none').textContent = s.ttlNone;

  setText('t-mode-label', s.modeLabel, 'cache_mode');
  document.getElementById('t-mode-explicit').textContent = s.modeExplicit;
  document.getElementById('t-mode-automatic').textContent = s.modeAutomatic;

  setText('t-strategy-label', s.strategyLabel, 'cache_strategy');
  document.getElementById('t-strategy-prefix').textContent = s.strategyPrefix;
  document.getElementById('t-strategy-last').textContent = s.strategyLast;

  setText('t-bp-label', s.bpLabel, 'cache_breakpoints');

  // Companion Memory labels
  document.getElementById('t-companion-title').textContent = s.companionTitle || 'AI Companion Core Settings';
  document.getElementById('t-comp-enable-label').textContent = s.compEnableLabel || 'Enable Memory & Intimacy';
  setText('t-comp-summary-url-label', s.compSummaryUrlLabel || 'Summarization Endpoint', 'memory_summary_url');
  document.getElementById('t-comp-summary-url-hint').textContent = s.compSummaryUrlHint || '';
  setText('t-comp-summary-key-label', s.compSummaryKeyLabel || 'Summarization API Key', 'memory_summary_key');
  setText('t-comp-summary-clear-label', s.compClearKeyLabel || 'Clear stored key', 'clear_memory_summary_key');
  setText('t-comp-summary-model-label', s.compSummaryModelLabel || 'Summarization Model', 'memory_summary_model');
  const modelRecallToggleLabel = document.querySelector('#t-comp-model-recall-toggle-label span');
  if (modelRecallToggleLabel) modelRecallToggleLabel.textContent = s.compModelRecallToggleLabel || 'Enable Model Recall';
  setText('t-comp-recall-model-model-label', s.compRecallModelModelLabel || 'Recall Model', 'memory_recall_model_model');
  setText('t-comp-recall-model-url-label', s.compRecallModelUrlLabel || 'Recall Model Endpoint', 'memory_recall_model_url');
  const recallUrlHint = document.getElementById('t-comp-recall-model-url-hint');
  if (recallUrlHint) recallUrlHint.textContent = s.compRecallModelUrlHint || 'Leave blank to inherit the summarization endpoint. The recall model receives the materialized memory index and returns a JSON retrieval plan.';
  setText('t-comp-recall-model-key-label', s.compRecallModelKeyLabel || 'Recall Model API Key', 'memory_recall_model_key');
  setText('t-comp-recall-model-clear-label', s.compRecallClearKeyLabel || 'Clear stored recall key', 'clear_memory_recall_model_key');
  setText('t-comp-model-recall-failure-label', s.compModelRecallFailureLabel || 'Recall Failure Threshold (1-20)', 'memory_model_recall_failure_threshold');
  setText('t-comp-model-recall-cooldown-label', s.compModelRecallCooldownLabel || 'Recall Cooldown Seconds (0-86400)', 'memory_model_recall_cooldown_seconds');
  setText('t-comp-model-recall-retention-label', s.compModelRecallRetentionLabel || 'Recall Trace Retention (1-5000)', 'memory_model_recall_trace_retention');
  setText('t-comp-local-recall-title', s.compLocalRecallTitle || 'Local Recall Enhancements');
  const localRecallToggleLabel = document.querySelector('#t-comp-local-recall-toggle-label span');
  if (localRecallToggleLabel) localRecallToggleLabel.textContent = s.compLocalRecallToggleLabel || 'Enable Local Recall Enhancements';
  const tagGraphToggleLabel = document.querySelector('#t-comp-tag-graph-toggle-label span');
  if (tagGraphToggleLabel) tagGraphToggleLabel.textContent = s.compTagGraphToggleLabel || 'Enable Tag Graph';
  setText('t-comp-tag-graph-max-label', s.compTagGraphMaxLabel || 'Tag Graph Expanded Terms (0-128)', 'memory_tag_graph_max_expanded_terms');
  const timelineRecallToggleLabel = document.querySelector('#t-comp-timeline-recall-toggle-label span');
  if (timelineRecallToggleLabel) timelineRecallToggleLabel.textContent = s.compTimelineRecallToggleLabel || 'Enable Timeline Recall';
  const summarySanitizeToggleLabel = document.querySelector('#t-comp-summary-sanitize-toggle-label span');
  if (summarySanitizeToggleLabel) summarySanitizeToggleLabel.textContent = s.compSummarySanitizeToggleLabel || 'Sanitize Internal Prompts Before Memory Summary';
  setText('t-comp-decay-hours-label', s.compDecayHoursLabel || 'Decay Interval (Hours)', 'memory_decay_interval_hours');
  setText('t-comp-decay-rate-label', s.compDecayRateLabel || 'Memory Decay Rate (0-1)', 'memory_decay_rate');
  setText('t-comp-threshold-label', s.compThresholdLabel || 'Forget Threshold (0-1)', 'memory_threshold');
  setText('t-comp-recovery-label', s.compRecoveryLabel || 'Recall Recovery delta', 'memory_recovery_amount');
  setText('t-comp-initial-strength-label', s.compInitialStrengthLabel || 'Initial Strength (0-1)', 'memory_initial_strength');
  setText('t-comp-max-strength-label', s.compMaxStrengthLabel || 'Max Strength (0-1)', 'memory_max_strength');
  setText('t-comp-intimacy-decay-label', s.compIntimacyDecayLabel || 'Intimacy Decay Rate / 24h', 'intimacy_decay_rate');
  setText('t-comp-trust-down-scale-label', s.compTrustDownScaleLabel || 'Trust Drop Scale (0.1-5)', 'trust_down_scale');
  setText('t-comp-trust-up-scale-label', s.compTrustUpScaleLabel || 'Trust Rise Scale (0.1-5)', 'trust_up_scale');
  setText('t-comp-recall-max-label', s.compRecallMaxLabel || 'Recall Max Nodes (0-20)', 'memory_recall_max_nodes');
  setText('t-comp-tau-label', s.compTauLabel || 'Ebbinghaus Decay τ (hours)', 'memory_decay_tau_hours');
  setText('t-comp-salience-k-label', s.compSalienceKLabel || 'Affect Salience k (0-10)', 'memory_salience_k');
  const deepToggleLabel = document.querySelector('#t-comp-deep-toggle-label span');
  if (deepToggleLabel) deepToggleLabel.textContent = s.compDeepToggleLabel || 'Enable Deep Recall';
  setText('t-comp-deep-candidates-label', s.compDeepCandidatesLabel || 'Deep Recall Candidates (1-100)', 'memory_deep_recall_max_candidates');
  setText('t-comp-deep-clues-label', s.compDeepCluesLabel || 'Deep Recall Clues (1-20)', 'memory_deep_recall_max_clues');
  setText('t-comp-person-clues-label', s.compPersonCluesLabel || 'Person Context Clues (0-10)', 'memory_person_context_max_clues');
  const bufferedToggleLabel = document.querySelector('#t-comp-buffered-toggle-label span');
  if (bufferedToggleLabel) bufferedToggleLabel.textContent = s.compBufferedToggleLabel || 'Buffer memory before promotion';
  setText('t-comp-promote-repeat-label', s.compPromoteRepeatLabel || 'Promote After Repeats (1-20)', 'memory_promote_repeat_threshold');
  const selfToggleLabel = document.querySelector('#t-comp-self-toggle-label span');
  if (selfToggleLabel) selfToggleLabel.textContent = s.compSelfToggleLabel || 'Enable Self Memory';
  const selfDirectToggleLabel = document.querySelector('#t-comp-self-direct-toggle-label span');
  if (selfDirectToggleLabel) selfDirectToggleLabel.textContent = s.compSelfDirectToggleLabel || 'Allow Direct Self Updates';
  setText('t-comp-self-recall-label', s.compSelfRecallLabel || 'Self Recall Max Nodes (0-20)', 'memory_self_recall_max_nodes');
  setText('t-comp-self-repeat-label', s.compSelfRepeatLabel || 'Self Promote Repeats (1-20)', 'memory_self_promote_repeat_threshold');
  setText('t-comp-project-repeat-label', s.compProjectRepeatLabel || 'Project Fact Repeats (1-20)', 'memory_project_fact_promote_repeat_threshold');
  setText('t-comp-working-slots-label', s.compWorkingSlotsLabel || 'Working Slots / Project (1-20)', 'memory_working_memory_slots_per_project');
  setText('t-comp-observation-retention-label', s.compObservationRetentionLabel || 'Observation Retention Days (1-365)', 'memory_observation_retention_days');
  setText('t-comp-low-confidence-retention-label', s.compLowConfidenceRetentionLabel || 'Low Confidence Retention Days (1-365)', 'memory_low_confidence_observation_retention_days');
  setText('t-comp-observation-cap-label', s.compObservationCapLabel || 'Observations Per Day (1-5000)', 'memory_observation_daily_cap');
  setText('t-comp-promoted-cap-label', s.compPromotedCapLabel || 'Promoted Nodes Per Day (0-500)', 'memory_promoted_nodes_daily_cap');
  const dreamToggleLabel = document.querySelector('#t-comp-dream-toggle-label span');
  if (dreamToggleLabel) dreamToggleLabel.textContent = s.compDreamToggleLabel || 'Enable Dreaming';
  const maintenanceToggleLabel = document.querySelector('#t-comp-maintenance-toggle-label span');
  if (maintenanceToggleLabel) maintenanceToggleLabel.textContent = s.compMaintenanceToggleLabel || 'Enable Auto Maintenance';
  setText('t-comp-maintenance-aggressiveness-label', s.compMaintenanceAggressivenessLabel || 'Maintenance Aggressiveness', 'memory_maintenance_aggressiveness');
  setText('t-comp-maintenance-aggressive-option', s.compMaintenanceAggressiveOption || 'Aggressive');
  setText('t-comp-maintenance-standard-option', s.compMaintenanceStandardOption || 'Standard');
  setText('t-comp-dream-idle-label', s.compDreamIdleLabel || 'Dream Idle Hours (1-720)', 'memory_dream_idle_hours');
  setText('t-comp-dream-daily-label', s.compDreamDailyLabel || 'Dreams Per Day (0-24)', 'memory_dream_daily_limit');
  setText('t-comp-long-idle-label', s.compLongIdleLabel || 'Pause After Idle Days (1-365)', 'memory_long_idle_pause_days');
  setText('t-comp-dream-traces-label', s.compDreamTracesLabel || 'Dream Traces in Recall (0-10)', 'memory_dream_recall_max_traces');
  setText('t-comp-dream-batch-label', s.compDreamBatchLabel || 'Dream Batch Nodes (1-200)', 'memory_dream_batch_max_nodes');
  setText('t-comp-dream-dry-limit-label', s.compDreamDryLimitLabel || 'Dream Dry Runs Per Day (0-24)', 'memory_dream_dry_run_daily_limit');
  setText('t-comp-recycle-retention-label', s.compRecycleRetentionLabel || 'Recycle Retention Days (1-3650)', 'memory_recycle_retention_days');

  setText('t-comp-summary-prompt-label', s.compSummaryPromptLabel || 'Summarization System Prompt', 'memory_summary_prompt');
  document.getElementById('t-password-change-title').textContent = s.passwordChangeTitle || 'Config Password';
  setText('t-password-current-label', s.passwordCurrentLabel || 'Current password', 'config_password_current');
  setText('t-password-new-label', s.passwordNewLabel || 'New password', 'config_password_new');
  setText('t-password-confirm-label', s.passwordConfirmLabel || 'Confirm new password', 'config_password_confirm');
  refreshAutoSaveUi();

  // Set save button text without stripping icon
  const saveBtn = document.getElementById('t-save');
  const saveIcon = saveBtn.querySelector('svg').outerHTML;
  saveBtn.innerHTML = saveIcon + ' ' + s.save;

  // Export / Import buttons
  const exportEl = document.getElementById('t-export-btn');
  if (exportEl) exportEl.textContent = s.exportBtn || 'Export Config';
  const importEl = document.getElementById('t-import-btn');
  if (importEl) importEl.textContent = s.importBtn || 'Import Config';

  // Companion + Logs panel labels
  const companionTitle = document.getElementById('t-companion-panel-title');
  if (companionTitle) companionTitle.textContent = s.companionPanelTitle || 'Companion State';
  const companionRefresh = document.getElementById('t-companion-refresh');
  if (companionRefresh) companionRefresh.textContent = s.companionRefresh || 'Refresh';
  const dreamRun = document.getElementById('t-dream-run');
  if (dreamRun) dreamRun.textContent = s.dreamRun || 'Dream now';
  const dreamDryRun = document.getElementById('t-dream-dry-run');
  if (dreamDryRun) dreamDryRun.textContent = s.dreamDryRun || 'Dream dry run';
  const selfMemoryLabel = document.getElementById('t-self-memory-label');
  if (selfMemoryLabel) selfMemoryLabel.textContent = s.selfMemoryLabel || 'Self Memory';
  const selfMemoryRefresh = document.getElementById('t-self-memory-refresh');
  if (selfMemoryRefresh) selfMemoryRefresh.textContent = s.selfMemoryRefresh || 'Refresh self';
  const selfMemoryCreate = document.getElementById('t-self-memory-create');
  if (selfMemoryCreate) selfMemoryCreate.textContent = s.selfMemoryCreate || 'Create self memory';
  const stableSelfLabel = document.getElementById('t-stable-self-label');
  if (stableSelfLabel) stableSelfLabel.textContent = s.stableSelfLabel || 'Stable Self';
  const bufferedSelfLabel = document.getElementById('t-buffered-self-label');
  if (bufferedSelfLabel) bufferedSelfLabel.textContent = s.bufferedSelfLabel || 'Buffered Self';
  const dreamSelfLabel = document.getElementById('t-dream-self-label');
  if (dreamSelfLabel) dreamSelfLabel.textContent = s.dreamSelfLabel || 'Dream-source Self';
  const conflictSelfLabel = document.getElementById('t-conflict-self-label');
  if (conflictSelfLabel) conflictSelfLabel.textContent = s.conflictSelfLabel || 'Self Conflicts';
  const selfEventsLabel = document.getElementById('t-self-events-label');
  if (selfEventsLabel) selfEventsLabel.textContent = s.selfEventsLabel || 'Self Events';
  const noStableSelf = document.getElementById('t-no-stable-self');
  if (noStableSelf) noStableSelf.textContent = s.noStableSelf || 'No stable self memory.';
  const noBufferedSelf = document.getElementById('t-no-buffered-self');
  if (noBufferedSelf) noBufferedSelf.textContent = s.noBufferedSelf || 'No buffered self observations.';
  const noDreamSelf = document.getElementById('t-no-dream-self');
  if (noDreamSelf) noDreamSelf.textContent = s.noDreamSelf || 'No dream-source self observations.';
  const noConflictSelf = document.getElementById('t-no-conflict-self');
  if (noConflictSelf) noConflictSelf.textContent = s.noConflictSelf || 'No self conflicts.';
  const noSelfEvents = document.getElementById('t-no-self-events');
  if (noSelfEvents) noSelfEvents.textContent = s.noSelfEvents || 'No self events.';
  const memoryQueuesLabel = document.getElementById('t-memory-queues-label');
  if (memoryQueuesLabel) memoryQueuesLabel.textContent = s.memoryQueuesLabel || 'Memory Queues';
  const memoryQueuesRefresh = document.getElementById('t-memory-queues-refresh');
  if (memoryQueuesRefresh) memoryQueuesRefresh.textContent = s.memoryQueuesRefresh || 'Refresh queues';
  const observationsLabel = document.getElementById('t-observations-label');
  if (observationsLabel) observationsLabel.textContent = s.observationsLabel || 'Buffered Observations';
  const recycleLabel = document.getElementById('t-recycle-label');
  if (recycleLabel) recycleLabel.textContent = s.recycleLabel || 'Recycle Bin';
  const noObservations = document.getElementById('t-no-observations');
  if (noObservations) noObservations.textContent = s.noObservations || 'No buffered observations.';
  const noRecycle = document.getElementById('t-no-recycle');
  if (noRecycle) noRecycle.textContent = s.noRecycle || 'Recycle bin is empty.';
  const intimacyLbl = document.getElementById('t-intimacy-label');
  if (intimacyLbl) intimacyLbl.textContent = s.intimacyLabel || 'Intimacy';
  const trustLbl = document.getElementById('t-trust-label');
  if (trustLbl) trustLbl.textContent = s.trustLabel || 'Trust';
  const memCountLbl = document.getElementById('t-memory-count-label');
  if (memCountLbl) memCountLbl.textContent = s.memoryCountLabel || 'Graph nodes: ';
  const relEdgesLbl = document.getElementById('t-related-edges-label');
  if (relEdgesLbl) relEdgesLbl.textContent = s.relatedEdgesLabel || 'Graph edges: ';
  const searchTermsLbl = document.getElementById('t-search-terms-label');
  if (searchTermsLbl) searchTermsLbl.textContent = s.searchTermsLabel || 'Search terms: ';
  const workingLbl = document.getElementById('t-working-memory-label');
  if (workingLbl) workingLbl.textContent = s.workingMemoryLabel || 'Working memory: ';
  const affectLbl = document.getElementById('t-affect-label');
  if (affectLbl) affectLbl.textContent = s.affectLabel || 'Affect: ';
  const consLbl = document.getElementById('t-consolidation-label');
  if (consLbl) consLbl.textContent = s.consolidationLabel || 'Last deep recall: ';
  const dreamLbl = document.getElementById('t-dream-label');
  if (dreamLbl) dreamLbl.textContent = s.dreamLabel || 'Last dream: ';
  const modelRecallLabel = document.getElementById('t-model-recall-label');
  if (modelRecallLabel) modelRecallLabel.textContent = s.modelRecallLabel || 'Model Recall Debug';
  const modelRecallRefresh = document.getElementById('t-model-recall-refresh');
  if (modelRecallRefresh) modelRecallRefresh.textContent = s.modelRecallRefresh || 'Refresh recall';
  const memoryIndexRebuild = document.getElementById('t-memory-index-rebuild');
  if (memoryIndexRebuild) memoryIndexRebuild.textContent = s.memoryIndexRebuild || 'Rebuild index';
  const modelRecallStatusLabel = document.getElementById('t-model-recall-status-label');
  if (modelRecallStatusLabel) modelRecallStatusLabel.textContent = s.modelRecallStatusLabel || 'Recall Status';
  const modelRecallTracesLabel = document.getElementById('t-model-recall-traces-label');
  if (modelRecallTracesLabel) modelRecallTracesLabel.textContent = s.modelRecallTracesLabel || 'Recent Recall Traces';
  const memoryIndexLabel = document.getElementById('t-memory-index-label');
  if (memoryIndexLabel) memoryIndexLabel.textContent = s.memoryIndexLabel || 'Memory Index Segments';
  const noModelRecallStatus = document.getElementById('t-no-model-recall-status');
  if (noModelRecallStatus) noModelRecallStatus.textContent = s.noModelRecallStatus || 'No recall diagnostics loaded.';
  const noModelRecallTraces = document.getElementById('t-no-model-recall-traces');
  if (noModelRecallTraces) noModelRecallTraces.textContent = s.noModelRecallTraces || 'No recall traces yet.';
  const noMemoryIndex = document.getElementById('t-no-memory-index');
  if (noMemoryIndex) noMemoryIndex.textContent = s.noMemoryIndex || 'No memory index loaded.';
  const reflectionsLbl = document.getElementById('t-reflections-label');
  if (reflectionsLbl) reflectionsLbl.textContent = s.reflectionsLabel || 'Recent Reflections';
  const noReflEl = document.getElementById('t-no-reflections');
  if (noReflEl) noReflEl.textContent = s.noReflections || 'No reflections yet.';
  const logsPanelTitle = document.getElementById('t-logs-panel-title');
  if (logsPanelTitle) logsPanelTitle.textContent = s.logsPanelTitle || 'Recent Requests';
  const logsRefresh = document.getElementById('t-logs-refresh');
  if (logsRefresh) logsRefresh.textContent = s.logsRefresh || 'Refresh';
  const noLogsEl = document.getElementById('t-no-logs');
  if (noLogsEl) noLogsEl.textContent = s.noLogs || 'No requests logged yet.';
  ['t-log-th-time','t-log-th-method','t-log-th-path','t-log-th-patched','t-log-th-model','t-log-th-msgs','t-log-th-cache'].forEach((id, i) => {
    const keys = ['logThTime','logThMethod','logThPath','logThPatched','logThModel','logThMsgs','logThCache'];
    const el = document.getElementById(id);
    if (el) el.textContent = s[keys[i]] || el.textContent;
  });

  // Nav + section titles
  const navIds = ['t-nav-status','t-nav-cache','t-nav-companion','t-nav-logs','t-nav-memories','t-nav-config'];
  const navKeys = ['navStatus','navCache','navCompanion','navLogs','navMemories','navConfig'];
  navIds.forEach((id, i) => {
    const el = document.getElementById(id);
    if (el) el.textContent = s[navKeys[i]] || el.textContent;
  });
  const menuEl = document.getElementById('t-nav-menu');
  if (menuEl) menuEl.textContent = s['navMenu'] || menuEl.textContent;
  const secIds = ['t-sec-basic-title','t-sec-claude-title','t-sec-companion-title','t-sec-security-title'];
  const secKeys = ['secBasicTitle','secClaudeTitle','secCompanionTitle','secSecurityTitle'];
  secIds.forEach((id, i) => {
    const el = document.getElementById(id);
    if (el) el.textContent = s[secKeys[i]] || el.textContent;
  });

  updateCacheModeOptions();
  updateVisualizer();
  updateFlowDiagram();
  updateStatusSummary();
  refreshSecretFieldUi();
  refreshPasswordChangeUi();
  updatePasswordToggleLabels();
}
function setText(labelId, text, inputName) {
  const label = document.getElementById(labelId);
  if (!label) return;
  const input = inputName ? label.querySelector('[name=' + inputName + ']') : null;
  while (label.firstChild && label.firstChild.nodeType === 3) {
    label.removeChild(label.firstChild);
  }
  label.insertBefore(document.createTextNode(text + ' '), label.firstChild);
}
function setLang(lang) {
  currentLang = lang;
  localStorage.setItem('proxy-lang', lang);
  applyLang(lang);

  document.querySelectorAll('#lang-bar button').forEach(btn => btn.classList.remove('active'));
  const activeBtn = document.getElementById('btn-lang-' + lang);
  if (activeBtn) activeBtn.classList.add('active');
}
