#!/usr/bin/env node
/**
 * deps.mjs — 不用 Gradle，把 Maven 依赖（含传递依赖）下载到 libs/，供 build.mjs 编进 APK。
 *
 * 只做最小一套：从 Google Maven / Maven Central 取 POM，按 compile + runtime 作用域递归，
 * 版本冲突取最高；缺版本号的去父 POM 的 dependencyManagement 里找。
 * aar 解出 classes.jar（+ libs/*.jar）、res/ 和 manifest 里的 package（build.mjs 要给它生成 R）。
 *
 * 用法：node deps.mjs      → libs/<group>/<artifact>/… + libs/index.json
 * 代理：默认走 http://127.0.0.1:7897（HTTPS_PROXY 环境变量可覆盖，设成空字符串则直连）。
 */

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const LIBS = join(HERE, "libs");
const SEVENZ = "C:\\Program Files\\7-Zip\\7z.exe";
const PROXY = process.env.HTTPS_PROXY ?? "http://127.0.0.1:7897";
const REPOS = ["https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"];

/** 直接依赖：播放器内核（ExoPlayer）+ HLS（转码兜底） */
const ROOTS = [
  "androidx.media3:media3-exoplayer:1.4.1",
  "androidx.media3:media3-exoplayer-hls:1.4.1",
];
/** 不要的传递依赖：只有注解 / 编译期检查，运行时用不到 */
const SKIP = new Set([
  "com.google.code.findbugs:jsr305", "org.checkerframework:checker-qual", "org.checkerframework:checker-compat-qual",
  "com.google.errorprone:error_prone_annotations", "com.google.j2objc:j2objc-annotations",
  "com.google.guava:listenablefuture", "org.jetbrains:annotations",
]);

const curl = (url, out) => {
  // --ssl-no-revoke：Windows 的 curl（Schannel）走代理时查不了证书吊销列表，会报 35 握手失败
  const args = ["-sfL", "--ssl-no-revoke", "--retry", "2", "-o", out, url];
  if (PROXY) args.unshift("-x", PROXY);
  // 代理偶尔握手失败（35）这类网络错误 curl 的 --retry 不管，自己重试；22 = HTTP 4xx（真没有）不重试
  for (let i = 0; i < 5; i++) {
    try { execFileSync("curl", args, { stdio: "ignore" }); return true; } catch (e) {
      if (process.env.DEPS_DEBUG) console.error("curl failed", e.status, url);
      if (e.status === 22) return false;
      execFileSync(process.execPath, ["-e", "setTimeout(()=>{}, 800)"]);
    }
  }
  return false;
};
const fetchText = (url) => {
  const tmp = join(LIBS, ".tmp");
  return curl(url, tmp) ? readFileSync(tmp, "utf8") : null;
};
const path = (g, a, v, ext) => `${g.replace(/\./g, "/")}/${a}/${v}/${a}-${v}.${ext}`;
const tag = (xml, name) => (xml.match(new RegExp(`<${name}>([^<]*)</${name}>`)) || [])[1]?.trim();
const cmpVer = (x, y) => {
  const a = x.split(/[.-]/).map((n) => parseInt(n) || 0), b = y.split(/[.-]/).map((n) => parseInt(n) || 0);
  for (let i = 0; i < Math.max(a.length, b.length); i++) if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) - (b[i] || 0);
  return 0;
};

