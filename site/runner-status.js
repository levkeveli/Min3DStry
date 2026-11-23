(() => {
  const feed = "https://api.github.com/repos/levkeveli/Min3DStry/issues/424";
  const summary = document.getElementById("runner-status-summary");
  const list = document.getElementById("runner-status-list");
  let snapshot;

  function render() {
    if (!snapshot) {
      summary.textContent = "Runner status unavailable.";
      return;
    }

    const age = Math.max(0, Math.floor((Date.now() - Date.parse(snapshot.updated_at)) / 1000));
    const stale = age >= 10 * 60;
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
      const response = await fetch(feed, { signal: AbortSignal.timeout(8000) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const issue = await response.json();
      const result = JSON.parse(issue.body.split("```json\n")[1].split("\n```")[0]);
      if (Number.isFinite(Date.parse(result.updated_at)) && Array.isArray(result.runners)
          && (!snapshot || Date.parse(result.updated_at) >= Date.parse(snapshot.updated_at))) {
        snapshot = result;
      }
    } catch {
      // Keep the last snapshot visible and show its age when the feed is unavailable.
    }
    render();
    setTimeout(refresh, 150000);
  }

  refresh();
})();
