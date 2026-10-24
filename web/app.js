// LayanTv setup page: pick a TV or tablet model, get tailored steps and one
// command that runs the Android TV Debloit setup from a computer.

const RAW = "https://raw.githubusercontent.com/Dynoz769/AndroidTvDebloit/main/web";

const STEPS = {
  googletv: [
    "Buka <b>Settings</b> (ikon gear) → <b>System</b> → <b>About</b>.",
    "Tatal ke bawah dan tekan <b>Android TV OS build</b> sebanyak <b>7 kali</b> sehingga keluar mesej <i>\"You are now a developer\"</i>.",
    "Kembali ke <b>System</b> → <b>Developer options</b>.",
    "Hidupkan <b>USB debugging</b>.",
  ],
  androidtv: [
    "Buka <b>Settings</b> → <b>Device Preferences</b> → <b>About</b>.",
    "Tekan <b>Build</b> sebanyak <b>7 kali</b> sehingga keluar mesej <i>\"You are now a developer\"</i>.",
    "Kembali ke <b>Device Preferences</b> → <b>Developer options</b>.",
    "Hidupkan <b>USB debugging</b>. Ada TV yang namakannya <b>Network debugging</b>.",
  ],
  tablet: [
    "Buka <b>Settings</b> → <b>About tablet</b>.",
    "Tekan <b>Build number</b> sebanyak <b>7 kali</b> sehingga keluar mesej <i>\"You are now a developer\"</i>. Kadang-kadang ia di bawah <b>Software information</b>.",
    "Kembali ke <b>Settings</b> → <b>System</b> → <b>Developer options</b>.",
    "Hidupkan <b>USB debugging</b>.",
  ],
  samsung: [
    "Buka <b>Settings</b> → <b>About tablet</b> → <b>Software information</b>.",
    "Tekan <b>Build number</b> sebanyak <b>7 kali</b> dan masukkan PIN jika diminta.",
    "Kembali ke <b>Settings</b> → <b>Developer options</b> (paling bawah).",
    "Hidupkan <b>USB debugging</b>.",
  ],
  xiaomi: [
    "Buka <b>Settings</b> → <b>About tablet</b>.",
    "Tekan <b>OS version</b> (atau <b>MIUI version</b>) sebanyak <b>7 kali</b>.",
    "Kembali ke <b>Settings</b> → <b>Additional settings</b> → <b>Developer options</b>.",
    "Hidupkan <b>USB debugging</b> dan <b>Install via USB</b>. Xiaomi mungkin minta log masuk akaun Mi.",
  ],
};

const CONNECT = {
  tv: {
    title: "Cari alamat IP TV",
    steps: [
      "Buka <b>Settings → Network &amp; Internet</b>.",
      "Pilih Wi-Fi (atau Ethernet) yang sedang disambung.",
      "Catat <b>IP address</b>, contoh <code>192.168.1.20</code>.",
    ],
    ip: "IP TV anda",
  },
  tablet: {
    title: "Sambung tablet ke komputer",
    steps: [
      "Sambung tablet ke komputer dengan <b>kabel USB</b>. Ini cara paling mudah.",
      "Bila tablet tanya <b>\"Allow USB debugging?\"</b>, tick <b>Always allow</b> dan tekan <b>Allow</b>.",
      "Tiada kabel? Hidupkan <b>Wireless debugging</b> dalam Developer options dan masukkan IP yang tertera di bawah.",
    ],
    ip: "IP tablet (tanpa kabel sahaja)",
  },
};

