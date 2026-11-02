/*
 * turbo-feed-ui/config.js
 * 全站唯一的后端地址来源（P1-5）。
 *
 * 为什么需要它：改造前 6 个页面各自硬编码 `var API_BASE = 'http://localhost:8080'`，
 * feed.html 还另有一个 BEHAVIOR_API 独立拼地址 —— 部署到任何非本机环境
 * （docker / 内网演示 / 反代）都要手改 6~7 处，极易漏改且漏改后表现为
 * 「页面能打开但所有请求静默失败」，排查成本高。
 *
 * 取值优先级：
 *   1) localStorage['turbofeed:apiBase'] —— 运行时覆盖，便于演示时临时指向别的环境，
 *      无需改代码（控制台执行 localStorage.setItem('turbofeed:apiBase','http://x:8080') 后刷新）；
 *   2) 默认 http://localhost:8080 —— 与改造前行为完全一致（零回归）。
 *
 * 刻意不做的事：不自动用 window.location.origin 推断后端地址。前端常以 live-server /
 * file:// 打开，此时 origin 分别是 :5500 和 "null"，都不是后端；自动推断会把
 * 「配置缺失」变成「指向错误地址」，比显式默认值更难排查。
 * 同源反代部署时，把下面的 DEFAULT_API_BASE 改成空串 '' 即可让所有请求走相对路径
 * （相对路径还能顺带免掉 CORS）。
 */
(function (global) {
  'use strict';

  var DEFAULT_API_BASE = 'http://localhost:8080';
  var OVERRIDE_KEY = 'turbofeed:apiBase';

  function resolve() {
    try {
      var v = global.localStorage.getItem(OVERRIDE_KEY);
      if (v && v.trim()) {
        // 去掉末尾斜杠，避免拼出 //api/v1/...
        return v.trim().replace(/\/+$/, '');
      }
    } catch (e) {
      // localStorage 不可用（隐私模式 / file:// 限制）→ 用默认值，不阻断页面
    }
    return DEFAULT_API_BASE;
  }

  /** 后端网关基址，例如 http://localhost:8080。全站只此一处定义。 */
  global.TF_API_BASE = resolve();

  /** 拼接完整接口地址：TF.url('/api/v1/feed/recommended')。 */
  global.TF_url = function (path) {
    if (!path) return global.TF_API_BASE;
    return global.TF_API_BASE + (path.charAt(0) === '/' ? path : '/' + path);
  };
})(window);