const pomCache = new Map();
function pom(g, a, v) {
  const key = `${g}:${a}:${v}`;
  if (pomCache.has(key)) return pomCache.get(key);
  let text = null, repo = null;
  for (const r of REPOS) { text = fetchText(`${r}/${path(g, a, v, "pom")}`); if (text) { repo = r; break; } }
  if (!text) throw new Error(`找不到 POM：${key}`);
  const parentXml = (text.match(/<parent>([\s\S]*?)<\/parent>/) || [])[1];
  const parent = parentXml ? pom(tag(parentXml, "groupId"), tag(parentXml, "artifactId"), tag(parentXml, "version")) : null;
  const props = { ...(parent?.props || {}), "project.version": v };
  for (const m of ((text.match(/<properties>([\s\S]*?)<\/properties>/) || [])[1] || "").matchAll(/<([\w.-]+)>([^<]*)<\/\1>/g)) props[m[1]] = m[2];
  const sub = (s) => s?.replace(/\$\{([^}]+)\}/g, (_, k) => props[k] ?? "");
  const managed = { ...(parent?.managed || {}) };
  for (const m of ((text.match(/<dependencyManagement>([\s\S]*?)<\/dependencyManagement>/) || [])[1] || "").matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    managed[`${tag(m[1], "groupId")}:${tag(m[1], "artifactId")}`] = sub(tag(m[1], "version"));
  }
  const body = text.replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/, "");
  const deps = [];
  for (const m of ((body.match(/<dependencies>([\s\S]*?)<\/dependencies>/) || [])[1] || "").matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const d = m[1];
    const scope = tag(d, "scope") || "compile";
    if (!["compile", "runtime"].includes(scope) || tag(d, "optional") === "true") continue;
    const dg = sub(tag(d, "groupId")), da = sub(tag(d, "artifactId"));
    let dv = sub(tag(d, "version")) || managed[`${dg}:${da}`];
    if (!dv) continue;
    dv = dv.replace(/^\[([^,\]]+)\]$/, "$1");   // androidx 常写 [1.4.1]
    deps.push({ g: dg, a: da, v: dv });
  }
  const res = { repo, packaging: tag(body.replace(/<parent>[\s\S]*?<\/parent>/, ""), "packaging") || "jar", deps, props, managed };
  pomCache.set(key, res);
  return res;
}

// ---- 解析依赖图：广度优先，同一个库取最高版本 ----
mkdirSync(LIBS, { recursive: true });
const chosen = new Map();   // g:a -> v
const queue = ROOTS.map((c) => { const [g, a, v] = c.split(":"); return { g, a, v }; });
while (queue.length) {
  const { g, a, v } = queue.shift();
  const ga = `${g}:${a}`;
  if (SKIP.has(ga)) continue;
  const cur = chosen.get(ga);
  if (cur && cmpVer(cur, v) >= 0) continue;
  chosen.set(ga, v);
  for (const d of pom(g, a, v).deps) queue.push(d);
}

// ---- 下载并解包 ----
const index = [];
for (const [ga, v] of [...chosen].sort()) {
  const [g, a] = ga.split(":");
  const p = pom(g, a, v);
  const dir = join(LIBS, g, a);
  const stamp = join(dir, ".version");
  if (!(existsSync(stamp) && readFileSync(stamp, "utf8") === v)) {
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    const ext = p.packaging === "aar" ? "aar" : "jar";
    const file = join(dir, `${a}.${ext}`);
    if (!curl(`${p.repo}/${path(g, a, v, ext)}`, file)) {
      // packaging 写的是 bundle / pom 之类时，两种都试
      const alt = ext === "aar" ? "jar" : "aar";
      if (!curl(`${p.repo}/${path(g, a, v, alt)}`, join(dir, `${a}.${alt}`))) { console.warn(`  跳过（无构件）：${ga}:${v}`); continue; }
    }
    if (existsSync(join(dir, `${a}.aar`))) {
      execFileSync(SEVENZ, ["x", "-y", `-o${join(dir, "aar")}`, join(dir, `${a}.aar`)], { stdio: "ignore" });
    }
    writeFileSync(stamp, v);
  }
  const entry = { id: `${ga}:${v}`, jars: [], res: null, pkg: null };
  if (existsSync(join(dir, "aar"))) {
    const aar = join(dir, "aar");
    if (existsSync(join(aar, "classes.jar"))) entry.jars.push(join(aar, "classes.jar"));
    if (existsSync(join(aar, "libs"))) for (const j of readdirSync(join(aar, "libs"))) if (j.endsWith(".jar")) entry.jars.push(join(aar, "libs", j));
    if (existsSync(join(aar, "res")) && readdirSync(join(aar, "res")).length) entry.res = join(aar, "res");
    const mf = existsSync(join(aar, "AndroidManifest.xml")) ? readFileSync(join(aar, "AndroidManifest.xml"), "utf8") : "";
    entry.pkg = (mf.match(/package="([^"]+)"/) || [])[1] || null;
  } else if (existsSync(join(dir, `${a}.jar`))) {
    entry.jars.push(join(dir, `${a}.jar`));
  }
  index.push(entry);
  console.log(`  ${entry.id}${entry.res ? "  (res: " + entry.pkg + ")" : ""}`);
}
rmSync(join(LIBS, ".tmp"), { force: true });
writeFileSync(join(LIBS, "index.json"), JSON.stringify(index, null, 1));
console.log(`\n✓ ${index.length} 个库 → libs/index.json`);