// profile: picks senarai/jenama-<profile>.txt in the scripts (if it exists).
const BRANDS = [
  {
    id: "sony", name: "Sony", models: [
      { id: "sony-bravia-googletv", name: "BRAVIA Google TV", years: "2021 ke atas", type: "googletv", profile: "sony" },
      {
        id: "sony-bravia-androidtv", name: "BRAVIA Android TV", years: "2015 – 2020", type: "androidtv", profile: "sony",
        note: "Pada model lama (Android 7), menu <b>About</b> terus di bawah <b>Settings</b>, tanpa Device Preferences.",
      },
    ],
  },
  {
    id: "tcl", name: "TCL", models: [
      { id: "tcl-googletv", name: "TCL Google TV", years: "2022 ke atas", type: "googletv", profile: "tcl" },
      { id: "tcl-androidtv", name: "TCL Android TV", years: "2018 – 2021", type: "androidtv", profile: "tcl" },
    ],
  },
  {
    id: "xiaomi", name: "Xiaomi", models: [
      { id: "xiaomi-tv-googletv", name: "Xiaomi TV (Google TV)", years: "Xiaomi TV A, A Pro, Q2 dan lain-lain", type: "googletv", profile: "xiaomi" },
      {
        id: "xiaomi-mitv-patchwall", name: "Mi TV (PatchWall)", years: "Mi TV 4, 4S, P1, Q1", type: "androidtv", profile: "xiaomi",
        note: "Buka Settings dari ikon gear di bahagian atas home PatchWall.",
      },
      { id: "xiaomi-tvbox-googletv", name: "Xiaomi TV Box S (Gen 2/3) / TV Stick 4K", years: "Google TV", type: "googletv", profile: "xiaomi" },
      { id: "xiaomi-mibox", name: "Mi Box S / Mi TV Stick", years: "Android TV", type: "androidtv", profile: "xiaomi" },
    ],
  },
  {
    id: "philips", name: "Philips", models: [
      { id: "philips-googletv", name: "Philips Google TV", years: "2023 ke atas", type: "googletv", profile: "philips" },
      {
        id: "philips-androidtv", name: "Philips Android TV", years: "2016 – 2022", type: "androidtv", profile: "philips",
        note: "Pada Philips, tetapan Android biasanya di <b>Settings → All settings → Android settings</b>. Nama menu mungkin sedikit berbeza.",
      },
    ],
  },
  {
    id: "sharp", name: "Sharp", models: [
      { id: "sharp-googletv", name: "Sharp Google TV", years: "2022 ke atas", type: "googletv", profile: "sharp" },
      { id: "sharp-androidtv", name: "Sharp Android TV", years: "Sebelum 2022", type: "androidtv", profile: "sharp" },
    ],
  },
  {
    id: "google", name: "Google", models: [
      { id: "chromecast-googletv", name: "Chromecast with Google TV", years: "4K & HD", type: "googletv", profile: "" },
      {
        id: "google-tv-streamer", name: "Google TV Streamer", years: "2024 ke atas", type: "googletv", profile: "",
        note: "Jika tiada <b>USB debugging</b> tetapi ada <b>Wireless debugging</b>, hidupkan Wireless debugging. Skrip akan minta kod pairing 6 digit yang tertera di TV.",
      },
    ],
  },
  {
    id: "nvidia", name: "NVIDIA", models: [
      { id: "nvidia-shield", name: "SHIELD TV / SHIELD TV Pro", years: "Android TV", type: "androidtv", profile: "" },
    ],
  },
  {
    id: "other", name: "Lain-lain", models: [
      { id: "other-googletv", name: "Google TV lain", years: "Hisense, Toshiba, Skyworth, Haier, Acer dan lain-lain", type: "googletv", profile: "" },
      { id: "other-androidtv", name: "Android TV lain", years: "Mana-mana TV atau TV box Android", type: "androidtv", profile: "" },
    ],
  },
  {
    id: "unsupported", name: "Bukan Android", models: [
      { id: "samsung", name: "Samsung", years: "Tizen", type: "unsupported" },
      { id: "lg", name: "LG", years: "webOS", type: "unsupported" },
      { id: "vidaa", name: "Hisense / Toshiba VIDAA", years: "VIDAA", type: "unsupported" },
      { id: "apple-tv", name: "Apple TV", years: "tvOS", type: "unsupported" },
      {
        id: "fire-tv", name: "Amazon Fire TV", years: "Fire OS", type: "unsupported",
        why: "Fire TV berasaskan Android, tetapi home screen dan app sistemnya berbeza. LayanTv belum menyokong Fire TV.",
      },
    ],
  },
];

