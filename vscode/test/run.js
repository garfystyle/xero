'use strict';

const { runTests } = require('@vscode/test-electron');
const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

async function main() {
  const vsix = path.resolve(process.argv[2]);
  const code = process.env.VSCODE_PATH || 'C:/Program Files/Microsoft VS Code/Code.exe';
  const cli = path.join(path.dirname(code), 'bin', 'code.cmd');
  const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'xero-vscode-'));
  const extensions = path.join(sandbox, 'extensions');
  const user = path.join(sandbox, 'user');
  const workspace = path.join(sandbox, 'workspace');
  fs.cpSync(path.join(__dirname, 'workspace'), workspace, { recursive: true });
  const env = { ...process.env };
  delete env.JAVA_HOME;
  env.PATH = (env.PATH || '').split(path.delimiter).filter(p => !/java|jdk|jre|adoptium/i.test(p)).join(path.delimiter);
  const installed = cp.spawnSync('cmd', ['/c', cli, '--extensions-dir', extensions, '--user-data-dir', user, '--install-extension', vsix],
    { env, encoding: 'utf8' });
  console.log((installed.stdout || '') + (installed.stderr || ''));
  if (installed.status !== 0) process.exit(1);
  const report = path.join(sandbox, 'report.json');
  let failed = false;
  try {
    await runTests({
      vscodeExecutablePath: code,
      extensionDevelopmentPath: path.join(__dirname, 'runner'),
      extensionTestsPath: path.join(__dirname, 'suite.js'),
      launchArgs: [workspace, '--extensions-dir', extensions, '--user-data-dir', user, '--disable-workspace-trust',
        '--skip-welcome', '--skip-release-notes'],
      extensionTestsEnv: { XERO_REPORT: report, XERO_UPLOAD: process.env.XERO_UPLOAD || '', JAVA_HOME: '', PATH: env.PATH }
    });
  } catch (e) {
    failed = true;
  }
  if (fs.existsSync(report)) {
    for (const r of JSON.parse(fs.readFileSync(report, 'utf8'))) console.log((r.ok ? 'да   ' : 'НЕТ  ') + r.name + (r.detail ? ' — ' + r.detail : ''));
  } else {
    console.log('отчёта нет');
    failed = true;
  }
  process.exit(failed ? 1 : 0);
}

main();
