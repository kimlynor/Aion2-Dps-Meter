const createHistoryUI = ({ panel, closeBtn, listEl, dpsFormatter }) => {
  const formatMs = (ms) => {
    const total = Math.floor(Number(ms) / 1000);
    if (!Number.isFinite(total) || total < 0) return "-";
    const m = Math.floor(total / 60);
    const s = total % 60;
    return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  };

  const formatNum = (v) => {
    const n = Number(v);
    return Number.isFinite(n) ? dpsFormatter.format(Math.trunc(n)) : "-";
  };

  const open = () => {
    listEl.innerHTML = "";

    const count = window.dpsData?.getHistoryCount?.();
    const numCount = Number(count);

    if (!Number.isFinite(numCount) || numCount === 0) {
      const empty = document.createElement("div");
      empty.className = "historyEmpty";
      empty.textContent = "저장된 이전 전투 기록이 없습니다.";
      listEl.appendChild(empty);
      panel.classList.add("open");
      return;
    }

    // 최신 기록이 위에 오도록 역순 렌더
    for (let i = numCount - 1; i >= 0; i--) {
      const rawJson = window.dpsData?.getHistoryData?.(i);
      if (typeof rawJson !== "string") continue;
      let data;
      try {
        data = JSON.parse(rawJson);
      } catch {
        continue;
      }

      const mapObj = data?.map && typeof data.map === "object" ? data.map : {};
      const battleTimeMs = Number(data?.battleTime) || 0;
      const targetName = typeof data?.targetName === "string" && data.targetName ? data.targetName : "알 수 없는 대상";

      // 참가자 행 목록 (dps 내림차순)
      const participants = Object.entries(mapObj)
        .map(([, v]) => ({
          name: (v && v.nickname) ? v.nickname : "?",
          job: (v && v.job) ? v.job : "",
          dps: Number(v?.dps) || 0,
          contrib: Number(v?.damageContribution) || 0,
        }))
        .sort((a, b) => b.dps - a.dps);

      const card = document.createElement("div");
      card.className = "historyCard";

      const cardHeader = document.createElement("div");
      cardHeader.className = "historyCardHeader";

      const cardTitle = document.createElement("span");
      cardTitle.className = "historyCardTarget";
      cardTitle.textContent = `${numCount - i}번째 이전 전투 • ${targetName}`;

      const cardTime = document.createElement("span");
      cardTime.className = "historyCardTime";
      cardTime.textContent = formatMs(battleTimeMs);

      cardHeader.appendChild(cardTitle);
      cardHeader.appendChild(cardTime);
      card.appendChild(cardHeader);

      for (const p of participants) {
        const row = document.createElement("div");
        row.className = "historyRow";

        const nameEl = document.createElement("span");
        nameEl.className = "historyRowName";
        nameEl.textContent = p.name;

        const dpsEl = document.createElement("span");
        dpsEl.className = "historyRowDps";
        dpsEl.textContent = `${formatNum(p.dps)}/초`;

        const contribEl = document.createElement("span");
        contribEl.className = "historyRowContrib";
        contribEl.textContent = `${p.contrib.toFixed(1)}%`;

        row.appendChild(nameEl);
        row.appendChild(dpsEl);
        row.appendChild(contribEl);
        card.appendChild(row);
      }

      listEl.appendChild(card);
    }

    panel.classList.add("open");
  };

  const close = () => {
    panel.classList.remove("open");
  };

  closeBtn?.addEventListener("click", close);

  return { open, close };
};