// Tablet profiles pick senarai/tablet-jenama-<profile>.txt; the scripts tell
// tablets from TVs on their own.
const TABLET_BRANDS = [
  {
    id: "tab-samsung", name: "Samsung", models: [
      { id: "tab-samsung-galaxy", name: "Galaxy Tab", years: "Tab A, Tab A7, A8, A9, S6 Lite, S7, S8, S9 dan lain-lain", type: "tablet", profile: "samsung", steps: "samsung" },
    ],
  },
  {
    id: "tab-xiaomi", name: "Xiaomi", models: [
      { id: "tab-xiaomi-pad", name: "Xiaomi Pad / Redmi Pad / POCO Pad", years: "MIUI & HyperOS", type: "tablet", profile: "xiaomi", steps: "xiaomi" },
    ],
  },
  {
    id: "tab-lenovo", name: "Lenovo", models: [
      { id: "tab-lenovo", name: "Lenovo Tab", years: "Tab M8, M10, M11, P11, P12 dan lain-lain", type: "tablet", profile: "lenovo" },
    ],
  },
  {
    id: "tab-oppo", name: "OPPO / realme / OnePlus", models: [
      { id: "tab-oppo-pad", name: "OPPO Pad", years: "ColorOS", type: "tablet", profile: "oppo" },
      { id: "tab-realme-pad", name: "realme Pad", years: "realme UI", type: "tablet", profile: "realme" },
      { id: "tab-oneplus-pad", name: "OnePlus Pad", years: "OxygenOS", type: "tablet", profile: "oppo" },
    ],
  },
  {
    id: "tab-other", name: "Lain-lain", models: [
      { id: "tab-huawei", name: "Huawei / Honor", years: "MatePad, Honor Pad (Android)", type: "tablet", profile: "" },
      { id: "tab-other", name: "Tablet Android lain", years: "Teclast, Alldocube, Blackview, Nokia, Doogee dan lain-lain", type: "tablet", profile: "" },
    ],
  },
  {
    id: "tab-unsupported", name: "Bukan Android", models: [
      { id: "ipad", name: "iPad", years: "iPadOS", type: "unsupported" },
      {
        id: "fire-tablet", name: "Amazon Fire tablet", years: "Fire OS", type: "unsupported",
        why: "Fire tablet berasaskan Android, tetapi app sistemnya berbeza. LayanTv belum menyokong Fire tablet.",
      },
    ],
  },
];

const TYPE_LABEL = { googletv: "Google TV", androidtv: "Android TV", tablet: "Tablet", unsupported: "Tak disokong" };

const withBrand = (kind) => (brand) => brand.models.map((m) => ({ ...m, kind, brand: brand.name, brandId: brand.id }));
const models = [...BRANDS.flatMap(withBrand("tv")), ...TABLET_BRANDS.flatMap(withBrand("tablet"))];

const $ = (id) => document.getElementById(id);
const state = { kind: "tv", brand: "all", query: "", model: null, os: detectOs(), ip: "" };

function detectOs() {
  const ua = navigator.userAgent;
  if (/Windows/i.test(ua)) return "windows";
  if (/Macintosh|Mac OS X/i.test(ua) && !/iPhone|iPad/i.test(ua)) return "mac";
  if (/Linux|X11/i.test(ua) && !/Android/i.test(ua)) return "linux";
  return "windows";
}

const isPhone = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);

// ---------------------------------------------------------------- picker

function renderKinds() {
  for (const button of document.querySelectorAll(".kind")) {
    button.setAttribute("aria-pressed", String(button.dataset.kind === state.kind));
  }
  $("search").placeholder = state.kind === "tv"
    ? "Cari jenama atau model, contoh: Sony, TCL, Mi Box"
    : "Cari jenama atau model, contoh: Galaxy Tab, Redmi Pad, Lenovo";
}

