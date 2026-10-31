#!/usr/bin/env node
/**
 * tv.mjs — 电视的 ADB 运维入口（在小米电视 6 OLED / MiTV-MTEQ0，Android 10，armeabi-v7a 上实测）。
 *
 * 思路来自 tv-vibehack（Gathin23）：局域网 ADB 停用广告/统计包 + 换自写极简桌面。
 * 全部用 `pm disable-user --user 0`，不卸载；`restore` 按备份把状态恢复回去。
 *
 * 用法：
 *   node tv.mjs connect                 # 连上电视（首次电视上要点「一律允许」）
 *   node tv.mjs backup                  # 包列表 / 设置 / getprop / 默认桌面 / 输入法 → backup/<时间>/
 *   node tv.mjs disable                 # 按 disable-list.txt 逐个停用（必须一条一条跑，串一行会漏）
 *   node tv.mjs restore [备份目录]       # 重新启用备份时没被停用的包、恢复默认桌面
 *   node tv.mjs enable-home-service     # 启用桌面的无障碍服务，接管主页键（国内版小米不让改默认桌面）
 *   node tv.mjs guard                   # 定时任务 tv-home-guard 每 5 分钟跑：找电视（IP 变了就扫网段）→ 主页键服务丢了就留现场 + 恢复
 *   node tv.mjs shot [名字]              # 截屏到 shots/
 *   node tv.mjs key <键名|键码>...       # 发遥控器按键（HOME/BACK/DPAD_DOWN/ENTER…）
 *
 * 坑（2026-10-18）：
 * - 电视 IP 是 DHCP 分的，会变。连不上先扫局域网；别信端口探测——电脑上的代理软件开了 TUN 模式时会假装接住
 *   不存在的地址（TCP 通、握手立刻断），看起来像「电视没弹授权框」。真正的 adbd 收到 CNXN 会回 AUTH。
 * - Git Bash 里调 adb shell 要 MSYS_NO_PATHCONV=1，不然 /data 之类会被改写成 E:/Git/data。本脚本直接 spawn，不受影响。
 * - `adb exec-out screencap` 会把厂商驱动的日志混进 PNG 开头，截图走「存 /sdcard 再 pull」。
 * - 桌面被强停（`dumpsys package` 里 stopped=true）时，系统会把它的无障碍服务从启用列表里删掉，主页键就回到小米桌面
 *   （2026-10-25 前后发生过一次，疑似电视管家清理后台）。被强停后应用自己什么都收不到，所以只能由本机 guard 从外面补。
 */

