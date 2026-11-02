/*
 * turbo-feed-ui/api.js
 * 统一请求层（P0-1 / P0-2 / P1-4）。零依赖，与 sanitize.js / config.js 同风格。
 *
 * ─────────────────────────────────────────────────────────────
 * 改造前的问题（三处，一次解决）
 * ─────────────────────────────────────────────────────────────
 * ① 令牌刷新链路断裂：后端有 POST /api/v1/auth/refresh，前端也存了 turbofeed:refresh，
 *    但全站从未调用它；访问令牌只有 30 分钟（JwtProperties.expireSeconds=1800），
 *    且 6 个页面里 5 个完全不处理凭证失效 —— 用户挂机半小时后点赞/评论/翻页全部静默失败，
 *    既不跳登录也不提示，只会觉得"网站坏了"。
 * ② refreshToken 走 URL 查询串：logout / refresh 都把 7 天有效期的刷新令牌拼在
 *    `?refreshToken=...` 上，会进网关访问日志、浏览器历史、Referer。
 *    本模块改走 application/x-www-form-urlencoded 请求体 —— 后端 @RequestParam
 *    同样能从 form body 取值（login 端点已在用这个模式），因此【后端零改动、无兼容风险】。
 * ③ 无统一请求层：36 处裸 fetch，各自拼 headers、各自（或不）判错。
 *
 * ─────────────────────────────────────────────────────────────
 * 必须知道的两个后端事实（否则会写错判错逻辑）
 * ─────────────────────────────────────────────────────────────
 * · GlobalExceptionHandler 返回 Result 但【没有 @ResponseStatus】，因此业务异常
 *   （含未登录 40101、限流 42901、参数错 40001）的 HTTP 状态码恒为 200，
 *   真实错误只在响应体的 code 字段。→ 判错必须以 body.code 为准，res.ok 只对
 *   框架层 5xx / 网关未启动等场景有意义，两者都要判。
 * · fetch 对 4xx/5xx 【不会 reject】，改造前 `.then(r => r.json())` 会把 500 当成功
 *   走下去，随后 res.data 为 undefined，页面渲染出空白或 "undefined"。
 *
 * ─────────────────────────────────────────────────────────────
 * 用法
 * ─────────────────────────────────────────────────────────────
 *   TF.api('/api/v1/feed/recommended?page=0&size=20')
 *     .then(function (data) { render(data.items); })
 *     .catch(function (err) { /* err.message 已可展示；已自动 toast，通常无需再处理 *\/ });
 *
 *   TF.api('/api/v1/feed/media/like', { method: 'POST', json: { postId: id, like: true } })
 *   TF.api('/api/v1/media', { method: 'POST', form: { mediaId: id } })
 *   TF.api('/api/v1/media/presign', { method: 'POST', json: {...}, silent: true })  // 不自动 toast
 *   TF.api('/api/v1/search/hot', { auth: false })                                    // 不注入令牌
 *   TF.api('/api/v1/notify/inbox', { raw: true })  → resolve 整个 {code,message,data}
 *
 * 成功 resolve res.data（raw:true 时 resolve 整个响应体）；失败 reject Error。
 * 失败一律已 toast（除非 silent:true），因此 catch 里通常只需处理界面状态。
 */
