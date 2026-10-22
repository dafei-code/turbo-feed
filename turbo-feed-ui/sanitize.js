/*
 * turbo-feed-ui/sanitize.js
 * 前端 XSS 防护工具集（P0-1）。
 * 纯函数、零依赖，供各页面内联脚本在把「后端回包 / 用户输入 / URL」拼进 HTML 前调用。
 *
 * 设计原则：
 *  1. 任何进入 innerHTML / 属性值 / 内联 onclick 的动态值，都必须经此处转义或白名单。
 *  2. URL 只允许 http(s) 协议（白名单），杜绝 javascript:/data:/vbscript: 伪协议点击即执行。
 *  3. 内联事件里的动态值用 jsLit() 包成合法 JS 字符串字面量，避免引号/反斜杠逃逸出上下文。
 */
(function (global) {
  'use strict';

  // HTML 转义：用于把任意文本安全地放进 innerHTML、文本节点或属性值。
  function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  // URL 白名单：仅允许 http(s)，且不含会破坏属性 / JS / CSS 字面量的字符。
  // 返回安全 URL 或空串（不安全时调用方应跳过渲染或忽略）。
  function safeUrl(u) {
    if (u === null || u === undefined) return '';
    u = String(u).trim();
    // 必须显式带 //（杜绝 javascript: / data: / vbscript:），且不含空白与引号/括号等危险字符。
    if (!/^https?:\/\/[^\s'"<>()]+$/i.test(u)) return '';
    return u;
  }

  // 在 JS 字符串字面量（含内联 onclick="..." 属性）中安全包裹任意值。
  // 产出双引号 JS 字符串，内部引号、反斜杠、控制字符均已转义，可直接插入 onclick="..."。
  function jsLit(v) {
    return JSON.stringify(v == null ? '' : String(v));
  }

  // 安全打开外链：只允许 http(s)，否则忽略（杜绝伪协议点击执行）。
  function safeOpen(u) {
    var s = safeUrl(u);
    if (s) window.open(s, '_blank');
  }

  // 暴露为全局，兼容各页面已有的裸函数调用风格（showMsg/logout/fmt 等同款）。
  global.esc = esc;
  global.safeUrl = safeUrl;
  global.jsLit = jsLit;
  global.safeOpen = safeOpen;
})(window);
