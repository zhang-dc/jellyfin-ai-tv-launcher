#!/usr/bin/env node
/**
 * build.mjs — 不用 Gradle，直接用 SDK 工具链把极简桌面打成签名好的 APK。
 *   aapt2 compile/link → javac → d8 → 塞 classes*.dex → zipalign → apksigner
 *
 * 工具链在 tools/android-sdk（不入库）：cmdline-tools 装 build-tools;34.0.0 + platforms;android-34（见 README）。
 * JDK 用本机的 Microsoft OpenJDK 17（JAVA_HOME 可覆盖）。签名用 launcher/debug.keystore（首次自动生成，不入库）——
 * 以后升级必须用同一把钥匙，不然电视上要先卸载旧版才能装。
 *
 * 第三方库（播放器内核 Media3 等）由 deps.mjs 下到 libs/：aar 的资源一起 link 并给每个库生成 R，
 * 代码 jar 进 javac classpath 和 d8。没有 libs/ 就先跑 node deps.mjs。
 *
 * 用法：node build.mjs   → out/tvhome.apk
 */

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, readdirSync, statSync, readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SDK = join(HERE, "..", "tools", "android-sdk");
const BT = join(SDK, "build-tools", "34.0.0");
// 编译用 android-34（androidx.core 的资源引用了安卓 12 才有的 attr）；运行行为由 manifest 的 targetSdk 29 决定
const ANDROID_JAR = join(SDK, "platforms", "android-34", "android.jar");
const JAVA_HOME = process.env.JAVA_HOME?.replace(/\\$/, "") || "C:\\Program Files\\Microsoft\\jdk-17.0.20.8-hotspot";
const JAVAC = join(JAVA_HOME, "bin", "javac.exe");
const JAR = join(JAVA_HOME, "bin", "jar.exe");
const KEYTOOL = join(JAVA_HOME, "bin", "keytool.exe");
const SEVENZ = "C:\\Program Files\\7-Zip\\7z.exe";
const OUT = join(HERE, "out");
const BUILD = join(OUT, "build");
const KEYSTORE = join(HERE, "debug.keystore");

const run = (cmd, args) => execFileSync(cmd, args, { stdio: "inherit", env: { ...process.env, JAVA_HOME }, shell: cmd.endsWith(".bat") });
const files = (dir, ext) => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? files(p, ext) : p.endsWith(ext) ? [p] : [];
});

const LIB_INDEX = join(HERE, "libs", "index.json");
if (!existsSync(LIB_INDEX)) { console.error("缺依赖：先跑 node deps.mjs"); process.exit(1); }
const libs = JSON.parse(readFileSync(LIB_INDEX, "utf8"));
const libJars = libs.flatMap((l) => l.jars);

rmSync(BUILD, { recursive: true, force: true });
mkdirSync(join(BUILD, "classes"), { recursive: true });

// 1. 资源：自己的 + 每个 aar 的。库代码引用的是它自己包名下的 R，所以 --extra-packages 给每个库包名各生成一份 R
run(join(BT, "aapt2.exe"), ["compile", "--dir", join(HERE, "res"), "-o", join(BUILD, "res.zip")]);
const resZips = [join(BUILD, "res.zip")];
libs.filter((l) => l.res).forEach((l, i) => {
  const z = join(BUILD, `lib${i}.zip`);
  run(join(BT, "aapt2.exe"), ["compile", "--dir", l.res, "-o", z]);
  resZips.push(z);
});
const extraPkgs = [...new Set(libs.filter((l) => l.res && l.pkg).map((l) => l.pkg))];
run(join(BT, "aapt2.exe"), ["link", "-I", ANDROID_JAR, "--manifest", join(HERE, "AndroidManifest.xml"), "--auto-add-overlay",
  ...(extraPkgs.length ? ["--extra-packages", extraPkgs.join(":")] : []),
  "--java", join(BUILD, "gen"), "-A", join(HERE, "assets"), "-o", join(BUILD, "unsigned.apk"), ...resZips]);

// 2. Java → class（目标 Java 8，d8 再降到 dex）。参数走 @文件：几十个 jar + 源文件会超过 Windows 命令行长度上限
const javacArgs = ["-encoding", "UTF-8", "--release", "8", "-classpath", [ANDROID_JAR, ...libJars].join(";"),
  "-d", join(BUILD, "classes"), "-Xlint:-options", ...files(join(HERE, "src"), ".java"), ...files(join(BUILD, "gen"), ".java")];
writeFileSync(join(BUILD, "javac.args"), javacArgs.map((a) => `"${a.replace(/\\/g, "/")}"`).join("\n"));
run(JAVAC, [`@${join(BUILD, "javac.args")}`]);
// 自己的 class 先打成一个 jar，d8 的输入就只是一串 jar
run(JAR, ["cf", join(BUILD, "app.jar"), "-C", join(BUILD, "classes"), "."]);

// 3. jar → dex，塞进 apk（方法数超 64K 时 d8 会出 classes2.dex…，全都要塞）
run(join(BT, "d8.bat"), ["--release", "--min-api", "21", "--lib", ANDROID_JAR, "--output", BUILD, join(BUILD, "app.jar"), ...libJars]);
const dexes = readdirSync(BUILD).filter((f) => /^classes\d*\.dex$/.test(f)).map((f) => join(BUILD, f));
run(SEVENZ, ["a", "-tzip", join(BUILD, "unsigned.apk"), ...dexes]);

// 4. 对齐 + 签名
if (!existsSync(KEYSTORE)) {
  run(KEYTOOL, ["-genkeypair", "-keystore", KEYSTORE, "-storepass", "android", "-keypass", "android",
    "-alias", "tvhome", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=TV Home"]);
}
run(join(BT, "zipalign.exe"), ["-f", "-p", "4", join(BUILD, "unsigned.apk"), join(BUILD, "aligned.apk")]);
run(join(BT, "apksigner.bat"), ["sign", "--ks", KEYSTORE, "--ks-pass", "pass:android", "--ks-key-alias", "tvhome",
  "--out", join(OUT, "tvhome.apk"), join(BUILD, "aligned.apk")]);
console.log(`\n✓ ${join(OUT, "tvhome.apk")}`);