function renderChips() {
  const chips = [{ id: "all", name: "Semua" }, ...(state.kind === "tv" ? BRANDS : TABLET_BRANDS)];
  $("brand-chips").replaceChildren(...chips.map((b) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip";
    chip.textContent = b.name;
    chip.setAttribute("aria-pressed", String(state.brand === b.id));
    chip.addEventListener("click", () => {
      state.brand = b.id;
      renderChips();
      renderModels();
    });
    return chip;
  }));
}

function matches(m) {
  if (m.kind !== state.kind) return false;
  if (state.brand !== "all" && m.brandId !== state.brand) return false;
  const q = state.query.trim().toLowerCase();
  // Non-Android TVs only show up when asked for, to keep the list short.
  if (!q) return state.brand !== "all" || m.type !== "unsupported";
  return `${m.brand} ${m.name} ${m.years} ${TYPE_LABEL[m.type]}`.toLowerCase().includes(q);
}

function renderModels() {
  const list = models.filter(matches);
  $("no-results").hidden = list.length > 0;
  $("models").replaceChildren(...list.map((m) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "model";
    card.setAttribute("aria-pressed", String(state.model?.id === m.id));
    card.innerHTML = `
      <span class="model-brand">${m.brand}</span>
      <span class="model-name">${m.name}</span>
      <span class="model-meta"><span class="badge ${m.type}">${TYPE_LABEL[m.type]}</span>${m.years}</span>`;
    card.addEventListener("click", () => selectModel(m, true));
    return card;
  }));
}

function selectModel(m, scroll) {
  state.model = m;
  if (state.kind !== m.kind) {
    state.kind = m.kind;
    state.brand = "all";
    renderKinds();
    renderChips();
  }
  history.replaceState(null, "", `#tv=${m.id}`);
  renderModels();
  renderSteps();
  renderRun();
  if (scroll) $("langkah-2").scrollIntoView({ behavior: "smooth", block: "start" });
}

// ---------------------------------------------------------------- steps

function fullName(m) {
  const named = m.name.toLowerCase().startsWith(m.brand.toLowerCase());
  const generic = ["other", "unsupported", "tab-other", "tab-unsupported"].includes(m.brandId);
  return named || generic ? m.name : `${m.brand} ${m.name}`;
}

function renderSteps() {
  const m = state.model;
  const supported = m && m.type !== "unsupported";
  $("langkah-2").classList.toggle("is-locked", !m);
  $("langkah-3").classList.toggle("is-locked", !supported);
  if (!m) return;

  const device = m.kind === "tablet" ? "Tablet" : "TV";
  $("selected-tv").innerHTML = `${device} anda: <b>${fullName(m)}</b> · ${TYPE_LABEL[m.type]}`;
  $("unsupported").hidden = supported;
  $("tv-steps").hidden = !supported;

  if (!supported) {
    const why = m.why || `${m.name} guna ${m.years}, bukan Android. LayanTv hanya berfungsi pada Google TV, Android TV dan tablet Android.`;
    $("unsupported").innerHTML = `<h3>Maaf, ${device.toLowerCase()} ini tak disokong</h3><p>${why}</p>`;
    return;
  }

  $("dev-steps").innerHTML = STEPS[m.steps || m.type].map((s) => `<li>${s}</li>`).join("");
  const connect = CONNECT[m.kind];
  $("connect-title").textContent = connect.title;
  $("connect-steps").innerHTML = connect.steps.map((s) => `<li>${s}</li>`).join("");
  $("ip-label").textContent = connect.ip;
  $("rom-card").hidden = m.kind !== "tablet";
  $("run-intro").textContent = m.kind === "tablet"
    ? "Guna komputer, dengan tablet disambung melalui kabel USB (atau Wi-Fi yang sama)."
    : "Guna komputer yang disambung ke Wi-Fi yang sama dengan TV.";
  $("dev-note").hidden = !m.note;
  $("dev-note").innerHTML = m.note || "";
}

