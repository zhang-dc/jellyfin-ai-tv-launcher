#!/usr/bin/env node
/**
 * build.mjs — 不用 Gradle，直接用 SDK 工具链把极简桌面打成签名好的 APK。
 *   aapt2 compile/link → javac → d8 → 塞 classes.dex → zipalign → apksigner
 *
 * 工具链在 tools/android-sdk（不入库）：cmdline-tools 装 build-tools;34.0.0 + platforms;android-29（见 README）。
 * JDK 用本机的 Microsoft OpenJDK 17。签名用 launcher/debug.keystore（首次自动生成，不入库）——
 * 以后升级必须用同一把钥匙，不然电视上要先卸载旧版才能装。
 *
 * 用法：node build.mjs   → out/tvhome.apk
 */

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, readdirSync, statSync, copyFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SDK = join(HERE, "..", "tools", "android-sdk");
const BT = join(SDK, "build-tools", "34.0.0");
const ANDROID_JAR = join(SDK, "platforms", "android-29", "android.jar");
const JAVA_HOME = process.env.JAVA_HOME?.replace(/\\$/, "") || "C:\\Program Files\\Microsoft\\jdk-17.0.20.8-hotspot";
const JAVAC = join(JAVA_HOME, "bin", "javac.exe");
const KEYTOOL = join(JAVA_HOME, "bin", "keytool.exe");
const OUT = join(HERE, "out");
const BUILD = join(OUT, "build");
const KEYSTORE = join(HERE, "debug.keystore");

const run = (cmd, args) => execFileSync(cmd, args, { stdio: "inherit", env: { ...process.env, JAVA_HOME }, shell: cmd.endsWith(".bat") });
const files = (dir, ext) => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? files(p, ext) : p.endsWith(ext) ? [p] : [];
});

rmSync(BUILD, { recursive: true, force: true });
mkdirSync(join(BUILD, "classes"), { recursive: true });

// 1. 资源
run(join(BT, "aapt2.exe"), ["compile", "--dir", join(HERE, "res"), "-o", join(BUILD, "res.zip")]);
run(join(BT, "aapt2.exe"), ["link", "-I", ANDROID_JAR, "--manifest", join(HERE, "AndroidManifest.xml"),
  "--java", join(BUILD, "gen"), "-A", join(HERE, "assets"), "-o", join(BUILD, "unsigned.apk"), join(BUILD, "res.zip")]);

// 2. Java → class（目标 Java 8，d8 再降到 dex）
run(JAVAC, ["-encoding", "UTF-8", "--release", "8", "-classpath", ANDROID_JAR, "-d", join(BUILD, "classes"),
  "-Xlint:-options", ...files(join(HERE, "src"), ".java"), ...files(join(BUILD, "gen"), ".java")]);

// 3. class → dex，塞进 apk
run(join(BT, "d8.bat"), ["--release", "--min-api", "21", "--lib", ANDROID_JAR, "--output", BUILD,
  ...files(join(BUILD, "classes"), ".class")]);
run("C:\\Program Files\\7-Zip\\7z.exe", ["a", "-tzip", join(BUILD, "unsigned.apk"), join(BUILD, "classes.dex")]);

// 4. 对齐 + 签名
if (!existsSync(KEYSTORE)) {
  run(KEYTOOL, ["-genkeypair", "-keystore", KEYSTORE, "-storepass", "android", "-keypass", "android",
    "-alias", "tvhome", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=OptimistiCAT TV Home"]);
}
run(join(BT, "zipalign.exe"), ["-f", "-p", "4", join(BUILD, "unsigned.apk"), join(BUILD, "aligned.apk")]);
run(join(BT, "apksigner.bat"), ["sign", "--ks", KEYSTORE, "--ks-pass", "pass:android", "--ks-key-alias", "tvhome",
  "--out", join(OUT, "tvhome.apk"), join(BUILD, "aligned.apk")]);
console.log(`\n✓ ${join(OUT, "tvhome.apk")}`);