(function (global) {
  'use strict';

  var TOKEN_KEY = 'turbofeed:token';
  var REFRESH_KEY = 'turbofeed:refresh';
  var ROLE_KEY = 'turbofeed:role';

  /** 业务成功码（Result.ok() 的 code）。 */
  var CODE_OK = 0;
  /** 凭证失效码（ErrorCode.UNAUTHORIZED）。触发一次静默刷新后重试。 */
  var CODE_UNAUTHORIZED = 40101;

  var REFRESH_PATH = '/api/v1/auth/refresh';
  var LOGOUT_PATH = '/api/v1/auth/logout';

  // ── 令牌存取 ────────────────────────────────────────────────

  function getToken() {
    try { return global.localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function getRefreshToken() {
    try { return global.localStorage.getItem(REFRESH_KEY) || ''; } catch (e) { return ''; }
  }
  function setTokens(access, refresh) {
    try {
      if (access) global.localStorage.setItem(TOKEN_KEY, access);
      if (refresh) global.localStorage.setItem(REFRESH_KEY, refresh);
    } catch (e) { /* 存储不可用时忽略，本次会话仍可用内存令牌 */ }
  }
  /** 清除本地登录态（不吊销服务端，吊销走 TF.logout）。 */
  function clearTokens() {
    try {
      global.localStorage.removeItem(TOKEN_KEY);
      global.localStorage.removeItem(REFRESH_KEY);
      global.localStorage.removeItem(ROLE_KEY);
      global.localStorage.removeItem('turbofeed:user');
    } catch (e) { /* ignore */ }
  }

  // ── 轻提示（自带 DOM，页面已有 #toast 时复用它）──────────────

  var toastTimer = null;
  function toast(msg) {
    var el = global.document.getElementById('toast');
    if (!el) {
      el = global.document.createElement('div');
      el.id = 'toast';
      el.setAttribute('style',
        'position:fixed;left:50%;bottom:48px;transform:translateX(-50%);'
        + 'background:rgba(0,0,0,.82);color:#fff;padding:9px 18px;border-radius:18px;'
        + 'font-size:13px;z-index:99999;opacity:0;transition:opacity .2s;max-width:80vw;'
        + 'pointer-events:none;');
      global.document.body.appendChild(el);
    }
    el.textContent = String(msg == null ? '' : msg);
    el.classList.add('show');
    el.style.opacity = '1';
    if (toastTimer) global.clearTimeout(toastTimer);
    toastTimer = global.setTimeout(function () {
      el.classList.remove('show');
      el.style.opacity = '0';
    }, 2600);
  }

  // ── 登录跳转 ────────────────────────────────────────────────

  var redirecting = false;
  /** 凭证彻底失效：清本地态并回登录页（只跳一次，避免并发请求把页面刷成死循环）。 */
  function gotoLogin() {
    if (redirecting) return;
    redirecting = true;
    clearTokens();
    // 登录页自身不跳转，否则会 login → login 死循环
    var here = (global.location.pathname || '').split('/').pop() || '';
    if (here === 'login.html' || here === 'register.html') { redirecting = false; return; }
    toast('登录已过期，请重新登录');
    global.setTimeout(function () { global.location.href = 'login.html'; }, 900);
  }

  // ── 单飞刷新：并发 401 只触发一次刷新 ────────────────────────
  //
  // 一页里往往同时发多个请求（feed + 计数 + 通知），令牌过期时它们会几乎同时 401。
  // 若各自去刷新，后端「刷新令牌轮换」（旧 RT 吊销、发新 RT）会让后到的刷新请求
  // 拿着已吊销的旧 RT 而全部失败 —— 用户明明能续期却被踢回登录页。
  // 因此用共享 Promise 把并发刷新收敛成一次，其余请求等同一个结果。

  var refreshing = null;

  /**
   * 用刷新令牌换发新令牌对。
   * 刷新令牌走【请求体】而非 URL（见文件头 ②）。
   * @returns {Promise<boolean>} 是否刷新成功
   */
  function refreshTokens() {
    if (refreshing) return refreshing;
    var rt = getRefreshToken();
    if (!rt) {
      refreshing = Promise.resolve(false);
      // 立刻释放，避免把 false 永久缓存住（下次登录成功后需要能再刷）
      global.setTimeout(function () { refreshing = null; }, 0);
      return refreshing;
    }
    refreshing = global.fetch(global.TF_url(REFRESH_PATH), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ refreshToken: rt })
    })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (res) {
        if (res && res.code === CODE_OK && res.data && res.data.accessToken) {
          setTokens(res.data.accessToken, res.data.refreshToken);
          return true;
        }
        return false;
      })
      .catch(function () { return false; })
      .then(function (ok) {
        refreshing = null; // 无论成败都释放，允许后续再次尝试
        return ok;
      });
    return refreshing;
  }

  // ── 核心请求 ────────────────────────────────────────────────

  /**
   * 统一请求入口。
   *
   * @param {string} path  接口路径（以 / 开头），或完整 URL
   * @param {object} [opt]
   * @param {string} [opt.method='GET']
   * @param {object} [opt.json]     JSON 请求体（自动设 Content-Type）
   * @param {object|URLSearchParams|FormData} [opt.form] 表单请求体
   * @param {object} [opt.headers]  追加/覆盖请求头
   * @param {boolean} [opt.auth=true]      是否注入 Authorization
   * @param {boolean} [opt.raw=false]      true 时 resolve 整个 {code,message,data}
   * @param {boolean} [opt.silent=false]   true 时失败不自动 toast
   * @param {boolean} [opt.authRedirect=true] 凭证彻底失效时是否跳登录页
   * @param {boolean} [opt.retryOnAuth=true]  40101 时是否先静默刷新再重试一次
 * @param {boolean} [opt.keepalive=false]   透传给 fetch：页面卸载/跳转时请求仍需发出
 *                                          （行为埋点 beforeunload 场景必需）
   * @returns {Promise<*>} resolve res.data（raw 时为整个响应体）；失败 reject Error
   */
  function api(path, opt) {
    opt = opt || {};
    var url = /^https?:\/\//i.test(path) ? path : global.TF_url(path);
    var method = (opt.method || 'GET').toUpperCase();
    var auth = opt.auth !== false;
    var retryOnAuth = opt.retryOnAuth !== false;

    var headers = {};
    var body;
    if (opt.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opt.json);
    } else if (opt.form !== undefined) {
      // FormData（文件上传）不能手设 Content-Type，否则丢失 boundary
      if (opt.form instanceof global.FormData) {
        body = opt.form;
      } else {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        body = opt.form instanceof URLSearchParams
          ? opt.form : new URLSearchParams(opt.form);
      }
    }
    if (auth) {
      var tk = getToken();
      if (tk) headers['Authorization'] = 'Bearer ' + tk;
    }
    if (opt.headers) {
      for (var k in opt.headers) {
        if (Object.prototype.hasOwnProperty.call(opt.headers, k)) headers[k] = opt.headers[k];
      }
    }

    return global.fetch(url, { method: method, headers: headers, body: body, keepalive: opt.keepalive === true })
      .then(function (r) {
        // 框架层失败：网关没起 / 5xx / 反代 502。此时通常没有 JSON 响应体。
        if (!r.ok) {
          // 401 也走这里（若将来给 GlobalExceptionHandler 补 @ResponseStatus）
          if (r.status === 401 || r.status === 403) {
            return tryRefreshAndRetry(r.status, null, path, opt, retryOnAuth);
          }
          var e = new Error('服务异常（HTTP ' + r.status + '）');
          e.httpStatus = r.status;
          throw e;
        }
        return r.json().catch(function () {
          throw new Error('响应解析失败（非 JSON）');
        });
      })
      .then(function (res) {
        if (res === null || res === undefined) {
          // 来自上面 tryRefreshAndRetry 的透传（已自行处理）
          throw new Error('请求失败');
        }
        // 业务错误：HTTP 200 但 code != 0（后端未加 @ResponseStatus，这是常态）
        if (res.code !== CODE_OK) {
          if (res.code === CODE_UNAUTHORIZED && retryOnAuth) {
            return doRefreshAndRetry(path, opt);
          }
          var be = new Error(res.message || ('请求失败（code=' + res.code + '）'));
          be.code = res.code;
          if (res.code === CODE_UNAUTHORIZED && opt.authRedirect !== false) gotoLogin();
          throw be;
        }
        return opt.raw ? res : res.data;
      })
      .catch(function (err) {
        if (!opt.silent) toast(err && err.message ? err.message : '请求失败');
        throw err;
      });
  }

  /** HTTP 401/403 分支：刷新一次后重放；仍失败则跳登录。 */
  function tryRefreshAndRetry(status, _res, path, opt, retryOnAuth) {
    if (!retryOnAuth) {
      if (opt.authRedirect !== false) gotoLogin();
      throw new Error('未登录或凭证已失效');
    }
    return doRefreshAndRetry(path, opt);
  }

  /** 业务 40101 分支：单飞刷新 → 成功则用新令牌重放一次（重放不再重试，防死循环）。 */
  function doRefreshAndRetry(path, opt) {
    return refreshTokens().then(function (ok) {
      if (!ok) {
        if (opt.authRedirect !== false) gotoLogin();
        var e = new Error('登录已过期，请重新登录');
        e.code = CODE_UNAUTHORIZED;
        throw e;
      }
      // 重放时关掉 retryOnAuth：新令牌仍 401 说明真无权，再刷就是死循环
      var retryOpt = {};
      for (var k in opt) {
        if (Object.prototype.hasOwnProperty.call(opt, k)) retryOpt[k] = opt[k];
      }
      retryOpt.retryOnAuth = false;
      retryOpt.silent = opt.silent; // 交由外层统一 toast，避免重复提示
      return rawRequest(path, retryOpt);
    });
  }

  /**
   * 不带自动 toast / 不带重试递归的底层请求，供重放使用。
   * 与 api() 的唯一区别：40101 不再触发刷新（retryOnAuth=false 已由调用方保证）。
   */
  function rawRequest(path, opt) {
    var url = /^https?:\/\//i.test(path) ? path : global.TF_url(path);
    var headers = {};
    var body;
    if (opt.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opt.json);
    } else if (opt.form !== undefined) {
      if (opt.form instanceof global.FormData) {
        body = opt.form;
      } else {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        body = opt.form instanceof URLSearchParams ? opt.form : new URLSearchParams(opt.form);
      }
    }
    if (opt.auth !== false) {
      var tk = getToken();
      if (tk) headers['Authorization'] = 'Bearer ' + tk;
    }
    return global.fetch(url, {
      method: (opt.method || 'GET').toUpperCase(), headers: headers, body: body
    }).then(function (r) {
      if (!r.ok) throw new Error('服务异常（HTTP ' + r.status + '）');
      return r.json();
    }).then(function (res) {
      if (res.code !== CODE_OK) {
        if (res.code === CODE_UNAUTHORIZED && opt.authRedirect !== false) gotoLogin();
        throw new Error(res.message || ('请求失败（code=' + res.code + '）'));
      }
      return opt.raw ? res : res.data;
    });
  }

  /**
   * 登出：吊销服务端令牌（全端失效）+ 清本地态 + 跳登录页。
   * 刷新令牌走【请求体】，不再拼在 URL 上（P0-2）。
   * fail-open：吊销请求失败也照样清本地态并跳转，不能让用户"退不出去"。
   */
  function logout() {
    var tk = getToken();
    var rt = getRefreshToken();
    var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (tk) headers['Authorization'] = 'Bearer ' + tk;
    return global.fetch(global.TF_url(LOGOUT_PATH), {
      method: 'POST',
      headers: headers,
      body: new URLSearchParams(rt ? { refreshToken: rt } : {})
    })
      .catch(function () { /* 吊销失败不阻断登出 */ })
      .then(function () {
        clearTokens();
        redirecting = false;
        global.location.href = 'login.html';
      });
  }

  // ── 主动续期：不等 401，提前换新令牌 ────────────────────────
  //
  // 为什么需要它（而不只靠 401 重试）：访问令牌只有 30 分钟
  // （JwtProperties.expireSeconds=1800），刷新令牌 7 天。若只在收到 40101 后才刷新，
  // 那些【尚未迁移到 TF.api 的裸 fetch 调用】在令牌过期时既不会刷新也不会跳转，
  // 表现为"页面所有操作静默失败"——这正是改造前的线上症状。
  // 主动续期让令牌在过期前就换成新的，于是【所有页面（含未迁移的裸 fetch）都受益】，
  // 因为它们每次请求都从 localStorage 重新读取令牌。
  //
  // 提前量取 5 分钟：足够覆盖一次页面操作 + 时钟误差，又不会频繁刷新。
  // 定时器上限 24h（setTimeout 超过 ~24.8 天会溢出），超长按上限截断并靠
  // visibilitychange 兜底重排——后台标签页的定时器会被浏览器节流，切回前台时重算一次。

  var REFRESH_AHEAD_MS = 5 * 60 * 1000;
  var MAX_TIMER_MS = 24 * 60 * 60 * 1000;
  var renewTimer = null;

  /** 解析 JWT 载荷（不校验签名，仅为读 exp；解析失败返回 null）。 */
  function decodeJwt(token) {
    if (!token) return null;
    try {
      var parts = String(token).split('.');
      if (parts.length < 2) return null;
      var b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      while (b64.length % 4) b64 += '=';
      return JSON.parse(decodeURIComponent(escape(global.atob(b64))));
    } catch (e) {
      return null;
    }
  }

  /** 访问令牌剩余毫秒数；无法判定返回 null。 */
  function tokenRemainingMs() {
    var payload = decodeJwt(getToken());
    if (!payload || typeof payload.exp !== 'number') return null;
    return payload.exp * 1000 - Date.now();
  }

  /**
   * 排定下一次续期。幂等：重复调用只会重排，不会叠加定时器。
   * 无访问令牌 / 无刷新令牌 / 无法解析 exp 时不排定（交由 401 路径兜底）。
   */
  function scheduleRenew() {
    if (renewTimer) { global.clearTimeout(renewTimer); renewTimer = null; }
    if (!getToken() || !getRefreshToken()) return;
    var remain = tokenRemainingMs();
    if (remain === null) return;

    // 已过期或即将过期（提前量内）→ 立刻刷新；否则等到「过期前 5 分钟」
    var delay = remain <= REFRESH_AHEAD_MS ? 0 : remain - REFRESH_AHEAD_MS;
    if (delay > MAX_TIMER_MS) delay = MAX_TIMER_MS;

    renewTimer = global.setTimeout(function () {
      renewTimer = null;
      if (!getToken() || !getRefreshToken()) return; // 期间已登出
      refreshTokens().then(function (ok) {
        if (ok) {
          scheduleRenew(); // 换新令牌后按新的 exp 重排
        } else {
          // 刷新失败：RT 已吊销/过期 → 清本地态；跳登录交给下一次请求的 401 路径，
          // 避免用户停留在页面填表单时被突然弹走。
          clearTokens();
        }
      });
    }, delay);
  }

  // 切回前台时重排一次：后台标签页定时器被浏览器节流，可能已错过续期时点。
  try {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'visible') scheduleRenew();
    });
  } catch (e) { /* 非浏览器环境（如测试）忽略 */ }

  // 脚本加载即排定一次：覆盖"打开页面就挂机"的场景。
  scheduleRenew();

  global.TF = global.TF || {};
  global.TF.api = api;
  global.TF.logout = logout;
  global.TF.refreshTokens = refreshTokens;
  global.TF.scheduleRenew = scheduleRenew;
  global.TF.tokenRemainingMs = tokenRemainingMs;
  global.TF.getToken = getToken;
  global.TF.getRefreshToken = getRefreshToken;
  global.TF.setTokens = setTokens;
  global.TF.clearTokens = clearTokens;
  global.TF.toast = toast;
  global.TF.gotoLogin = gotoLogin;
})(window);