// ---------------------------------------------------------------- command

const IP_RE = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}(:\d{1,5})?$/;

function validIp() {
  const ip = state.ip.trim();
  return ip && IP_RE.test(ip) ? ip : "";
}

function windowsCommand() {
  const m = state.model;
  let cmd = `[Net.ServicePointManager]::SecurityProtocol='Tls12'; & ([scriptblock]::Create((irm ${RAW}/setup.ps1)))`;
  if (m?.profile) cmd += ` -Jenama ${m.profile}`;
  if (validIp()) cmd += ` -Ip ${validIp()}`;
  return cmd;
}

function unixCommand() {
  const m = state.model;
  let cmd = `curl -fsSL ${RAW}/setup.sh | bash -s --`;
  if (m?.profile) cmd += ` --jenama ${m.profile}`;
  if (validIp()) cmd += ` --ip ${validIp()}`;
  return cmd;
}

function renderRun() {
  for (const os of ["windows", "mac", "linux"]) {
    $(`tab-${os}`).setAttribute("aria-selected", String(state.os === os));
  }
  const windows = state.os === "windows";
  $("run-windows").hidden = !windows;
  $("run-unix").hidden = windows;
  $("unix-how").innerHTML = state.os === "mac"
    ? "Tekan <b>Cmd + Space</b>, taip <b>Terminal</b>, tekan Enter. Tampal dengan <b>Cmd + V</b>, kemudian tekan Enter."
    : "Buka Terminal, tampal arahan ini, kemudian tekan Enter.";
  $("command").textContent = windows ? windowsCommand() : unixCommand();
}

function downloadBat() {
  const name = state.model ? `setup-layantv-${state.model.id}.bat` : "setup-layantv.bat";
  const bat = [
    "@echo off",
    "title LayanTv Setup",
    `powershell -NoProfile -ExecutionPolicy Bypass -Command "${windowsCommand()}"`,
    "pause",
    "",
  ].join("\r\n");
  const url = URL.createObjectURL(new Blob([bat], { type: "application/octet-stream" }));
  const a = Object.assign(document.createElement("a"), { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ---------------------------------------------------------------- utils

let toastTimer;
function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 1800);
}

async function copy(text, done) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = Object.assign(document.createElement("textarea"), { value: text });
    document.body.append(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
  toast(done);
}

// ---------------------------------------------------------------- start

function init() {
  for (const button of document.querySelectorAll(".kind")) {
    button.addEventListener("click", () => {
      state.kind = button.dataset.kind;
      state.brand = "all";
      renderKinds();
      renderChips();
      renderModels();
    });
  }
  renderKinds();
  renderChips();
  renderModels();

  $("search").addEventListener("input", (e) => {
    state.query = e.target.value;
    renderModels();
  });

  $("ip").addEventListener("input", (e) => {
    state.ip = e.target.value.replace(/\s/g, "");
    const bad = state.ip !== "" && !validIp();
    $("ip-error").hidden = !bad;
    e.target.setAttribute("aria-invalid", String(bad));
    renderRun();
  });

  for (const tab of document.querySelectorAll(".tab")) {
    tab.addEventListener("click", () => {
      state.os = tab.dataset.os;
      renderRun();
    });
  }

  $("copy-command").addEventListener("click", () => copy($("command").textContent, "Arahan disalin"));
  $("download-bat").addEventListener("click", downloadBat);
  $("copy-link").addEventListener("click", () => copy(location.href, "Pautan disalin"));
  $("phone-note").hidden = !isPhone;

  // A shared link like #tv=tcl-googletv opens with that TV already chosen.
  const id = new URLSearchParams(location.hash.slice(1)).get("tv");
  const fromLink = models.find((m) => m.id === id);
  if (fromLink) selectModel(fromLink, false);
  else renderRun();
}

init();
