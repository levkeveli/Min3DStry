(() => {
  const feed = "https://raw.githubusercontent.com/levkeveli/Min3DStry/runner-status/runners.json";
  const summary = document.getElementById("runner-status-summary");
  const list = document.getElementById("runner-status-list");
  let snapshot;

  function render() {
    if (!snapshot) {
      summary.textContent = "Runner status unavailable.";
      return;
    }

    const age = Math.max(0, Math.floor((Date.now() - Date.parse(snapshot.updated_at)) / 1000));
    const stale = age >= 7 * 60;
    const count = state => snapshot.runners.filter(runner => runner.state === state).length;
    summary.textContent = `${stale ? "Stale · " : ""}${count("busy")} busy · ${count("idle")} idle · ${count("offline")} offline · updated ${Math.floor(age / 60)} min ago`;
    list.replaceChildren(...snapshot.runners.map(runner => {
      const row = document.createElement("li");
      const title = document.createElement("strong");
      title.textContent = runner.name;
      row.append(title, ` · ${runner.state}${runner.priority ? " · priority" : ""}`);
      if (runner.job) {
        const job = document.createElement("div");
        const link = document.createElement("a");
        const { name, workflow, ref, url } = runner.job;
        link.textContent = name;
        if (/^https:\/\/github\.com\/levkeveli\/Min3DStry\/actions\/runs\/\d+\/job\/\d+$/i.test(url)) {
          link.href = url;
        }
        job.append(link, ` · ${workflow}${ref ? ` · ${ref}` : ""}`);
        row.append(job);
      } else if (runner.state === "busy") {
        row.append(document.createElement("br"), "Job details unavailable");
      }
      return row;
    }));
  }

  async function refresh() {
    try {
      const response = await fetch(`${feed}?minute=${Math.floor(Date.now() / 60000)}`, { cache: "no-cache" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const result = await response.json();
      if (!Number.isFinite(Date.parse(result.updated_at)) || !Array.isArray(result.runners)) {
        throw new Error("Invalid runner status snapshot");
      }
      snapshot = result;
    } catch {
      // Preserve the last snapshot and let its age show when the feed is stale.
    }
    render();
    setTimeout(refresh, 60000);
  }

  refresh();
})();
