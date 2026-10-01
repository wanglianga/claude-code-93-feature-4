// 枚举中文标签与颜色集中维护
export const L = {
  CustomerType: { NORMAL: ['普通客户', 'blue'], VIP: ['VIP 客户', 'purple'], ELDERLY: ['老年客户', 'orange'], CORPORATE: ['对公客户', 'blue'] },
  Source: { WALK_IN: '现场取号', MOBILE_APP: '手机银行预约', PHONE: '电话预约', HOTLINE_ELDERLY: '老年热线预约' },
  BusinessType: {
    ACCOUNT_OPEN: '开户', TRANSFER: '转账', LOST_REPORT: '挂失',
    SOCIAL_CARD: '社保卡', WEALTH_CONSULT: '理财咨询', LARGE_CASH: '大额现金'
  },
  RiskLevel: { LOW: ['低', 'green'], MEDIUM: ['中', 'orange'], HIGH: ['高', 'red'] },
  Material: { COMPLETE: ['齐全', 'green'], INCOMPLETE: ['不完整', 'orange'], MISSING: ['缺失', 'red'] },
  TicketStatus: {
    WAITING: ['待分流', 'orange'], ASSIGNED: ['已分流待叫号', 'blue'], SERVING: ['办理中', 'green'],
    PENDING: ['异常挂起', 'red'], DONE: ['已办结', 'gray'], CANCELLED: ['已取消', 'gray'],
    DISPOSING: ['故障处置中', 'red'], PENDING_ASSIGN: ['待分配', 'orange'], DEFERRED: ['客户暂缓', 'purple']
  },
  Channel: { COUNTER: '柜台', SELF_MACHINE: '自助机', WEALTH_ROOM: '理财室', REMOTE_SERVICE: '远程客服' },
  CounterType: { GENERAL: '综合柜台', CASH: '现金柜台', PRIORITY: '爱心优先', SELF_SERVICE: '智能设备' },
  CounterStatus: {
    OPEN: ['开放', 'green'], TEMP_LEAVE: ['临时离岗', 'orange'], CLOSED: ['关闭', 'gray'],
    FAULT: ['故障', 'red'], OFFLINE: ['设备离线', 'red']
  },
  IssueType: {
    NONE: '普通记录', LONG_WAIT: '长时间等待', FRAUD_CHECK: '反诈核验', MISSING_ID: '忘带证件',
    TELLER_LEAVE: '柜员离岗', MACHINE_FAULT: '自助机故障', JUMP_COMPLAINT: '投诉插队', OUTAGE: '突发停电',
    DEVICE_OFFLINE: '设备离线', TRANSFER_FAILED: '转台失败', QUEUE_MIGRATED: '队列迁移',
    CUSTOMER_DEFERRED: '客户暂缓', PENDING_ASSIGN: '待分配', RECOVERY_SUGGESTED: '恢复建议',
    RECOVERY_APPLIED: '恢复入队', RISK_ALERT: '风控告警'
  },
  FraudStatus: { NONE: ['未触发', 'gray'], PENDING: ['待核验', 'red'], PASSED: ['核验通过', 'green'], BLOCKED: ['已拦截', 'red'] },
  ResultType: { SUCCESS: '办理成功', REJECTED: '拒绝/撤回', ESCALATED: '升级处理', REDIRECTED: '引导其他渠道' },
  Role: {
    CUSTOMER: '客户', MANAGER: '大堂经理', TELLER: '柜员',
    ADVISOR: '理财经理', SECURITY: '安保', SERVICE: '客服',
    MAINTENANCE: '设备维修', RISK: '风控', SUPERVISOR: '主管'
  },
  /* 特性4 */
  FailureType: { DEVICE_OFFLINE: '设备离线', TRANSFER_FAILED: '转台失败' },
  DispositionStatus: { OPEN: ['待处置', 'red'], RESOLVED: ['已处置', 'blue'], RECOVERED: ['已恢复', 'green'] },
  DispositionActionType: {
    MIGRATE_KEEP_WEIGHT: '保留权重迁移', TO_MANUAL: '转人工窗口', DEFER: '客户暂缓', RECOVER: '维修后恢复'
  },
  DispositionActionResult: { SUCCESS: ['成功', 'green'], ROLLED_BACK: ['已回滚', 'orange'] },
  MaintenanceStatus: { OPEN: ['待接单', 'red'], REPAIRING: ['维修中', 'orange'], DONE: ['已完成', 'green'] },
  SuggestionStatus: {
    PENDING: ['待主管复核', 'orange'], APPROVED: ['已批准', 'blue'],
    REJECTED: ['已驳回', 'gray'], APPLIED: ['已应用', 'green']
  },
  RiskAlertType: { REPEAT_TICKET: '重复取号', FREQUENT_TRANSFER: '频繁转台', MANUAL_JUMP: '人工插队' },
  RiskAlertStatus: { OPEN: ['待确认', 'red'], ACKNOWLEDGED: ['已确认', 'green'] }
}

export function tag(map, key) {
  const v = map[key]
  return Array.isArray(v) ? { text: v[0], cls: v[1] } : { text: v || key, cls: 'blue' }
}

export function fmtTime(s) {
  if (!s) return '—'
  return s.replace('T', ' ').substring(0, 16)
}

export function waitText(sec) {
  if (sec == null) return '—'
  if (sec < 60) return sec + ' 秒'
  return Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒'
}
