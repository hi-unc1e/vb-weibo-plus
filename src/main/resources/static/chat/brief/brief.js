(() => {
  "use strict";
  const params = new URLSearchParams(location.search);
  const group = document.querySelector("#group");
  const date = document.querySelector("#date");
  const generate = document.querySelector("#generate");
  const status = document.querySelector("#status");
  const brief = document.querySelector("#brief");
  const source = document.querySelector("#source");
  let current = null;

  function day(timestamp) {
    const parts = new Intl.DateTimeFormat("en", {timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit"})
      .formatToParts(timestamp);
    const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
    return `${values.year}-${values.month}-${values.day}`;
  }

  function formatTime(timestamp) {
    return new Intl.DateTimeFormat("zh-CN", {timeZone: "Asia/Shanghai", dateStyle: "short", timeStyle: "short"}).format(timestamp);
  }

  async function request(url, options) {
    const response = await fetch(url, {cache: "no-store", ...options});
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.msg || `请求失败（${response.status}）`);
    }
    return response.status === 204 ? null : response.json();
  }

  function query() {
    return new URLSearchParams({gid: group.value, date: date.value});
  }

  function updateUrl(mid) {
    const next = query();
    if (mid) next.set("mid", String(mid));
    history.replaceState(null, "", `?${next}`);
  }

  function messageNode(message, targetMid) {
    const node = document.createElement("article");
    node.className = `message${message.mid === targetMid ? " target" : ""}`;
    const meta = document.createElement("small");
    meta.textContent = `${message.senderName || "未知发送者"} · ${formatTime(message.createdAt)}`;
    const content = document.createElement("p");
    content.textContent = message.text || "[非文字消息]";
    node.append(meta, content);
    return node;
  }

  async function showSource(item) {
    updateUrl(item.mid);
    source.hidden = false;
    const context = document.querySelector("#context");
    context.replaceChildren(messageNode(item, item.mid));
    source.scrollIntoView({behavior: "smooth", block: "start"});
    try {
      const base = new URLSearchParams({gid: group.value, size: "5"});
      const before = new URLSearchParams(base);
      before.set("beforeCreatedAt", item.createdAt);
      before.set("beforeMid", item.mid);
      const after = new URLSearchParams(base);
      after.set("afterCreatedAt", item.createdAt);
      after.set("afterMid", item.mid);
      const [older, newer] = await Promise.all([
        request(`/chat/messages/cursor?${before}`),
        request(`/chat/messages/cursor?${after}`)
      ]);
      context.replaceChildren(...older.items.reverse().map(message => messageNode(message, item.mid)),
        messageNode(item, item.mid), ...newer.items.map(message => messageNode(message, item.mid)));
    } catch {
      status.textContent = "附近消息暂时无法加载，原消息仍可查看。";
    }
  }

  function render(result) {
    current = result;
    brief.hidden = !result;
    source.hidden = true;
    if (!result) {
      status.textContent = "这一天还没有简报。可以点击生成。";
      return;
    }
    status.textContent = "";
    document.querySelector("#brief-date").textContent = result.date;
    document.querySelector("#coverage").textContent = result.messageCount > result.analyzedCount
      ? `分析了最近 ${result.analyzedCount} / ${result.messageCount} 条消息`
      : `分析了 ${result.messageCount} 条消息`;
    document.querySelector("#summary").textContent = result.summary;
    const list = document.querySelector("#items");
    list.replaceChildren();
    result.items.forEach(item => {
      const row = document.createElement("li");
      const title = document.createElement("strong");
      title.textContent = item.summary;
      const meta = document.createElement("small");
      meta.textContent = `${item.senderName || "未知发送者"} · ${formatTime(item.createdAt)}`;
      const link = document.createElement("a");
      link.href = `?${new URLSearchParams({gid: group.value, date: date.value, mid: String(item.mid)})}`;
      link.textContent = "查看原消息与上下文 →";
      link.addEventListener("click", event => { event.preventDefault(); showSource(item); });
      row.append(title, meta, link);
      list.append(row);
    });
    const mid = Number(params.get("mid"));
    const selected = result.items.find(item => item.mid === mid);
    if (selected) showSource(selected);
  }

  async function load() {
    if (!group.value || !date.value) return;
    updateUrl();
    status.textContent = "正在加载简报…";
    try {
      render(await request(`/chat/daily-brief?${query()}`));
    } catch (error) {
      brief.hidden = true;
      status.textContent = error.message;
    }
  }

  async function init() {
    try {
      const groups = await request("/chat/groups");
      if (!groups.length) { status.textContent = "暂无群聊，请先同步。"; return; }
      groups.forEach(item => {
        const option = document.createElement("option");
        option.value = item.gid;
        option.textContent = item.name || `群聊 ${item.gid}`;
        group.append(option);
      });
      group.value = groups.some(item => String(item.gid) === params.get("gid"))
        ? params.get("gid") : String(groups[0].gid);
      await selectGroup();
    } catch (error) {
      status.textContent = error.message;
    }
  }

  async function selectGroup() {
    if (!params.get("date") || params.get("gid") !== group.value) {
      try {
        const latest = await request(`/chat/messages?${new URLSearchParams({gid: group.value, page: "1", size: "1"})}`);
        date.value = latest.items.length ? day(latest.items[0].createdAt) : day(Date.now() - 86400000);
      } catch {
        date.value = day(Date.now() - 86400000);
      }
    } else date.value = params.get("date");
    date.max = day(Date.now());
    await load();
  }

  group.addEventListener("change", selectGroup);
  date.addEventListener("change", load);
  generate.addEventListener("click", async () => {
    generate.disabled = true;
    status.textContent = "正在生成，可能需要一两分钟…";
    try {
      render(await request(`/chat/daily-brief?${query()}`, {method: "POST"}));
    } catch (error) {
      status.textContent = error.message;
    } finally {
      generate.disabled = false;
    }
  });
  document.querySelector("#close-source").addEventListener("click", () => {
    source.hidden = true;
    updateUrl();
  });
  init();
})();