import { spawnSync } from "node:child_process";
import { Socket } from "node:net";
import { mkdirSync, writeFileSync, readFileSync, existsSync, appendFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ADB = join(HERE, "tools", "platform-tools", "adb.exe");
// 电视的 IP:端口（电视设置里打开 ADB 调试后看网络信息）。IP 由路由器分配、可能会变，换了就重新设。
let TV = process.env.TV_ADDR || (existsSync(join(HERE, ".tv-addr")) ? readFileSync(join(HERE, ".tv-addr"), "utf8").trim() : "");   // .tv-addr：本地记一下，不入库
if (!TV) { console.error("先设电视地址：TV_ADDR=<电视IP>:5555 node tv.mjs ..."); process.exit(1); }

/** 厂商 shell 每条命令都会先吐两行驱动日志，滤掉 */
const NOISE = /^(Init wrapper sys mutex|open mma dev failed)/;
function adb(...args) {
  const r = spawnSync(ADB, ["-s", TV, ...args], { encoding: "utf8", maxBuffer: 1 << 26 });
  const out = (r.stdout + r.stderr).split(/\r?\n/).filter((l) => l && !NOISE.test(l)).join("\n");
  return { ok: r.status === 0, out };
}
const sh = (cmd) => adb("shell", cmd);
const pkgs = (flag = "") => sh(`pm list packages ${flag}`).out.split("\n")
  .filter((l) => l.startsWith("package:")).map((l) => l.slice(8).trim());
const stamp = () => new Date().toISOString().replace(/[-:]/g, "").replace("T", "-").slice(0, 15);

const [cmd, ...rest] = process.argv.slice(2);

const HOME_SVC = "com.optimisticat.tvhome/com.optimisticat.tvhome.HomeKeyService";
const MODEL = "MiTV-MTEQ0";

function enableHomeService() {
  // 小米不让第三方当默认桌面，主页键靠 launcher 的无障碍服务接管（见 HomeKeyService.java）
  const cur = sh("settings get secure enabled_accessibility_services").out.trim();
  const list = cur && cur !== "null" ? cur.split(":").filter(Boolean) : [];
  if (!list.includes(HOME_SVC)) list.push(HOME_SVC);
  sh(`settings put secure enabled_accessibility_services ${list.join(":")}`);
  sh("settings put secure accessibility_enabled 1");
  // 原厂桌面的窗口对无障碍隐藏，服务靠应用使用记录判断它是否在前台
  sh("appops set com.optimisticat.tvhome GET_USAGE_STATS allow");
  // 录音（按住说话）+ 读日志（遥控器语音键借小爱听写，见 XiaoaiBridge.java）
  sh("pm grant com.optimisticat.tvhome android.permission.RECORD_AUDIO");
  sh("pm grant com.optimisticat.tvhome android.permission.READ_LOGS");
  return sh("settings get secure enabled_accessibility_services").out;
}

/** 连上并确认是这台电视（代理 TUN 会假装接住不存在的地址，所以要读到型号才算） */
function isTv(addr) {
  spawnSync(ADB, ["connect", addr], { encoding: "utf8", timeout: 8000 });
  const r = spawnSync(ADB, ["-s", addr, "shell", "getprop ro.product.model"], { encoding: "utf8", timeout: 8000 });
  return r.status === 0 && r.stdout.includes(MODEL);
}

/** 同网段扫 5555，逐个用 isTv 确认 */
async function scanForTv(prefix) {
  const open = [];
  const probe = (ip) => new Promise((done) => {
    const s = new Socket();
    s.setTimeout(600);
    s.once("connect", () => { open.push(ip); s.destroy(); done(); });
    s.once("timeout", () => { s.destroy(); done(); });
    s.once("error", () => done());
    s.connect(5555, ip);
  });
  const ips = Array.from({ length: 254 }, (_, i) => `${prefix}.${i + 1}`);
  for (let i = 0; i < ips.length; i += 64) await Promise.all(ips.slice(i, i + 64).map(probe));
  return open.map((ip) => `${ip}:5555`).find(isTv) || null;
}

if (cmd === "connect") {
  const r = spawnSync(ADB, ["connect", TV], { encoding: "utf8" });
  console.log((r.stdout + r.stderr).trim());
} else if (cmd === "backup") {
  const dir = join(HERE, "backup", stamp());
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "getprop.txt"), sh("getprop").out);
  writeFileSync(join(dir, "packages-all.txt"), sh("pm list packages -f").out);
  writeFileSync(join(dir, "packages-disabled.txt"), sh("pm list packages -d").out);
  for (const ns of ["system", "secure", "global"]) writeFileSync(join(dir, `settings-${ns}.txt`), sh(`settings list ${ns}`).out);
  writeFileSync(join(dir, "home.txt"), sh("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN").out);
  writeFileSync(join(dir, "ime.txt"), sh("settings get secure default_input_method").out);
  writeFileSync(join(HERE, "backup", "LATEST"), dir);
  console.log(`备份到 ${dir}`);
} else if (cmd === "disable") {
  const list = readFileSync(join(HERE, "disable-list.txt"), "utf8").split(/\r?\n/)
    .map((l) => l.replace(/#.*/, "").trim()).filter(Boolean);
  const installed = new Set(pkgs());
  for (const p of list) {
    if (!installed.has(p)) { console.log(`- ${p}  未安装，跳过`); continue; }
    console.log(sh(`pm disable-user --user 0 ${p}`).out.replace(/^Package /, "✓ "));
  }
} else if (cmd === "restore") {
  const dir = rest[0] || readFileSync(join(HERE, "backup", "LATEST"), "utf8").trim();
  const wasDisabled = new Set(readFileSync(join(dir, "packages-disabled.txt"), "utf8")
    .split(/\r?\n/).filter((l) => l.startsWith("package:")).map((l) => l.slice(8).trim()));
  for (const p of pkgs("-d")) {
    if (wasDisabled.has(p)) continue;
    console.log(sh(`pm enable ${p}`).out.replace(/^Package /, "↺ "));
  }
  const home = readFileSync(join(dir, "home.txt"), "utf8").trim().split(/\r?\n/).pop();
  if (home?.includes("/")) console.log(sh(`cmd package set-home-activity ${home}`).out || `默认桌面 → ${home}`);
  const ime = existsSync(join(dir, "ime.txt")) && readFileSync(join(dir, "ime.txt"), "utf8").trim();
  if (ime) sh(`ime set ${ime}`);
  console.log(`已按 ${dir} 恢复`);
} else if (cmd === "enable-home-service") {
  console.log(enableHomeService());
} else if (cmd === "guard") {
  // 产物 state/guard-last.txt 每次都写（定时任务靠它判断 guard 自己活着）；只有真的修复了才打印【…】给推送
  const stateDir = join(HERE, "state");
  mkdirSync(stateDir, { recursive: true });
  const now = new Date().toLocaleString("zh-CN", { hour12: false });
  const done = (line) => writeFileSync(join(stateDir, "guard-last.txt"), `${now} ${line}\n`);
  if (!isTv(TV)) {
    const found = await scanForTv(TV.split(":")[0].split(".").slice(0, 3).join("."));
    if (!found) { done(`电视不在线（${TV}，网段里也没扫到）`); process.exit(0); }
    writeFileSync(join(HERE, ".tv-addr"), found + "\n");
    console.error(`电视地址 ${TV} → ${found}`);
    TV = found;
  }
  const enabled = sh("settings get secure enabled_accessibility_services").out;
  if (enabled.includes(HOME_SVC)) { done(`正常（${TV}）`); process.exit(0); }
  // 丢了：先留现场（谁强停的），再恢复
  const evidence = [
    `==== ${now} ${TV} 主页键服务不在启用列表`,
    `enabled_accessibility_services=${enabled}`,
    sh("dumpsys package com.optimisticat.tvhome | grep -E 'stopped=|lastUpdateTime'").out,
    "uptime " + sh("cat /proc/uptime").out,
    sh("dumpsys usagestats | grep -E 'package=com.optimisticat.tvhome .*lastTimeUsed' | tail -1").out,
    sh("logcat -d -b all | grep -iE 'force.?stop|am_kill|killBackground|tvmanager|optimisticat' | tail -80").out,
  ].join("\n");
  appendFileSync(join(stateDir, "guard.log"), evidence + "\n\n");
  enableHomeService();
  await new Promise((r) => setTimeout(r, 3000));
  // Bound services 一个服务一行、只有 label（app_name），截到 Enabled services 为止。
  // 刚装完 / 刚被强停过时 label 是截断的包名（「label=com.optimisticat.tvho…」，2026-10-31 实测），两种都算
  const dump = sh("dumpsys accessibility").out;
  const bound = /label=(简洁桌面|com\.optimisticat\.tvho)/.test((dump.match(/Bound services:[\s\S]*?Enabled services/) || [""])[0]);
  done(bound ? `已恢复（${TV}）` : `已写回设置但服务没绑上（${TV}）`);
  console.log(`【电视桌面】主页键接管被系统清掉了（桌面应用被强停），已自动恢复${bound ? "" : "，但服务没绑上，需要看一眼"}。现场日志在 tv.mjs 旁边的 state/guard.log。`);
} else if (cmd === "shot") {
  const name = rest[0] || stamp();
  mkdirSync(join(HERE, "shots"), { recursive: true });
  sh("screencap -p /sdcard/_shot.png");
  adb("pull", "/sdcard/_shot.png", join(HERE, "shots", `${name}.png`));
  sh("rm /sdcard/_shot.png");
  console.log(join(HERE, "shots", `${name}.png`));
} else if (cmd === "key") {
  for (const k of rest) sh(`input keyevent ${/^\d+$/.test(k) ? k : `KEYCODE_${k.toUpperCase()}`}`);
} else {
  console.log(readFileSync(fileURLToPath(import.meta.url), "utf8").split("*/")[0]);
}
