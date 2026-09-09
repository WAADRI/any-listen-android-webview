/*
 * AnyListen Android 页面桥（注入到 WebView 主 world，document 加载早期执行）。
 * 设计依据：any-listen 播放器完全在页面内（new Audio()，不挂 DOM），
 *   并通过 navigator.mediaSession.setActionHandler 暴露控制入口。
 * - 上行：hook window.Audio 捕获实例 -> 事件推送状态给原生 anyListenNative.onMediaState
 * - 下行：hook mediaSession.setActionHandler 保存页面真实处理函数 ->
 *         window.__anylistenBridge.{play,pause,next,prev,seek,stop}
 * 脚本幂等（window.__anylistenBridgeReady 守卫），可重复注入。
 */
(function () {
  if (window.__anylistenBridgeReady) return;
  window.__anylistenBridgeReady = true;

  var instances = [];
  var lastJson = '';
  var lastPushAt = 0;
  var THROTTLE_MS = 500;

  function mediaMeta() {
    var out = { title: '', artist: '', album: '' };
    try {
      var m = navigator.mediaSession && navigator.mediaSession.metadata;
      if (m) {
        out.title = m.title || '';
        out.artist = m.artist || '';
        out.album = m.album || '';
      }
    } catch (e) {}
    return out;
  }

  // 主播放器判定：优先“正在播放、非静音、有 src”，否则最近创建的非静音实例
  function pickMain() {
    for (var i = instances.length - 1; i >= 0; i--) {
      var el = instances[i];
      if (el && el.src && !el.paused && !el.muted && !el.ended) return el;
    }
    for (var j = instances.length - 1; j >= 0; j--) {
      var e2 = instances[j];
      if (e2 && e2.src && !e2.muted) return e2;
    }
    return null;
  }

  function collectState(a) {
    var d = (a && isFinite(a.duration) && a.duration > 0) ? a.duration : 0;
    var meta = mediaMeta();
    return {
      playing: !!(a && a.src && !a.paused && !a.ended),
      currentTime: Math.floor(((a && a.currentTime) || 0) * 1000),
      duration: Math.floor(d * 1000),
      title: meta.title || document.title || '',
      artist: meta.artist || ''
    };
  }

  function push(force) {
    var a = pickMain();
    var native = window.anyListenNative;
    if (!a || !native) return;
    var now = Date.now();
    if (!force && now - lastPushAt < THROTTLE_MS) return;
    var json = JSON.stringify(collectState(a));
    if (!force && json === lastJson) return;
    lastJson = json;
    lastPushAt = now;
    try {
      native.onMediaState(json);
    } catch (e) {}
  }

  function hook(el) {
    instances.push(el);
    [
      'play', 'pause', 'playing', 'ended', 'timeupdate',
      'loadedmetadata', 'durationchange', 'emptied', 'error'
    ].forEach(function (ev) {
      el.addEventListener(ev, function () { push(false); }, false);
    });
  }

  // ---- hook window.Audio ----
  var RealAudio = window.Audio;
  function AnyListenAudio(src) {
    var el = new RealAudio(src);
    hook(el);
    return el;
  }
  AnyListenAudio.prototype = RealAudio.prototype;
  window.Audio = AnyListenAudio;

  // ---- hook mediaSession.setActionHandler，保存页面注册的真实处理函数 ----
  var handlers = {};
  try {
    var origSet = navigator.mediaSession.setActionHandler;
    if (origSet) {
      navigator.mediaSession.setActionHandler = function (type, fn) {
        if (typeof fn === 'function') handlers[type] = fn;
        else delete handlers[type];
        return origSet.call(navigator.mediaSession, type, fn);
      };
    }
  } catch (e) {}

  // ---- 原生下行控制入口 ----
  window.__anylistenBridge = {
    play: function () { if (handlers.play) handlers.play(); },
    pause: function () { if (handlers.pause) handlers.pause(); },
    next: function () { if (handlers.nexttrack) handlers.nexttrack(); },
    prev: function () { if (handlers.previoustrack) handlers.previoustrack(); },
    seek: function (sec) { if (handlers.seekto) handlers.seekto({ seekTime: sec }); },
    stop: function () { if (handlers.stop) handlers.stop(); },
    getState: function () {
      var a = pickMain();
      return a ? JSON.stringify(collectState(a)) : '{}';
    },
    bridgeReady: true
  };

  // 兜底：若注入发生在页面已开始播放之后，主动推一次状态
  try {
    setTimeout(function () { push(true); }, 500);
  } catch (e) {}
})();
