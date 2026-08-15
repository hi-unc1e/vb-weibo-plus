(() => {
  "use strict";

  const PAGE_SIZE = 50;
  const EARLIER_LOAD_THRESHOLD = 120;
  const LAST_GROUP_KEY = "weibo-chat:last-gid";
  const DESKTOP_PREFERENCE_KEY = "weibo-chat:prefer-desktop";
  const MESSAGE_URL_PATTERN = /https?:\/\/[A-Za-z0-9._~:/?#@!$&'()*+,;=%\[\]-]+/g;
  const EMOJI_PHRASE_PATTERN = /\[[^\[\]]+\]/g;
  const EMOJI_IMAGE_TEST = /\[(\/[0-9a-z]+\.png)\]/i;
  const EMOJI_IMAGE_BASE = "https://img.t.sinajs.cn/t4/appstyle/expression/emimage";
  const MEDIA_TYPE = {IMAGE: 1, VIDEO: 10, VIDEO_OR_REDPACKET: 13, WEIBO_CARD: 14};
  const SYSTEM_SENDER_NAME = "粉丝群";
  const RED_PACKET_TEXT = "收到红包消息";
  const MAX_IMAGE_SIZE = 20 * 1024 * 1024;
  const MAX_VIDEO_SIZE = 100 * 1024 * 1024;
  const WEIBO_EMOJI_MAP = (typeof window !== "undefined" && window.WEIBO_EMOJI_MAP) || {};
  const elements = {
    app: document.querySelector("#app"),
    groupsList: document.querySelector("#groups-list"),
    groupsState: document.querySelector("#groups-state"),
    retryGroups: document.querySelector("#retry-groups"),
    groupSearch: document.querySelector("#group-search"),
    loginBanner: document.querySelector("#login-banner"),
    loginSheet: document.querySelector("#login-sheet"),
    loginQr: document.querySelector("#login-qr"),
    loginQrImg: document.querySelector("#login-qr-img"),
    qrLoading: document.querySelector("#qr-loading"),
    loginNote: document.querySelector("#login-note"),
    desktopLink: document.querySelector("#desktop-link"),
    backButton: document.querySelector("#back-button"),
    currentGroup: document.querySelector("#current-group"),
    currentSize: document.querySelector("#current-size"),
    currentAvatar: document.querySelector("#current-group-avatar"),
    messages: document.querySelector("#messages"),
    newMessages: document.querySelector("#new-messages"),
    imageViewer: document.querySelector("#image-viewer"),
    imageViewerImage: document.querySelector("#image-viewer img"),
    imageViewerState: document.querySelector("#image-viewer-state"),
    composer: document.querySelector("#composer"),
    composerHint: document.querySelector("#composer-hint"),
    sendButton: document.querySelector("#send-button"),
    attachOpen: document.querySelector("#attach-open"),
    attachInput: document.querySelector("#attach-input"),
    composerAttachment: document.querySelector("#composer-attachment"),
    attachmentPreviewImage: document.querySelector("#attachment-preview-image"),
    attachmentPreviewVideo: document.querySelector("#attachment-preview-video"),
    attachmentRemove: document.querySelector("#attachment-remove")
  };
  const state = {
    groups: [],
    currentGid: null,
    messages: new Map(),
    beforeCursor: null,
    hasMore: false,
    refreshingGroups: false,
    refreshing: false,
    loadingEarlier: false,
    followingLatest: true,
    sending: false,
    pendingAttachment: null,
    loginCheckTick: 0,
    loginPending: false
  };

  /* ---------- 基础工具 ---------- */

  // 借鉴 post 页的增强版：解析后端 {code,msg} 错误体并挂 status，供 401 统一识别
  async function fetchJson(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) {
      let msg = `HTTP ${response.status}`;
      try {
        const body = await response.json();
        if (body.msg) msg = body.msg;
      } catch (_) {
        // 非 JSON 错误体，保留默认消息
      }
      const error = new Error(msg);
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  function localDateValue(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }

  function initials(value, fallback) {
    return value?.trim().slice(0, 1) || fallback;
  }

  function avatar(group, className, profileUrl) {
    const container = document.createElement(profileUrl ? "a" : "span");
    container.className = className;
    if (profileUrl) {
      container.href = profileUrl;
      container.target = "_blank";
      container.rel = "noopener noreferrer";
      container.setAttribute("aria-label", `查看${group.name || "群友"}的微博主页`);
    } else {
      container.setAttribute("aria-hidden", "true");
    }
    if (group.avatar) {
      const image = document.createElement("img");
      image.src = `/chat/image?${new URLSearchParams({url: group.avatar})}`;
      image.alt = "";
      container.append(image);
    } else {
      container.textContent = initials(group.name, "群");
    }
    return container;
  }

  /* ---------- 消息文本与表情 ---------- */

  function emojiImageUrl(token) {
    const imageMatch = token.match(EMOJI_IMAGE_TEST);
    if (imageMatch) {
      return EMOJI_IMAGE_BASE + imageMatch[1];
    }
    return WEIBO_EMOJI_MAP[token];
  }

  function appendTextSegment(container, text) {
    let offset = 0;
    for (const match of text.matchAll(EMOJI_PHRASE_PATTERN)) {
      const url = emojiImageUrl(match[0]);
      if (!url) continue;
      if (match.index > offset) {
        container.append(document.createTextNode(text.slice(offset, match.index)));
      }
      const img = document.createElement("img");
      img.className = "emoji";
      img.src = url;
      img.alt = match[0];
      img.loading = "lazy";
      container.append(img);
      offset = match.index + match[0].length;
    }
    if (offset < text.length) {
      container.append(document.createTextNode(text.slice(offset)));
    }
  }

  function appendMessageText(container, text) {
    let offset = 0;
    for (const match of text.matchAll(MESSAGE_URL_PATTERN)) {
      appendTextSegment(container, text.slice(offset, match.index));
      const link = document.createElement("a");
      link.href = match[0];
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = match[0];
      container.append(link);
      offset = match.index + match[0].length;
    }
    appendTextSegment(container, text.slice(offset));
  }

  function appendWeiboCard(container, urlObject) {
    const status = urlObject.status || {};
    const author = status.user?.screen_name?.trim() || "";
    const rawText = (status.text || "").replace(/<[^>]+>/g, "").trim();
    const summary = rawText.length > 100 ? rawText.slice(0, 100) + "…" : rawText;
    const link = urlObject.url_ori || urlObject.info?.url_long || "";
    container.classList.add("weibo-card");
    if (author) {
      const authorEl = document.createElement("div");
      authorEl.className = "weibo-card-author";
      authorEl.textContent = author;
      container.append(authorEl);
    }
    if (summary) {
      const summaryEl = document.createElement("div");
      summaryEl.className = "weibo-card-summary";
      summaryEl.textContent = summary;
      container.append(summaryEl);
    }
    if (link) {
      const linkEl = document.createElement("a");
      linkEl.className = "weibo-card-link";
      linkEl.href = link;
      linkEl.target = "_blank";
      linkEl.rel = "noopener noreferrer";
      linkEl.textContent = "查看微博";
      container.append(linkEl);
    }
  }

  /* ---------- 群列表 ---------- */

  function groupPreview(group) {
    const sender = group.latestSenderName?.trim() || "";
    const message = group.latestMessage?.trim() || "";
    if (sender || message) {
      return sender ? `${sender}：${message}` : message;
    }
    return `${group.maxMember || group.memberCount} 人群`;
  }

  function renderGroups() {
    elements.groupsList.replaceChildren();
    state.groups.forEach(group => {
      const button = document.createElement("button");
      button.className = "group-row";
      button.type = "button";
      button.dataset.gid = String(group.gid);
      if (group.gid === state.currentGid) {
        button.classList.add("active");
        button.setAttribute("aria-current", "true");
      }
      const previewText = groupPreview(group);
      button.setAttribute("aria-label",
        `${group.name || `群聊 ${group.gid}`}，${previewText}`);
      button.append(avatar(group, "group-avatar"));
      const copy = document.createElement("span");
      copy.className = "group-copy";
      const name = document.createElement("span");
      name.className = "group-name";
      name.textContent = group.name || `群聊 ${group.gid}`;
      const size = document.createElement("span");
      size.className = "group-preview";
      size.textContent = previewText;
      copy.append(name, size);
      button.append(copy);
      button.addEventListener("click", () => selectGroup(group.gid));
      elements.groupsList.append(button);
    });
    filterGroups(elements.groupSearch.value);
  }

  function filterGroups(value) {
    const keyword = value.trim().toLocaleLowerCase("zh-CN");
    elements.groupsList.querySelectorAll(".group-row").forEach(row => {
      row.hidden = !row.textContent.toLocaleLowerCase("zh-CN").includes(keyword);
    });
  }

  /* ---------- 消息渲染 ---------- */

  function isAdminSender(senderId) {
    if (!Number.isSafeInteger(senderId) || senderId <= 0) return false;
    const group = state.groups.find(item => item.gid === state.currentGid);
    return Array.isArray(group?.admins) && group.admins.includes(senderId);
  }

  const timeFormatter = new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit", minute: "2-digit", hour12: false
  });
  const dateDividerFormatter = new Intl.DateTimeFormat("zh-CN", {
    month: "long", day: "numeric"
  });
  const dateDividerYearFormatter = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "long", day: "numeric"
  });

  function formatTime(timestamp) {
    return timeFormatter.format(new Date(timestamp));
  }

  function messageDayKey(timestamp) {
    const date = new Date(timestamp);
    return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
  }

  function dateDividerLabel(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    if (date.getFullYear() === now.getFullYear()) {
      return dateDividerFormatter.format(date);
    }
    return dateDividerYearFormatter.format(date);
  }

  function messageElement(message, onMediaLoad = null) {
    const article = document.createElement("article");
    article.className = "message";
    article.dataset.mid = String(message.mid);
    if (isAdminSender(message.senderId)) article.classList.add("admin-message");
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    if (message.fileUrl) {
      const link = document.createElement("a");
      link.className = "file-download";
      link.href = message.fileUrl;
      link.download = message.text || "";
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = message.text || "下载文件";
      bubble.append(link);
    } else if (message.mediaType === MEDIA_TYPE.WEIBO_CARD && message.urlObjects?.[0]?.status) {
      appendWeiboCard(bubble, message.urlObjects[0]);
    } else {
      appendMessageText(bubble, message.text || `[${message.msgTypeName || "消息"}]`);
    }
    if (message.senderName?.trim() === SYSTEM_SENDER_NAME) {
      article.classList.add("system-message");
      article.append(bubble);
      return article;
    }
    article.append(avatar({
      name: message.senderName,
      avatar: message.senderAvatar
    }, "message-avatar", Number.isSafeInteger(message.senderId) && message.senderId > 0
      ? `https://weibo.com/u/${message.senderId}`
      : ""));
    const content = document.createElement("div");
    content.className = "message-content";
    const meta = document.createElement("div");
    meta.className = "message-meta";
    meta.textContent = `${message.senderName || "未知成员"} · ${formatTime(message.createdAt)}`;
    const media = messageMedia(message, onMediaLoad);
    const hidesBubbleText = media
      && ["分享图片", "分享视频", "[动画表情]"].includes(message.text?.trim());
    content.append(meta);
    if (!hidesBubbleText) content.append(bubble);
    if (media) content.append(media);
    article.append(content);
    return article;
  }

  function messageMedia(message, onLoad) {
    if (!message.previewUrl) return null;
    const button = document.createElement("button");
    button.type = "button";
    const image = document.createElement("img");
    // 首屏图片需立即加载以触发 scrollToBottom，懒加载会让 load 回调无法及时跟随底部
    image.loading = onLoad ? "eager" : "lazy";
    image.alt = "";
    // 先注册 load 再设 src，避免缓存命中时 load 在监听前触发而漏掉跟随到底部
    if (onLoad) image.addEventListener("load", onLoad, {once: true});
    image.src = message.previewUrl;
    button.append(image);
    const label = document.createElement("span");
    if (message.videoUrl) {
      button.className = "media-preview video-preview";
      button.setAttribute("aria-label", "播放视频");
      label.textContent = "▶";
      button.append(label);
      button.addEventListener("click", () => {
        const video = document.createElement("video");
        video.src = message.videoUrl;
        video.controls = true;
        video.preload = "metadata";
        video.playsInline = true;
        video.setAttribute("aria-label", "群聊视频");
        button.replaceWith(video);
        video.play().catch(() => {
          // 自动播放被浏览器阻止时静默处理，controls 已开启供用户手动播放
        });
      }, {once: true});
    } else {
      button.className = "media-preview image-preview";
      button.setAttribute("aria-label", "查看原图");
      button.addEventListener("click", () => openImage(message.originalUrl || message.previewUrl));
    }
    image.addEventListener("error", () => {
      image.hidden = true;
      label.textContent = "媒体加载失败";
      button.append(label);
      button.classList.add("media-failed");
      if (onLoad) onLoad();
    }, {once: true});
    return button;
  }

  function compareMessages(left, right) {
    return left.createdAt - right.createdAt || left.mid - right.mid;
  }

  function renderMessages(forceFollow = false) {
    const ordered = [...state.messages.values()].sort(compareMessages);
    const onLoad = forceFollow ? () => scrollToBottom(true) : () => scrollToBottom();

    // 已渲染的消息元素按 mid 索引（日期分隔条单独维护）
    const existingByMid = new Map();
    for (const el of elements.messages.children) {
      if (el.dataset.mid) existingByMid.set(Number(el.dataset.mid), el);
    }
    const desiredMids = new Set(ordered.map(message => message.mid));

    // 无交集时直接全量重建（切换群聊、首次加载）
    const hasCommon = ordered.some(message => existingByMid.has(message.mid));
    if (!hasCommon) {
      elements.messages.replaceChildren(...ordered.map(message => messageElement(message, onLoad)));
    } else {
      // 移除不再存在的消息
      for (const [mid, el] of existingByMid) {
        if (!desiredMids.has(mid)) el.remove();
      }
      // 按顺序插入新消息、校正位置
      let prevEl = null;
      for (const message of ordered) {
        let el = existingByMid.get(message.mid);
        if (el) {
          const expectedNext = prevEl ? prevEl.nextElementSibling
            : elements.messages.firstElementChild;
          if (el !== expectedNext) {
            if (prevEl) prevEl.after(el);
            else elements.messages.prepend(el);
          }
        } else {
          el = messageElement(message, onLoad);
          if (prevEl) prevEl.after(el);
          else elements.messages.prepend(el);
        }
        prevEl = el;
      }
    }
    refreshDateDividers();
  }

  // 相邻消息跨天时在中间插入日期分隔条，消息增删后全量校正
  function refreshDateDividers() {
    const messagesEl = elements.messages;
    let previousKey = null;
    let previousMessageEl = null;
    for (const messageEl of [...messagesEl.querySelectorAll(".message")]) {
      const message = state.messages.get(Number(messageEl.dataset.mid));
      if (!message) continue;
      const key = messageDayKey(message.createdAt);
      const existingDivider = messageEl.previousElementSibling?.classList?.contains("date-divider")
        ? messageEl.previousElementSibling
        : null;
      if (key !== previousKey) {
        if (existingDivider?.dataset.date !== key) {
          const divider = document.createElement("div");
          divider.className = "date-divider";
          divider.dataset.date = key;
          divider.textContent = dateDividerLabel(message.createdAt);
          if (existingDivider) existingDivider.replaceWith(divider);
          else messageEl.before(divider);
        }
      } else if (existingDivider) {
        existingDivider.remove();
      }
      previousKey = key;
      previousMessageEl = messageEl;
    }
    // 列表顶部的分隔条（首条消息总有一个）
    const firstMessage = messagesEl.querySelector(".message");
    if (firstMessage) {
      const topDivider = messagesEl.firstElementChild?.classList?.contains("date-divider")
        ? messagesEl.firstElementChild
        : null;
      const message = state.messages.get(Number(firstMessage.dataset.mid));
      if (message && !topDivider) {
        const divider = document.createElement("div");
        divider.className = "date-divider";
        divider.dataset.date = messageDayKey(message.createdAt);
        divider.textContent = dateDividerLabel(message.createdAt);
        messagesEl.prepend(divider);
      }
    }
  }

  /* ---------- 滚动与游标加载 ---------- */

  function captureScrollAnchor() {
    const containerTop = elements.messages.getBoundingClientRect().top;
    // 日期分隔条没有 mid，锚定必须落在消息元素上，否则向上加载后无法恢复位置
    const anchor = [...elements.messages.querySelectorAll(".message")].find(element =>
      element.getBoundingClientRect().bottom > containerTop);
    if (!anchor) return null;
    return {
      mid: anchor.dataset.mid,
      top: anchor.getBoundingClientRect().top
    };
  }

  function restoreScrollAnchor(anchor) {
    if (!anchor) return;
    const renderedAnchor = elements.messages.querySelector(`[data-mid="${anchor.mid}"]`);
    if (renderedAnchor) {
      elements.messages.scrollTop += renderedAnchor.getBoundingClientRect().top - anchor.top;
    }
  }

  function scrollToBottom(force = false) {
    if (force || state.followingLatest) {
      elements.messages.scrollTop = elements.messages.scrollHeight;
    }
  }

  function isNearBottom() {
    return elements.messages.scrollHeight
      - elements.messages.scrollTop
      - elements.messages.clientHeight < 80;
  }

  async function loadMessages(beforeCursor = null) {
    const isLatestPage = beforeCursor === null;
    const anchor = isLatestPage ? null : captureScrollAnchor();
    const gid = state.currentGid;
    const query = new URLSearchParams({
      gid: String(gid), size: String(PAGE_SIZE)
    });
    if (!isLatestPage) {
      query.set("beforeCreatedAt", String(beforeCursor.createdAt));
      query.set("beforeMid", String(beforeCursor.mid));
    }
    try {
      const result = await fetchJson(`/chat/messages/cursor?${query}`, {cache: "no-store"});
      if (state.currentGid !== gid) return;
      result.items.forEach(message => state.messages.set(message.mid, message));
      state.beforeCursor = result.hasMore && result.nextBeforeCreatedAt !== null
        && result.nextBeforeMid !== null
        ? {createdAt: result.nextBeforeCreatedAt, mid: result.nextBeforeMid}
        : null;
      state.hasMore = result.hasMore;
      if (isLatestPage) state.followingLatest = true;
      renderMessages(isLatestPage);
      if (isLatestPage) {
        scrollToBottom(true);
      } else {
        restoreScrollAnchor(anchor);
      }
    } catch (error) {
      if (error.status === 401) {
        showLoginBanner();
        return;
      }
      console.warn("加载消息失败：", error);
    }
  }

  async function maybeLoadEarlierMessages() {
    if (!state.currentGid || !state.hasMore || state.loadingEarlier || state.refreshing) return;
    if (elements.messages.scrollTop > EARLIER_LOAD_THRESHOLD
      || elements.messages.scrollHeight <= elements.messages.clientHeight) return;
    if (!state.beforeCursor) return;
    state.loadingEarlier = true;
    try {
      await loadMessages(state.beforeCursor);
    } finally {
      state.loadingEarlier = false;
    }
  }

  async function refreshMessages() {
    if (!state.currentGid || state.refreshing || document.hidden) return;
    state.refreshing = true;
    const gid = state.currentGid;
    // 页面隐藏期间视为未在阅读，回来后不自动贴底，保留上次阅读位置
    const followedLatest = state.followingLatest && isNearBottom();
    const knownMids = new Set(state.messages.keys());
    const query = new URLSearchParams({
      gid: String(gid), size: String(PAGE_SIZE)
    });
    try {
      const result = await fetchJson(`/chat/messages/cursor?${query}`, {cache: "no-store"});
      if (state.currentGid !== gid) return;
      result.items.forEach(message => state.messages.set(message.mid, message));
      const added = result.items.some(message => !knownMids.has(message.mid));
      if (added) {
        state.followingLatest = followedLatest;
        renderMessages();
        if (followedLatest) {
          elements.messages.scrollTop = elements.messages.scrollHeight;
        } else {
          elements.newMessages.hidden = false;
        }
      }
    } catch (error) {
      if (error.status === 401) {
        showLoginBanner();
        return;
      }
      console.warn("刷新消息失败：", error);
    } finally {
      state.refreshing = false;
      if (state.currentGid === gid) maybeLoadEarlierMessages();
    }
  }

  /* ---------- 群切换与刷新 ---------- */

  async function selectGroup(gid) {
    const group = state.groups.find(item => item.gid === gid);
    if (!group) return;
    state.currentGid = gid;
    state.messages.clear();
    state.beforeCursor = null;
    state.hasMore = false;
    elements.newMessages.hidden = true;
    localStorage.setItem(LAST_GROUP_KEY, String(gid));
    elements.currentGroup.textContent = group.name || `群聊 ${group.gid}`;
    updateChatHeader(group);
    elements.attachOpen.disabled = false;
    document.title = `微博群聊 - ${elements.currentGroup.textContent}`;
    elements.groupsList.querySelectorAll(".group-row").forEach(row => {
      const active = row.dataset.gid === String(gid);
      row.classList.toggle("active", active);
      if (active) row.setAttribute("aria-current", "true");
      else row.removeAttribute("aria-current");
    });
    showChatView();
    // 进入会话压入一条历史记录，物理返回键回到群列表
    history.pushState({view: "chat"}, "");
    updateSendButton();
    await loadMessages(null, null);
  }

  function updateChatHeader(group) {
    const count = typeof group.messageCount === "number"
      ? `${group.maxMember || group.memberCount} 人群 · ${group.messageCount} 条消息`
      : `${group.maxMember || group.memberCount} 人群`;
    elements.currentSize.textContent = count;
    const next = avatar(group, "chat-avatar");
    elements.currentAvatar.replaceWith(next);
    elements.currentAvatar = next;
  }

  function groupsEqual(prev, next) {
    if (prev.length !== next.length) return false;
    return prev.every((group, i) => {
      const other = next[i];
      return group.gid === other.gid
        && group.name === other.name
        && group.avatar === other.avatar
        && group.latestMessage === other.latestMessage
        && group.latestSenderName === other.latestSenderName
        && group.messageCount === other.messageCount
        && group.memberCount === other.memberCount
        && group.maxMember === other.maxMember
        && sameAdmins(group.admins, other.admins);
    });
  }

  function sameAdmins(a, b) {
    if (a === b) return true;
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => v === b[i]);
  }

  async function refreshGroups() {
    if (state.refreshingGroups || document.hidden) return;
    state.refreshingGroups = true;
    try {
      const groups = await fetchJson("/chat/groups", {cache: "no-store"});
      if (groupsEqual(state.groups, groups)) return;
      state.groups = groups;
      renderGroups();
      if (state.currentGid) {
        const group = state.groups.find(item => item.gid === state.currentGid);
        if (group) updateChatHeader(group);
      }
    } catch (error) {
      if (error.status === 401) {
        showLoginBanner();
        return;
      }
      console.warn("刷新群聊列表失败：", error);
    } finally {
      state.refreshingGroups = false;
    }
  }

  function refreshView() {
    refreshGroups();
    refreshMessages();
    maybeCheckLoginStatus();
  }

  /* ---------- 登录 ---------- */

  const LOGIN_CHECK_INTERVAL = 60;
  const QR_IMAGE_INTERVAL = 10000;
  let qrImageTimer = null;

  function showLoginBanner() {
    elements.loginBanner.hidden = false;
  }

  function refreshQrImage() {
    const img = new Image();
    img.onload = () => {
      elements.qrLoading.hidden = true;
      elements.loginQrImg.src = img.src;
      elements.loginQrImg.hidden = false;
    };
    img.src = `/weibo/login/qr/image?t=${Date.now()}`;
  }

  function startQrImagePolling() {
    elements.loginQrImg.hidden = true;
    elements.qrLoading.hidden = false;
    qrImageTimer = setInterval(refreshQrImage, QR_IMAGE_INTERVAL);
    setTimeout(refreshQrImage, 3000);
  }

  function stopQrImagePolling() {
    if (qrImageTimer) {
      clearInterval(qrImageTimer);
      qrImageTimer = null;
    }
    elements.loginQrImg.hidden = true;
    elements.qrLoading.hidden = true;
  }

  function maybeCheckLoginStatus() {
    if (document.hidden || state.loginPending) return;
    state.loginCheckTick += 1;
    if (state.loginCheckTick < LOGIN_CHECK_INTERVAL) return;
    state.loginCheckTick = 0;
    checkLoginStatus();
  }

  async function checkLoginStatus() {
    try {
      const response = await fetch("/weibo/login/status", {cache: "no-store"});
      if (!response.ok) return;
      const result = await response.json();
      elements.loginBanner.hidden = result.valid !== false;
    } catch (error) {
      console.warn("检查登录状态失败：", error);
    }
  }

  function openLoginSheet() {
    elements.loginSheet.hidden = false;
    if (!state.loginPending) startQrLogin();
  }

  function closeLoginSheet() {
    elements.loginSheet.hidden = true;
  }

  async function startQrLogin() {
    if (state.loginPending) return;
    state.loginPending = true;
    elements.loginQr.disabled = true;
    elements.loginQr.textContent = "扫码中，请稍候…";
    elements.loginNote.textContent = "打开微博 App 扫码确认登录，等待期间请勿关闭此页。";
    startQrImagePolling();
    try {
      const response = await fetch("/weibo/login/qr", {method: "POST"});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      elements.loginBanner.hidden = true;
      closeLoginSheet();
      await initialize();
    } catch {
      elements.loginNote.textContent = "扫码登录失败，请点击按钮重试。";
    } finally {
      stopQrImagePolling();
      state.loginPending = false;
      elements.loginQr.disabled = false;
      elements.loginQr.textContent = "📱 开始扫码登录";
    }
  }

  /* ---------- 发送 ---------- */

  function setComposerHint(text, level) {
    elements.composerHint.textContent = text;
    elements.composerHint.classList.toggle("is-sending", level === "sending");
    elements.composerHint.classList.toggle("is-error", level === "error");
  }

  function updateSendButton() {
    const hasText = elements.composer.value.trim().length > 0;
    const hasAttachment = Boolean(state.pendingAttachment);
    elements.sendButton.disabled = state.sending || !state.currentGid
      || (!hasText && !hasAttachment);
  }

  function autoGrowComposer() {
    const textarea = elements.composer;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, window.innerHeight * 0.3)}px`;
  }

  function setPendingAttachment(file) {
    if (!file) return;
    const isImage = file.type.startsWith("image/");
    const validType = isImage ? true : file.type === "video/mp4";
    if (!validType) {
      setComposerHint("仅支持图片或 MP4 视频。", "error");
      return;
    }
    const maxSize = isImage ? MAX_IMAGE_SIZE : MAX_VIDEO_SIZE;
    if (file.size > maxSize) {
      setComposerHint(isImage ? "图片不能超过 20MB。" : "视频不能超过 100MB。", "error");
      return;
    }
    clearPendingAttachment();
    const kind = isImage ? "image" : "video";
    state.pendingAttachment = {kind, file, url: URL.createObjectURL(file)};
    elements.attachmentPreviewImage.src = isImage ? state.pendingAttachment.url : "";
    elements.attachmentPreviewImage.hidden = !isImage;
    elements.attachmentPreviewVideo.src = isImage ? "" : state.pendingAttachment.url;
    elements.attachmentPreviewVideo.hidden = isImage;
    elements.composerAttachment.hidden = false;
    setComposerHint(isImage ? "图片已就绪，点发送。" : "视频已就绪，点发送。", null);
    updateSendButton();
  }

  function clearPendingAttachment() {
    if (state.pendingAttachment) {
      URL.revokeObjectURL(state.pendingAttachment.url);
    }
    state.pendingAttachment = null;
    elements.composerAttachment.hidden = true;
    elements.attachmentPreviewImage.src = "";
    elements.attachmentPreviewImage.hidden = false;
    elements.attachmentPreviewVideo.src = "";
    elements.attachmentPreviewVideo.hidden = true;
    if (elements.attachInput.value) {
      elements.attachInput.value = "";
    }
    updateSendButton();
  }

  async function handleSendError(response, fallbackMessage) {
    const error = await response.json().catch(() => ({}));
    if (response.status === 401) {
      showLoginBanner();
      setComposerHint(error.msg || "登录已失效，请扫码后重试。", "error");
      return;
    }
    if (response.status === 409) {
      setComposerHint(error.msg || "消息已发出，但本地同步失败，稍后会自动补全。", "error");
    } else {
      setComposerHint(error.msg || fallbackMessage, "error");
    }
  }

  async function sendAttachment() {
    if (state.sending || !state.currentGid || !state.pendingAttachment) return;
    const kind = state.pendingAttachment.kind;
    const endpoint = kind === "image" ? "/chat/messages/sendImage" : "/chat/messages/sendVideo";
    state.sending = true;
    setSendingState(true);
    setComposerHint("发送中…", "sending");
    try {
      const formData = new FormData();
      formData.append("gid", String(state.currentGid));
      formData.append("file", state.pendingAttachment.file);
      const response = await fetch(endpoint, {
        method: "POST",
        body: formData
      });
      if (!response.ok) {
        await handleSendError(response,
          kind === "image" ? "图片发送失败，请稍后重试。" : "视频发送失败，请稍后重试。");
        return;
      }
      clearPendingAttachment();
      setComposerHint("");
      state.followingLatest = true;
      await refreshMessages();
    } catch {
      setComposerHint(kind === "image" ? "图片发送失败，请稍后重试。" : "视频发送失败，请稍后重试。",
        "error");
    } finally {
      state.sending = false;
      setSendingState(false);
    }
  }

  async function sendMessage() {
    if (state.sending || !state.currentGid) return;
    if (state.pendingAttachment) {
      await sendAttachment();
      return;
    }
    const content = elements.composer.value.trim();
    if (!content) return;
    state.sending = true;
    setSendingState(true);
    setComposerHint("发送中…", "sending");
    try {
      const response = await fetch("/chat/messages/send", {
        method: "POST",
        headers: {"Content-Type": "application/x-www-form-urlencoded"},
        body: new URLSearchParams({gid: String(state.currentGid), content})
      });
      if (!response.ok) {
        await handleSendError(response, "消息发送失败，请稍后重试。");
        return;
      }
      elements.composer.value = "";
      autoGrowComposer();
      setComposerHint("");
      state.followingLatest = true;
      await refreshMessages();
    } catch {
      setComposerHint("消息发送失败，请稍后重试。", "error");
    } finally {
      state.sending = false;
      setSendingState(false);
    }
  }

  function setSendingState(sending) {
    elements.composer.disabled = sending;
    elements.attachOpen.disabled = sending || !state.currentGid;
    updateSendButton();
    if (!sending) autoGrowComposer();
  }

  /* ---------- 图片查看器 ---------- */

  let suppressViewerPop = false;

  function openImage(url) {
    elements.imageViewerImage.hidden = true;
    elements.imageViewerState.textContent = "正在加载原图…";
    elements.imageViewerImage.src = url;
    elements.imageViewer.showModal();
    // 物理返回键优先关闭查看器而不是退出会话
    history.pushState({viewer: true, view: "chat"}, "");
  }

  /* ---------- 视图切换 ---------- */

  function showGroupsView() {
    elements.app.dataset.view = "groups";
  }

  function showChatView() {
    elements.app.dataset.view = "chat";
  }

  /* ---------- 初始化 ---------- */

  async function initialize() {
    elements.retryGroups.hidden = true;
    elements.groupsState.textContent = "";
    try {
      state.groups = await fetchJson("/chat/groups", {cache: "no-store"});
      renderGroups();
      if (!state.groups.length) {
        elements.groupsState.textContent = "暂无群聊数据";
      }
    } catch (error) {
      if (error.status === 401) {
        showLoginBanner();
        elements.groupsState.textContent = "登录已失效";
        return;
      }
      elements.groupsState.textContent = "群聊列表加载失败";
      elements.retryGroups.hidden = false;
    }
  }

  /* ---------- 事件绑定 ---------- */

  elements.groupSearch.addEventListener("input", event => {
    filterGroups(event.target.value);
  });
  elements.messages.addEventListener("scroll", () => {
    state.followingLatest = isNearBottom();
    if (state.followingLatest) elements.newMessages.hidden = true;
    maybeLoadEarlierMessages();
  });
  elements.retryGroups.addEventListener("click", initialize);
  elements.loginBanner.addEventListener("click", openLoginSheet);
  elements.loginQr.addEventListener("click", startQrLogin);
  elements.backButton.addEventListener("click", () => history.back());
  elements.newMessages.addEventListener("click", async () => {
    await refreshMessages();
    state.followingLatest = true;
    elements.messages.scrollTop = elements.messages.scrollHeight;
    elements.newMessages.hidden = true;
  });
  elements.composer.addEventListener("keydown", event => {
    // 手机软键盘 Enter 换行；带实体键盘时 Ctrl/Cmd+Enter 发送
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      sendMessage();
    }
  });
  elements.composer.addEventListener("input", () => {
    autoGrowComposer();
    if (elements.composerHint.classList.contains("is-error")) {
      setComposerHint("");
    }
    updateSendButton();
  });
  elements.sendButton.addEventListener("click", sendMessage);
  elements.attachOpen.addEventListener("click", () => elements.attachInput.click());
  elements.attachInput.addEventListener("change", () => {
    if (elements.attachInput.files?.[0]) {
      setPendingAttachment(elements.attachInput.files[0]);
    }
  });
  elements.attachmentRemove.addEventListener("click", clearPendingAttachment);
  elements.desktopLink.addEventListener("click", () => {
    try {
      localStorage.setItem(DESKTOP_PREFERENCE_KEY, "1");
    } catch (_) {
      // 忽略隐私模式下的存储异常
    }
  });

  elements.imageViewer.addEventListener("click", event => {
    if (event.target === elements.imageViewer) elements.imageViewer.close();
  });
  elements.imageViewerImage.addEventListener("load", () => {
    elements.imageViewerImage.hidden = false;
    elements.imageViewerState.textContent = "";
  });
  elements.imageViewerImage.addEventListener("error", () => {
    elements.imageViewerImage.hidden = true;
    elements.imageViewerState.textContent = "原图加载失败，请关闭后重试。";
  });
  elements.imageViewer.addEventListener("close", () => {
    // 非返回键触发的人工关闭，回退掉 openImage 压入的 history 状态
    if (history.state?.viewer && !suppressViewerPop) {
      history.back();
    }
  });

  window.addEventListener("popstate", () => {
    if (elements.imageViewer.open) {
      // 返回键弹出的是查看器状态，停留在会话视图
      suppressViewerPop = true;
      elements.imageViewer.close();
      suppressViewerPop = false;
      showChatView();
      return;
    }
    // 依据弹出的历史状态决定视图：人工关闭查看器触发的 back 仍停留在会话
    if (history.state?.view === "chat") showChatView();
    else showGroupsView();
  });

  // 软键盘弹起时以 visualViewport 高度约束应用高度，避免 composer 被键盘遮挡
  if (window.visualViewport) {
    const setAppHeight = () => {
      document.documentElement.style.setProperty("--app-height", `${window.visualViewport.height}px`);
      if (state.followingLatest && !document.hidden) {
        scrollToBottom();
      }
    };
    window.visualViewport.addEventListener("resize", setAppHeight);
    setAppHeight();
  }

  window.addEventListener("focus", refreshView);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      // 离开页面，挂起跟随最新，回来后由滚动或点击新消息恢复
      state.followingLatest = false;
      return;
    }
    refreshView();
  });
  setInterval(refreshView, 3_000);

  // 监听消息子树变化跟随到底部；图片异步撑开由 messageMedia 的 load 回调处理
  let scrollScheduled = false;
  new MutationObserver(() => {
    if (scrollScheduled) return;
    scrollScheduled = true;
    requestAnimationFrame(() => {
      scrollScheduled = false;
      scrollToBottom();
    });
  }).observe(elements.messages, {childList: true, subtree: true});

  history.replaceState({view: "groups"}, "");
  initialize();
  checkLoginStatus();
})();
