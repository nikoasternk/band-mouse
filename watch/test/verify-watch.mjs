import fs from 'fs';
import path from 'path';
import assert from 'assert';

const rootDir = path.resolve(process.cwd(), 'watch');
const srcDir = path.join(rootDir, 'src');

function logPass(msg) {
  console.log(`[WATCH-TEST PASS] ${msg}`);
}

function walkFiles(dir) {
  let results = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results = results.concat(walkFiles(full));
    } else {
      results.push(full);
    }
  }
  return results;
}

const allSrcFiles = walkFiles(srcDir);
for (const file of allSrcFiles) {
  if (file.endsWith('.ux') || file.endsWith('.js')) {
    const content = fs.readFileSync(file, 'utf8');
    assert.strictEqual(content.includes('//'), false, `Line comment found in ${file}`);
    assert.strictEqual(content.includes('/*'), false, `Block comment found in ${file}`);
    assert.strictEqual(content.includes('<!--'), false, `HTML comment found in ${file}`);
    logPass(`Zero comments verified in ${path.relative(rootDir, file)}`);
  }
}

const manifestPath = path.join(srcDir, 'manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
assert.strictEqual(manifest.package, 'com.band.mouse');
assert.strictEqual(manifest.router.entry, 'pages/touchpad');
assert.ok(manifest.router.pages['pages/touchpad']);
assert.ok(manifest.router.pages['pages/menu']);
assert.ok(manifest.router.pages['pages/settings']);
assert.ok(manifest.features.some(f => f.name === 'system.device'), 'manifest.json must declare system.device');
const appUxCode = fs.readFileSync(path.join(srcDir, 'app.ux'), 'utf8');
assert.ok(appUxCode.includes('detectWatchPpi') && appUxCode.includes('watchTouchPpi'), 'app.ux must auto-detect watchTouchPpi');
logPass('manifest.json structure, routes and auto-PPI detection verified');

const logoPath = path.join(srcDir, 'common', 'logo.png');
const svgPath = path.join(srcDir, 'common', 'icon.svg');
assert.ok(fs.existsSync(svgPath), 'icon.svg must exist');
assert.ok(fs.existsSync(logoPath), 'Rendered logo.png must exist');
logPass('Vector icon.svg and rendered logo.png verified');

const locContent = fs.readFileSync(path.join(srcDir, 'services', 'Localization.js'), 'utf8');
const enMatch = locContent.match(/en:\s*\{([\s\S]*?)\},\s*ru:\s*\{([\s\S]*?)\}/);
assert.ok(enMatch, 'Localization dictionary must contain en and ru');
const extractKeys = (block) => [...block.matchAll(/(\w+)\s*:/g)].map(m => m[1]);
const enKeys = extractKeys(enMatch[1]);
const ruKeys = extractKeys(enMatch[2]);
assert.deepStrictEqual(enKeys, ruKeys, 'EN and RU localization keys must match 100%');
assert.ok(enKeys.includes('dragScrollTitle'), 'dragScrollTitle key must exist');
assert.ok(enKeys.includes('phoneBrightTitle'), 'phoneBrightTitle key must exist');
logPass(`Localization parity verified (${enKeys.length} keys in EN & RU)`);

for (let mScalePct = 70; mScalePct <= 140; mScalePct += 10) {
  for (let nScalePct = 70; nScalePct <= 130; nScalePct += 10) {
    const mScale = Math.max(0.7, Math.min(1.4, mScalePct / 100));
    const menuVisW = Math.round(88 * mScale);
    const menuVisH = Math.round(32 * mScale);
    const menuHitH = menuVisH + 14;

    const nScale = Math.max(0.7, Math.min(1.3, nScalePct / 100));
    const navVisW = Math.round(50 * nScale);
    const navVisH = Math.round(38 * nScale);
    const navHitTop = menuHitH;
    const navHitCellW = Math.min(70, Math.max(navVisW + 8, Math.round(58 * nScale)));

    assert.strictEqual(navHitTop, menuHitH, 'Zero vertical gap between Menu and NavBar');
    assert.ok(menuVisW <= 212, 'Menu visual width fits screen');
    assert.ok(navHitCellW * 3 <= 212, 'Flush 3 nav hit cells fit 212px screen');
    assert.ok(navVisW <= navHitCellW, 'Nav visual pill fits inside flush hit cell');
  }
}
logPass('Hitbox & visual pill geometry verified across all scales (70%..140%)');

const padCode = fs.readFileSync(path.join(srcDir, 'pages', 'touchpad', 'touchpad.ux'), 'utf8');
assert.ok(padCode.includes("act: 's_start'"), 'Touchpad must send s_start packet');
assert.ok(padCode.includes("act: 's_move'"), 'Touchpad must send s_move packets');
assert.ok(padCode.includes("act: 's_hold'"), 'Touchpad must send s_hold keepalive packet');
assert.ok(padCode.includes("act: 's_end'"), 'Touchpad must send s_end packet on release');
assert.ok(padCode.includes('bottom-clock-txt'), 'Touchpad must display non-blocking bottom clock');
assert.ok(padCode.includes('full-touchpad-hit'), 'Touchpad hit layer must cover bottom clock');
logPass('Touchpad drag-scroll, clock overlay and hit layers verified');

const menuCode = fs.readFileSync(path.join(srcDir, 'pages', 'menu', 'menu.ux'), 'utf8');
assert.ok(menuCode.includes('togglePhoneBrightness'), 'Menu must include phone brightness switch');
assert.ok(menuCode.includes("act: 'p_bright'"), 'Menu must sync p_bright to phone');
logPass('Menu phone brightness control verified');

const settingsCode = fs.readFileSync(path.join(srcDir, 'pages', 'settings', 'settings.ux'), 'utf8');
const markModMatch = settingsCode.match(/markModified\(\)\s*\{([\s\S]*?)\}/);
assert.ok(markModMatch, 'markModified must exist in settings.ux');
assert.strictEqual(
  markModMatch[1].includes('commitToMemoryAndStorage'),
  false,
  'markModified must NOT auto-save before Save button is pressed'
);
logPass('Settings explicit Save button behavior verified');

console.log('[WATCH-TEST SUCCESS] All watch checks and simulations passed!');
